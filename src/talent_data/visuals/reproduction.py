"""Does a regenerated visuals directory hold what the committed one holds? (M5.OTA.AUTO.1)

A forced re-preparation of a build ``main`` already carries regenerates its visuals on
another machine. The *content* of a build's visuals is reproducible: the same client files
(``sourceSha256``) decode to the same pixels. The PNG *bytes* are not: Pillow's wheels bundle
zlib-ng, and its deflate output differs between builds of the wheel - the committed
1.60.1.70450 icons were encoded with the macOS arm64 wheel, and GitHub's ubuntu-24.04 x86_64
runner compresses the same pixels of 353 of the 359 icons into other bytes
(``docs/talent-visuals.md`` §3).

So the comparison is by meaning, and strict about everything that carries meaning:

* the same files, nothing added or missing;
* every class sidecar: the same ``contentHash`` and hashed payload (only ``provenance``
  may differ - it names the tools, not the visuals);
* the icon registry: the same build, image format, source and retrieval, and per icon the
  same FileDataID, file, size, source container, source byte length and source sha256;
* every image: valid against its own registry entry on both sides, and - where the bytes
  differ - the same size, colour type and pixels (:func:`same_image`).

Only the converter provenance, the PNG bytes and what follows from them (``byteLength``,
``sha256``, the registry ``contentHash``) may differ. Then the committed bytes are the
canonical ones and are kept; any other difference is a real change of the visuals.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field

from pydantic import BaseModel, ValidationError

from talent_data.models.visuals import (
    IconAsset,
    TalentIconRegistry,
    TalentVisualsDocument,
    hashed_payload,
    sha256_of,
)
from talent_data.visuals.icons import IconAssetError, same_image

REGISTRY_FILE = "icons.json"
# Fields of an icon that come from the client file and the converter's fixed geometry; the
# remaining two (``byteLength``, ``sha256``) describe the encoded bytes.
_SOURCE_FIELDS = ("file", "width", "height", "source_format", "source_byte_length", "source_sha256")


@dataclass(frozen=True)
class VisualsComparison:
    problems: list[str] = field(default_factory=list)
    identical_files: int = 0
    # FileDataIDs whose PNG bytes differ while the image is the same
    reencoded: list[int] = field(default_factory=list)
    converters: tuple[str, str] | None = None  # (committed, regenerated) when they differ

    @property
    def equivalent(self) -> bool:
        return not self.problems


def _parse[M: BaseModel](model: type[M], data: bytes, name: str, problems: list[str]) -> M | None:
    try:
        return model.model_validate_json(data)
    except ValidationError as exc:
        problems.append(f"{name}: not a valid {model.__name__}: {exc.error_count()} error(s)")
        return None


def _payload(document: BaseModel) -> dict[str, object]:
    return hashed_payload(document.model_dump(mode="json", by_alias=True))


def _compare_sidecar(name: str, committed: bytes, regenerated: bytes, problems: list[str]) -> None:
    ours = _parse(TalentVisualsDocument, committed, f"committed {name}", problems)
    theirs = _parse(TalentVisualsDocument, regenerated, f"regenerated {name}", problems)
    if ours is None or theirs is None:
        return
    if ours.content_hash != theirs.content_hash or _payload(ours) != _payload(theirs):
        problems.append(f"{name}: the talent visuals differ (entries, icons or contentHash)")


def _image_problem(
    icon: IconAsset, committed: bytes, regenerated: bytes, theirs: IconAsset
) -> str | None:
    fid = icon.file_data_id
    for label, entry, data in (
        ("committed", icon, committed),
        ("regenerated", theirs, regenerated),
    ):
        if len(data) != entry.byte_length or sha256_of(data) != entry.sha256:
            return f"icon {fid}: the {label} {entry.file} does not match its registry entry"
    try:
        if not same_image(committed, regenerated):
            return f"icon {fid}: the image differs (size, colour type or pixels)"
    except IconAssetError as exc:
        return f"icon {fid}: cannot compare the images: {exc}"
    return None


def compare_visuals(
    committed: Mapping[str, bytes], regenerated: Mapping[str, bytes]
) -> VisualsComparison:
    """Compare two visuals directories of one build, given as ``{relative path: bytes}``."""
    problems: list[str] = []
    missing = sorted(committed.keys() - regenerated.keys())
    added = sorted(regenerated.keys() - committed.keys())
    if missing:
        problems.append(f"not regenerated: {missing[:10]}" + (" ..." if len(missing) > 10 else ""))
    if added:
        problems.append(f"new files: {added[:10]}" + (" ..." if len(added) > 10 else ""))
    if REGISTRY_FILE not in committed or REGISTRY_FILE not in regenerated:
        problems.append(f"{REGISTRY_FILE} is missing")
        return VisualsComparison(problems)

    shared = sorted(committed.keys() & regenerated.keys())
    identical = sum(committed[name] == regenerated[name] for name in shared)
    for name in shared:
        if name != REGISTRY_FILE and "/" not in name and committed[name] != regenerated[name]:
            _compare_sidecar(name, committed[name], regenerated[name], problems)

    ours = _parse(TalentIconRegistry, committed[REGISTRY_FILE], "committed icons.json", problems)
    theirs = _parse(
        TalentIconRegistry, regenerated[REGISTRY_FILE], "regenerated icons.json", problems
    )
    if ours is None or theirs is None:
        return VisualsComparison(problems, identical)

    for attribute in ("schema_version", "game", "client_build", "image_format"):
        if getattr(ours, attribute) != getattr(theirs, attribute):
            problems.append(f"{REGISTRY_FILE}: {attribute} differs")
    for attribute in ("source", "retrieval"):
        if getattr(ours.provenance, attribute) != getattr(theirs.provenance, attribute):
            problems.append(f"{REGISTRY_FILE}: provenance.{attribute} differs")

    ours_ids = [icon.file_data_id for icon in ours.icons]
    theirs_ids = [icon.file_data_id for icon in theirs.icons]
    if ours_ids != theirs_ids:
        gone = sorted(set(ours_ids) - set(theirs_ids))
        added_ids = sorted(set(theirs_ids) - set(ours_ids))
        problems.append(
            f"{REGISTRY_FILE}: the icon FileDataIDs differ (no longer used {gone}, new {added_ids})"
        )
        return VisualsComparison(problems, identical)

    reencoded: list[int] = []
    for icon, other in zip(ours.icons, theirs.icons, strict=True):
        changed = [f for f in _SOURCE_FIELDS if getattr(icon, f) != getattr(other, f)]
        if changed:
            problems.append(f"icon {icon.file_data_id}: {', '.join(changed)} differ")
            continue
        old, new = committed.get(icon.file), regenerated.get(other.file)
        if old is None or new is None:
            continue  # reported above as a missing or new file
        problem = _image_problem(icon, old, new, other)
        if problem is not None:
            problems.append(problem)
        elif old != new:
            reencoded.append(icon.file_data_id)

    converters = (
        (ours.provenance.converter, theirs.provenance.converter)
        if ours.provenance.converter != theirs.provenance.converter
        else None
    )
    return VisualsComparison(problems, identical, reencoded, converters)
