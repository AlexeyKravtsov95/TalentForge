"""Capture manifest - what the WoW capture addon must ask the client for.

The addon never carries hand-maintained talent ids. Its only talent input is
``GeneratedTalentManifest.lua``, rendered here from the committed ``talent-data.v1``
documents of one exact build: every talent entry with the identity needed to trace a
captured tooltip back to the dataset, plus the identity of the dataset itself.

``manifestHash`` covers everything in the manifest. A capture records the hash of the
manifest it was produced from; the importer recomputes it from the repository and refuses a
capture whose hash differs, so a capture can never be reused across builds or dataset
revisions.

The rendered Lua is deterministic: classes in manifest (class id) order, entries by entry
id inside a class, no timestamp.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from talent_data.games import GameConfig
from talent_data.models.talents import TalentDataDocument, content_hash
from talent_data.pipeline import PipelineError
from talent_data.versions import ClientVersion

CAPTURE_MANIFEST_FORMAT_VERSION: Literal[1] = 1

# The committed dataset is English, so the canonical capture is the enUS client.
CANONICAL_LOCALE = "enUS"

_SHA256_PATTERN = r"^sha256:[0-9a-f]{64}$"
_BUILD_PATTERN = r"^\d+\.\d+\.\d+\.\d+$"


class _Model(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        frozen=True,
        extra="forbid",
        strict=True,
    )


class DatasetIdentity(_Model):
    class_token: str = Field(min_length=1)
    content_hash: str = Field(
        pattern=_SHA256_PATTERN, description="contentHash of the class's talent-data.v1 document"
    )


class ManifestEntry(_Model):
    class_token: str = Field(min_length=1)
    tree_id: int = Field(gt=0)
    node_id: int = Field(gt=0)
    entry_id: int = Field(gt=0)
    spell_id: int = Field(gt=0)
    name: str = Field(min_length=1)
    max_rank: int = Field(gt=0)


class CaptureManifest(_Model):
    format_version: Literal[1]
    game: str = Field(min_length=1)
    client_build: str = Field(pattern=_BUILD_PATTERN)
    locale: str = Field(min_length=1)
    datasets: tuple[DatasetIdentity, ...]
    entries: tuple[ManifestEntry, ...]

    def hashed_payload(self) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True)

    @property
    def manifest_hash(self) -> str:
        return content_hash(self.hashed_payload())

    @property
    def rank_count(self) -> int:
        """Number of (entry, rank) tooltips a complete capture contains."""
        return sum(entry.max_rank for entry in self.entries)


def build_capture_manifest(
    game: GameConfig,
    version: ClientVersion,
    documents: list[TalentDataDocument],
    *,
    locale: str = CANONICAL_LOCALE,
) -> CaptureManifest:
    """Manifest of every talent entry of ``documents`` (the complete dataset of a build).

    Fails instead of emitting a manifest the client could not be asked about
    unambiguously: an entry without a name, or an entry id that occurs twice.
    """
    datasets: list[DatasetIdentity] = []
    entries: list[ManifestEntry] = []
    for doc in documents:
        if doc.game != game.key or doc.client_build != str(version):
            raise PipelineError(
                f"{doc.class_.token}: document is {doc.game} {doc.client_build}, "
                f"not {game.key} {version}"
            )
        token = doc.class_.token
        datasets.append(DatasetIdentity(class_token=token, content_hash=doc.content_hash))
        class_entries: list[ManifestEntry] = []
        for tree in doc.trees:
            for node in tree.nodes:
                for entry in node.entries:
                    if not entry.name:
                        raise PipelineError(
                            f"{token} entry {entry.id}: no name in the dataset; the capture "
                            "manifest needs one to identify the talent"
                        )
                    class_entries.append(
                        ManifestEntry(
                            class_token=token,
                            tree_id=tree.id,
                            node_id=node.id,
                            entry_id=entry.id,
                            spell_id=entry.spell_id,
                            name=entry.name,
                            max_rank=entry.max_rank,
                        )
                    )
        entries.extend(sorted(class_entries, key=lambda e: e.entry_id))

    duplicates = sorted(i for i, n in Counter(e.entry_id for e in entries).items() if n > 1)
    if duplicates:
        raise PipelineError(f"duplicate talent entry ids in the dataset: {duplicates}")
    tokens = [d.class_token for d in datasets]
    if len(set(tokens)) != len(tokens):
        raise PipelineError(f"duplicate class documents in the dataset: {tokens}")
    if not entries:
        raise PipelineError("the dataset has no talent entries; nothing to capture")
    return CaptureManifest(
        format_version=CAPTURE_MANIFEST_FORMAT_VERSION,
        game=game.key,
        client_build=str(version),
        locale=locale,
        datasets=tuple(datasets),
        entries=tuple(entries),
    )


# --------------------------------------------------------------------------- Lua rendering

_LUA_HEADER = """\
-- GENERATED FILE - DO NOT EDIT.
-- Rendered by `python -m talent_data tooltips manifest` from the committed
-- talent-data.v1 documents; `--check` (and the test suite) fails when it is out of date.
"""

_LUA_ESCAPES = {'"': '\\"', "\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t"}


def lua_string(text: str) -> str:
    """``text`` as a double-quoted Lua 5.1 string literal (UTF-8 bytes kept as they are)."""
    out: list[str] = []
    for ch in text:
        if ch in _LUA_ESCAPES:
            out.append(_LUA_ESCAPES[ch])
        elif ord(ch) < 0x20 or ord(ch) == 0x7F:
            out.append(f"\\{ord(ch):03d}")
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def render_lua(manifest: CaptureManifest) -> str:
    lines = [
        _LUA_HEADER.rstrip("\n"),
        "local _, ns = ...",
        "",
        "ns.manifest = {",
        f"    formatVersion = {manifest.format_version},",
        f"    game = {lua_string(manifest.game)},",
        f"    clientBuild = {lua_string(manifest.client_build)},",
        f"    locale = {lua_string(manifest.locale)},",
        f"    manifestHash = {lua_string(manifest.manifest_hash)},",
        f"    entryCount = {len(manifest.entries)},",
        f"    rankCount = {manifest.rank_count},",
        "    datasets = {",
    ]
    lines.extend(
        f"        {{ classToken = {lua_string(d.class_token)}, "
        f"contentHash = {lua_string(d.content_hash)} }},"
        for d in manifest.datasets
    )
    lines.append("    },")
    lines.append("    entries = {")
    lines.extend(
        f"        {{ classToken = {lua_string(e.class_token)}, treeId = {e.tree_id}, "
        f"nodeId = {e.node_id}, entryId = {e.entry_id}, spellId = {e.spell_id}, "
        f"name = {lua_string(e.name)}, maxRank = {e.max_rank} }},"
        for e in manifest.entries
    )
    lines.append("    },")
    lines.append("}")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- repository


def load_capture_manifest(game: GameConfig, version: ClientVersion) -> CaptureManifest:
    """The capture manifest of the committed, verified dataset of ``version``."""
    # Imported here: the dataset loader lives with the other sidecar pipeline and pulls in
    # the icon tooling, which rendering a manifest does not need.
    from talent_data.visuals.pipeline import load_canonical_dataset

    return build_capture_manifest(game, version, load_canonical_dataset(game, version))


def addon_manifest_problems(manifest: CaptureManifest, path: Path) -> list[str]:
    """Why the generated Lua at ``path`` is not exactly ``manifest`` (empty = in sync)."""
    if not path.is_file():
        return [f"{path} does not exist"]
    if path.read_text(encoding="utf-8") != render_lua(manifest):
        return [
            f"{path} is out of date with the canonical dataset "
            f"(expected manifest {manifest.manifest_hash})"
        ]
    return []
