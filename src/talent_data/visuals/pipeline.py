"""Visuals use cases: resolve, fetch, publish and verify the talent visuals of one build.

Online and offline are split on purpose:

* ``fetch_icons`` (and loading tables through an online source) may talk to the network;
* ``publish`` only reads what is already cached and writes the canonical files;
* ``check_canonical`` reads nothing but the committed repository files - no cache, no
  network, no Pillow - and is what CI runs.

Publishing is all-or-nothing: every sidecar and every image is produced in memory first,
and a build with a single unresolved or unconvertible icon publishes nothing.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path

from pydantic import ValidationError

from talent_data import paths
from talent_data.games import GameConfig
from talent_data.models.talents import TalentDataDocument
from talent_data.models.visuals import (
    TalentIconRegistry,
    TalentVisualsDocument,
    expected_content_hash,
    sha256_of,
)
from talent_data.pipeline import (
    PipelineError,
    check_game_version,
    read_document,
    read_manifest,
    validate_manifest_against_documents,
    write_json,
)
from talent_data.sources.base import Db2Source
from talent_data.sources.casc import CascFileSource
from talent_data.versions import ClientVersion
from talent_data.visuals.coverage import IconCoverageReport, build_coverage_report
from talent_data.visuals.icons import (
    ICON_DIR,
    ConvertedIcon,
    IconAssetError,
    build_icon_registry,
    convert_blp2,
    icon_file_name,
    read_png_size,
)
from talent_data.visuals.resolver import (
    VISUAL_SOURCE_TABLES,
    IconResolution,
    VisualSourceTables,
    build_visuals_document,
    resolve_document,
)

REGISTRY_FILE = "icons.json"


def load_canonical_dataset(game: GameConfig, version: ClientVersion) -> list[TalentDataDocument]:
    """The committed talent-data.v1 documents of ``version``, in manifest (class id) order.

    Visuals are derived from the *published* dataset, so they can only reference talent
    entries that exist in it. An incomplete or inconsistent dataset is refused.
    """
    check_game_version(game, version)
    mpath = paths.manifest_path(game.key, version)
    if not mpath.is_file():
        raise PipelineError(
            f"no normalized dataset for {version} ({mpath}); run "
            f"`python -m talent_data normalize --build {version} --class all` first"
        )
    manifest = read_manifest(mpath)
    documents = {
        entry.token: read_document(mpath.parent / entry.file) for entry in manifest.classes
    }
    problems = validate_manifest_against_documents(
        manifest,
        documents,
        expected_game=game.key,
        expected_build=str(version),
        expected_class_tokens=game.dataset_class_tokens,
    )
    if problems:
        raise PipelineError(
            f"normalized dataset {version} is not consistent; visuals are not derived from "
            "it: " + "; ".join(problems)
        )
    return [documents[entry.token] for entry in manifest.classes]


@dataclass(frozen=True)
class BuildResolution:
    game: GameConfig
    version: ClientVersion
    documents: tuple[TalentDataDocument, ...]
    by_class: Mapping[str, list[IconResolution]]  # class token -> resolutions, class id order

    @property
    def report(self) -> IconCoverageReport:
        return build_coverage_report(
            game=self.game.key, client_build=str(self.version), by_class=dict(self.by_class)
        )

    @property
    def file_data_ids(self) -> list[int]:
        """Distinct icon FileDataIDs of every resolved entry, ascending."""
        return sorted(
            {
                r.icon_file_data_id
                for resolutions in self.by_class.values()
                for r in resolutions
                if r.icon_file_data_id is not None
            }
        )


def fetch_visual_tables(source: Db2Source, version: ClientVersion) -> list[str]:
    """Make the tables icon resolution reads locally available."""
    for table in VISUAL_SOURCE_TABLES:
        source.ensure_table(version, table)
    return list(VISUAL_SOURCE_TABLES)


def resolve_build(source: Db2Source, game: GameConfig, version: ClientVersion) -> BuildResolution:
    documents = load_canonical_dataset(game, version)
    tables = VisualSourceTables.load(source, version)
    return BuildResolution(
        game=game,
        version=version,
        documents=tuple(documents),
        by_class={doc.class_.token: resolve_document(doc, tables) for doc in documents},
    )


def fetch_icons(
    casc: CascFileSource,
    version: ClientVersion,
    file_data_ids: Iterable[int],
    progress: Callable[[int, Path], None] | None = None,
) -> dict[int, Path]:
    out: dict[int, Path] = {}
    for file_data_id in file_data_ids:
        path = casc.fetch_file(version, file_data_id)
        out[file_data_id] = path
        if progress:
            progress(file_data_id, path)
    return out


@dataclass(frozen=True)
class PublishedVisuals:
    directory: Path
    documents: tuple[TalentVisualsDocument, ...]
    registry: TalentIconRegistry
    source_bytes: int
    image_bytes: int


def publish(
    resolution: BuildResolution, casc: CascFileSource, *, source_name: str
) -> PublishedVisuals:
    """Write ``data/visuals/<game>/<build>/``: nine sidecars, ``icons.json`` and
    ``icons/<FileDataID>.png`` - or nothing at all."""
    version = resolution.version
    documents = [
        build_visuals_document(doc, resolution.by_class[doc.class_.token], source_name=source_name)
        for doc in resolution.documents
    ]
    converted: list[ConvertedIcon] = [
        convert_blp2(file_data_id, casc.fetch_file(version, file_data_id).read_bytes())
        for file_data_id in resolution.file_data_ids
    ]
    registry = build_icon_registry(
        game=resolution.game.key,
        client_build=str(version),
        icons=converted,
        source_name=casc.name,
    )

    directory = paths.visuals_dir(resolution.game.key, version)
    files: dict[str, bytes] = {
        paths.visuals_path(resolution.game.key, version, d.class_token).name: d.to_json().encode(
            "utf-8"
        )
        for d in documents
    }
    files[REGISTRY_FILE] = registry.to_json().encode("utf-8")
    for icon in converted:
        files[icon_file_name(icon.file_data_id)] = icon.png

    for name, data in sorted(files.items()):
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.with_name(target.name + ".tmp")
        tmp.write_bytes(data)
        tmp.replace(target)
    # Drop what a previous run published and this one did not (e.g. an icon no talent uses
    # any more): the directory is exactly the published set.
    for stale in _relative_files(directory) - files.keys():
        (directory / stale).unlink()

    return PublishedVisuals(
        directory=directory,
        documents=tuple(documents),
        registry=registry,
        source_bytes=sum(icon.source_byte_length for icon in converted),
        image_bytes=sum(len(icon.png) for icon in converted),
    )


def write_coverage_report(report: IconCoverageReport, path: Path) -> None:
    write_json(report.to_json(), path)


# --------------------------------------------------------------------------- offline check


def _relative_files(directory: Path) -> set[str]:
    if not directory.is_dir():
        return set()
    return {p.relative_to(directory).as_posix() for p in directory.rglob("*") if p.is_file()}


def read_visuals_document(path: Path) -> TalentVisualsDocument:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read talent visuals {path}: {exc}") from exc
    if not isinstance(raw, dict) or raw.get("schemaVersion") != 1:
        version = raw.get("schemaVersion") if isinstance(raw, dict) else None
        raise PipelineError(f"{path}: unsupported talent-visuals schemaVersion {version!r}")
    try:
        return TalentVisualsDocument.model_validate(raw)
    except ValidationError as exc:
        raise PipelineError(f"{path}: not a talent-visuals.v1 document: {exc}") from exc


def read_icon_registry(path: Path) -> TalentIconRegistry:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PipelineError(f"cannot read icon registry {path}: {exc}") from exc
    if not isinstance(raw, dict) or raw.get("schemaVersion") != 1:
        version = raw.get("schemaVersion") if isinstance(raw, dict) else None
        raise PipelineError(f"{path}: unsupported talent-icons schemaVersion {version!r}")
    try:
        return TalentIconRegistry.model_validate(raw)
    except ValidationError as exc:
        raise PipelineError(f"{path}: not a talent-icons.v1 document: {exc}") from exc


def check_visuals_document(
    visuals: TalentVisualsDocument, talents: TalentDataDocument
) -> list[str]:
    """Problems that make ``visuals`` unusable next to ``talents`` (empty = consistent)."""
    problems: list[str] = []
    add = problems.append
    where = visuals.class_token
    if visuals.game != talents.game:
        add(f"{where}: visuals game {visuals.game!r} != dataset {talents.game!r}")
    if visuals.client_build != talents.client_build:
        add(
            f"{where}: visuals clientBuild {visuals.client_build!r} "
            f"!= dataset {talents.client_build!r}"
        )
    if visuals.class_token != talents.class_.token:
        add(f"{where}: visuals are for {visuals.class_token}, dataset is {talents.class_.token}")
    if visuals.talent_data_content_hash != talents.content_hash:
        add(
            f"{where}: visuals were resolved for talent-data {visuals.talent_data_content_hash}, "
            f"dataset is {talents.content_hash}"
        )
    if visuals.content_hash != expected_content_hash(visuals):
        add(f"{where}: contentHash does not match the document (stale or tampered)")

    entry_ids = [e.entry_id for e in visuals.entries]
    if entry_ids != sorted(entry_ids):
        add(f"{where}: entries are not ordered by entryId")
    if len(entry_ids) != len(set(entry_ids)):
        add(f"{where}: duplicate entryId in entries")
    known = {e.id: e for tree in talents.trees for node in tree.nodes for e in node.entries}
    unknown = sorted(set(entry_ids) - known.keys())
    missing = sorted(known.keys() - set(entry_ids))
    if unknown:
        add(f"{where}: visuals reference unknown talent entries {unknown}")
    if missing:
        add(f"{where}: talent entries without visuals {missing}")
    for entry in visuals.entries:
        talent = known.get(entry.entry_id)
        if talent is None:
            continue
        if entry.definition_id != talent.definition_id or entry.spell_id != talent.spell_id:
            add(
                f"{where}: entry {entry.entry_id} is definition {entry.definition_id} / spell "
                f"{entry.spell_id}, dataset says {talent.definition_id} / {talent.spell_id}"
            )
    return problems


def check_icon_registry(
    registry: TalentIconRegistry,
    directory: Path,
    *,
    game: str,
    client_build: str,
    used_file_data_ids: set[int],
) -> list[str]:
    """Registry <-> files <-> sidecars: no missing, orphan, empty or unsupported image."""
    problems: list[str] = []
    add = problems.append
    if registry.game != game:
        add(f"icon registry game {registry.game!r} != {game!r}")
    if registry.client_build != client_build:
        add(f"icon registry clientBuild {registry.client_build!r} != {client_build!r}")
    if registry.content_hash != expected_content_hash(registry):
        add("icon registry contentHash does not match the document (stale or tampered)")
    ids = [icon.file_data_id for icon in registry.icons]
    if ids != sorted(ids):
        add("icon registry is not ordered by fileDataId")
    if len(ids) != len(set(ids)):
        add("icon registry has duplicate fileDataId keys")
    files = [icon.file for icon in registry.icons]
    if len(files) != len(set(files)):
        add("icon registry maps several FileDataIDs to one file")

    for icon in registry.icons:
        if icon.file != icon_file_name(icon.file_data_id):
            add(f"icon {icon.file_data_id}: file is {icon.file!r}, expected its FileDataID path")
            continue
        path = directory / icon.file
        if not path.is_file():
            add(f"icon {icon.file_data_id}: {icon.file} is missing")
            continue
        data = path.read_bytes()
        if not data:
            add(f"icon {icon.file_data_id}: {icon.file} is empty")
            continue
        if len(data) != icon.byte_length or sha256_of(data) != icon.sha256:
            add(f"icon {icon.file_data_id}: {icon.file} does not match its recorded size/sha256")
        try:
            size = read_png_size(data)
        except IconAssetError as exc:
            add(f"icon {icon.file_data_id}: {icon.file}: {exc}")
            continue
        if size != (icon.width, icon.height):
            add(
                f"icon {icon.file_data_id}: image is {size}, "
                f"registry says {icon.width}x{icon.height}"
            )

    registered = set(ids)
    for fid in sorted(used_file_data_ids - registered):
        add(f"icon {fid} is used by a talent but not in the registry")
    for fid in sorted(registered - used_file_data_ids):
        add(f"icon {fid} is in the registry but no talent uses it")
    on_disk = {name for name in _relative_files(directory) if name.startswith(f"{ICON_DIR}/")}
    for orphan in sorted(on_disk - set(files)):
        add(f"{orphan} is not in the icon registry (orphan file)")
    return problems


def check_canonical(game: GameConfig, version: ClientVersion) -> list[str]:
    """Verify the committed visuals of ``version`` against the committed dataset.

    Pure repository check: reads ``data/normalized`` and ``data/visuals`` only.
    """
    documents = load_canonical_dataset(game, version)
    directory = paths.visuals_dir(game.key, version)
    problems: list[str] = []
    used: set[int] = set()
    expected_files = {REGISTRY_FILE}
    for talents in documents:
        path = paths.visuals_path(game.key, version, talents.class_.token)
        expected_files.add(path.name)
        try:
            visuals = read_visuals_document(path)
        except PipelineError as exc:
            problems.append(str(exc))
            continue
        problems.extend(check_visuals_document(visuals, talents))
        used.update(e.icon_file_data_id for e in visuals.entries)
    try:
        registry = read_icon_registry(directory / REGISTRY_FILE)
    except PipelineError as exc:
        problems.append(str(exc))
    else:
        problems.extend(
            check_icon_registry(
                registry,
                directory,
                game=game.key,
                client_build=str(version),
                used_file_data_ids=used,
            )
        )
    stray = {
        name for name in _relative_files(directory) if not name.startswith(f"{ICON_DIR}/")
    } - expected_files
    problems.extend(f"{name} is not part of the visuals of {version}" for name in sorted(stray))
    return problems
