"""The pre-approval review report: what a release changes vs. the active production one.

Pipeline step 3 (publisher design §2): a human reads this before approving or
promoting. It answers, from the release snapshot and the live catalog alone:

* which classes' talent data changed (the semantic diff engine of ``talent_data.diff``,
  the same one developers use between builds);
* how ``warningsByCode`` moves, and what gate G6's engine findings are;
* what happens to icons, tooltips and locales;
* how many bytes a device that already has the active release will download
  (content-addressed blobs are shared between releases).
"""

from __future__ import annotations

from pydantic import BaseModel

from talent_data.diff.engine import diff_documents
from talent_data.diff.render import render_text
from talent_data.models.manifest import DatasetManifest
from talent_data.models.talents import TalentDataDocument
from talent_data.models.visuals import TalentIconRegistry
from talent_data.ota.errors import OtaPublisherError
from talent_data.ota.gates import ReleaseUnderTest, _dataset_manifest, talent_documents
from talent_data.ota.mechanics import load_baseline, new_unsupported_mechanics
from talent_data.ota.models import CatalogRelease, FileRole, OtaCatalog, OtaReleaseManifest
from talent_data.ota.store import BlobStore, blob_key, read_release_manifest
from talent_data.ota.verify import parse_strict


def _locales(paths: dict[str, str], game: str, build: str) -> set[str]:
    prefix = f"localization/{game}/{build}/"
    return {
        path.removeprefix(prefix).split("/")[0]
        for path in paths
        if path.startswith(prefix) and "/" in path.removeprefix(prefix)
    }


def render_review_report(
    under_test: ReleaseUnderTest, live: OtaCatalog | None, store: BlobStore
) -> str:
    """The human-readable review of one release against the live production catalog."""
    rebuild = under_test.rebuild
    m = under_test.manifest
    lines = [
        f"release {m.release_id} ({m.game}, min data level {m.min_data_level})",
        f"  commit {m.provenance.repository_commit}",
        f"  validation: {m.provenance.validation}",
        f"  files {len(m.files)}, distinct blobs {len(rebuild.blobs)}, "
        f"total {m.total_byte_length:,} bytes",
    ]

    active: CatalogRelease | None = None
    if live is not None:
        active = next((r for r in live.releases if r.release_id == live.active_release_id), None)
        lines.append(
            f"  live production catalog: sequence {live.sequence}, "
            f"{len(live.releases)} release(s), active {live.active_release_id}"
        )

    # G6 findings as the reviewer sees them
    try:
        baseline = load_baseline(m.min_data_level)
        findings = new_unsupported_mechanics(talent_documents(under_test), baseline)
        if findings:
            lines.append(
                f"  G6 engine findings OUTSIDE the level-{m.min_data_level} baseline "
                "(production would be a STOP):"
            )
            lines += [
                f"    {f.class_token} tree {f.tree_id} node {f.node_id} {f.code}" for f in findings
            ]
        else:
            lines.append(
                f"  G6 engine findings: none outside the level-{m.min_data_level} baseline"
            )
    except FileNotFoundError:
        lines.append(
            f"  G6 engine findings: level {m.min_data_level} has NO baseline file "
            "(production is a STOP until the level exists)"
        )

    new_dataset = _dataset_manifest(under_test)
    new_warnings: dict[str, int] = {}
    for entry in new_dataset.classes:
        for code, count in entry.validation.warnings_by_code.items():
            new_warnings[code] = new_warnings.get(code, 0) + count

    new_paths = {f.path: f.sha256 for f in m.files}
    lines.append(f"  locales: {sorted(_locales(new_paths, m.game, m.client_build)) or 'none'}")

    if active is None or active.release_id == m.release_id:
        lines.append("  no other active production release to compare against")
        lines.append(f"  warnings by code: {dict(sorted(new_warnings.items())) or 'none'}")
        return "\n".join(lines) + "\n"

    # ---- compare against the active production release, from its blobs
    try:
        _, old_manifest = read_release_manifest(store, active)
        old_dataset = _store_parse(store, old_manifest, FileRole.DATASET_MANIFEST, DatasetManifest)
        old_registry = _store_parse(store, old_manifest, FileRole.ICON_REGISTRY, TalentIconRegistry)
        old_docs = _store_documents(store, old_manifest)
    except OtaPublisherError as exc:
        lines.append(f"  cannot read the active release from the store: {exc}")
        return "\n".join(lines) + "\n"

    old_warnings: dict[str, int] = {}
    for entry in old_dataset.classes:
        for code, count in entry.validation.warnings_by_code.items():
            old_warnings[code] = old_warnings.get(code, 0) + count
    moved = {
        code: (new_warnings.get(code, 0), old_warnings.get(code, 0))
        for code in sorted(new_warnings.keys() | old_warnings.keys())
        if new_warnings.get(code, 0) != old_warnings.get(code, 0)
    }
    lines.append(
        "  warnings by code vs active: "
        + (
            "; ".join(f"{code} {old}->{new}" for code, (new, old) in moved.items())
            if moved
            else "unchanged"
        )
    )

    old_by_token = {doc.class_.token: doc for doc in old_docs}
    changed_classes = 0
    for new_doc in talent_documents(under_test):
        old_doc = old_by_token.get(new_doc.class_.token)
        if old_doc is None:
            lines.append(f"  {new_doc.class_.token}: NEW class ({len(new_doc.trees)} trees)")
            changed_classes += 1
            continue
        report = diff_documents(old_doc, new_doc)
        if not report.is_empty:
            changed_classes += 1
            lines.append(render_text(report, m.game).rstrip("\n"))
    lines.append(f"  classes with talent-data changes: {changed_classes}")

    old_registry_icons = {icon.file_data_id: icon.sha256 for icon in old_registry.icons}
    new_registry = parse_strict(
        TalentIconRegistry, rebuild.snapshot[f"visuals/{m.game}/{m.client_build}/icons.json"]
    )
    new_icons = {icon.file_data_id: icon.sha256 for icon in new_registry.icons}
    shared_icons = new_icons.keys() & old_registry_icons.keys()
    icons_changed = [i for i in shared_icons if new_icons[i] != old_registry_icons[i]]
    lines.append(
        f"  icons: {len(new_icons)} registered; added "
        f"{len(new_icons.keys() - old_registry_icons.keys())}, removed "
        f"{len(old_registry_icons.keys() - new_icons.keys())}, changed {len(icons_changed)}"
    )

    old_paths = {f.path: f.sha256 for f in old_manifest.files}
    for label, directory in (
        ("tooltips", "tooltips"),
        ("localization", "localization"),
    ):
        old_dir = f"{directory}/{old_manifest.game}/{old_manifest.client_build}/"
        new_dir = f"{directory}/{m.game}/{m.client_build}/"
        old_shas = {sha for path, sha in old_paths.items() if path.startswith(old_dir)}
        new_shas = {sha for path, sha in new_paths.items() if path.startswith(new_dir)}
        lines.append(
            f"  {label} blobs: active {len(old_shas)}, this release {len(new_shas)}, "
            f"changed {len(new_shas ^ old_shas)}"
        )

    if _locales(old_paths, old_manifest.game, old_manifest.client_build) != _locales(
        new_paths, m.game, m.client_build
    ):
        lines.append(
            f"  locales vs active: "
            f"{sorted(_locales(old_paths, old_manifest.game, old_manifest.client_build))}"
            f" -> {sorted(_locales(new_paths, m.game, m.client_build))}"
        )

    old_blob_shas = {f.sha256 for f in old_manifest.files}
    download = sum(len(data) for sha, data in rebuild.blobs.items() if sha not in old_blob_shas)
    lines.append(
        f"  a device on {active.release_id} downloads {download:,} new bytes "
        f"(of {m.total_byte_length:,}; shared blobs are reused)"
    )
    return "\n".join(lines) + "\n"


def _store_parse[M: BaseModel](
    store: BlobStore, manifest: OtaReleaseManifest, role: FileRole, model: type[M]
) -> M:
    file = next(f for f in manifest.files if f.role is role)
    try:
        return parse_strict(model, store.get(blob_key(file.sha256)))
    except ValueError as exc:
        raise OtaPublisherError(f"{file.path}: {exc}") from exc


def _store_documents(store: BlobStore, manifest: OtaReleaseManifest) -> list[TalentDataDocument]:
    docs: list[TalentDataDocument] = []
    for file in manifest.files:
        if file.role is not FileRole.TALENT_DATA:
            continue
        try:
            docs.append(parse_strict(TalentDataDocument, store.get(blob_key(file.sha256))))
        except ValueError as exc:
            raise OtaPublisherError(f"{file.path}: {exc}") from exc
    return docs
