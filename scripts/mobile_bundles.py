"""Which client builds the mobile app bundles, and the offline mirror that keeps them exact.

The app packages the canonical data of *every* bundled build side by side as Compose
Multiplatform resources (``files/<kind>/forever/<build>/``): new builds are created on the
product dataset, and every saved build keeps opening on the build it was created on - so
a build that saved builds may reference is never dropped by a sync (M5.DATA, see
docs/mobile-architecture.md). The roles (product vs. reference) are an app decision
made in Kotlin (``BundledDatasets.kt``); this module only knows *which* builds are bundled.

Used by ``sync_mobile_datasets.py``, ``sync_mobile_visuals.py`` and
``sync_mobile_tooltips.py``. Standard library only and no network: CI runs the checks with
a bare ``python3``.

Rules, for each kind (dataset / visuals / tooltips):

* every bundled build must have its canonical directory with the kind's required files;
* the bundle of a bundled build is a byte-identical mirror of its canonical directory -
  nothing missing, nothing extra, nothing different (``--check``);
* a sync copies changed files and removes stale files *inside* a bundled build, but never
  removes a whole build directory: a bundled directory of a build that is not in
  ``BUNDLED_BUILDS`` is reported (and fails the check and the sync) - retiring a build is a
  deliberate change, not a side effect of syncing another one;
* a sync is all or nothing: its preflight (unlisted bundled builds, missing required
  canonical files) runs before the first file is written or removed, and a failed
  preflight leaves the bundle exactly as it was.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

GAME = "forever"

# Every client build the app bundles (ascending). 1.60.1.70009 is the verified reference
# baseline (allocation evidence, M2 conformance, every build saved before M5.DATA);
# 1.60.1.70245 (M5.DATA) and 1.60.1.70338 (M5.DATA.2) were product datasets and stay for
# the builds saved on them; 1.60.1.70374 is the product dataset new builds are created on
# (M5.DATA.3).
BUNDLED_BUILDS: tuple[str, ...] = ("1.60.1.70009", "1.60.1.70245", "1.60.1.70338", "1.60.1.70374")

CLASS_FILES: tuple[str, ...] = (
    "warrior.json",
    "paladin.json",
    "hunter.json",
    "rogue.json",
    "priest.json",
    "shaman.json",
    "mage.json",
    "warlock.json",
    "druid.json",
)

REPO_ROOT = Path(__file__).resolve().parent.parent
RESOURCES = REPO_ROOT / "mobile" / "composeApp" / "src" / "commonMain" / "composeResources"
# Where the app declares its product dataset (the bundle New Build creates drafts on).
BUNDLED_DATASETS_KT = (
    REPO_ROOT
    / "mobile"
    / "composeApp"
    / "src"
    / "commonMain"
    / "kotlin"
    / "io"
    / "github"
    / "alexeykravtsov95"
    / "talentforge"
    / "ui"
    / "library"
    / "BundledDatasets.kt"
)
_PRODUCT_BUNDLE_RE = re.compile(
    r"\bval PRODUCT_BUNDLE: DatasetBundleId = DatasetBundleId\(\s*"
    r'game = GameId\("(?P<game>[a-z]+)"\),\s*'
    r'clientBuild = "(?P<build>\d+\.\d+\.\d+\.\d+)",\s*\)'
)


@dataclass(frozen=True)
class BundleKind:
    """One kind of bundled data: where it lives canonically and in the app."""

    name: str
    script: str
    canonical_root: Path
    bundled_root: Path
    # Files every canonical build directory must contain before anything is mirrored.
    required_files: tuple[str, ...]


DATASET = BundleKind(
    name="dataset",
    script="scripts/sync_mobile_datasets.py",
    canonical_root=REPO_ROOT / "data" / "normalized" / GAME,
    bundled_root=RESOURCES / "files" / "dataset" / GAME,
    required_files=("manifest.json", *CLASS_FILES),
)
VISUALS = BundleKind(
    name="visuals",
    script="scripts/sync_mobile_visuals.py",
    canonical_root=REPO_ROOT / "data" / "visuals" / GAME,
    bundled_root=RESOURCES / "files" / "visuals" / GAME,
    required_files=("icons.json", *CLASS_FILES),
)
TOOLTIPS = BundleKind(
    name="tooltips",
    script="scripts/sync_mobile_tooltips.py",
    canonical_root=REPO_ROOT / "data" / "tooltips" / GAME,
    bundled_root=RESOURCES / "files" / "tooltips" / GAME,
    required_files=CLASS_FILES,
)


def product_build(source: Path = BUNDLED_DATASETS_KT) -> str:
    """The client build of ``PRODUCT_BUNDLE`` as the app declares it in Kotlin.

    The app owns the role decision; this reads it - strictly - so that offline tooling
    (the product freshness check, the bundle tests) compares against the very build the
    app creates new builds on. Anything but exactly one declaration of the expected shape
    for this game is an error, never a guess.
    """
    matches = list(_PRODUCT_BUNDLE_RE.finditer(source.read_text(encoding="utf-8")))
    if len(matches) != 1:
        raise ValueError(
            f"{source}: expected exactly one PRODUCT_BUNDLE declaration "
            f'(DatasetBundleId(game = GameId("{GAME}"), clientBuild = "a.b.c.d")), '
            f"found {len(matches)}"
        )
    match = matches[0]
    if match["game"] != GAME:
        raise ValueError(f"{source}: PRODUCT_BUNDLE is for game {match['game']!r}, not {GAME!r}")
    return match["build"]


def relative_files(directory: Path) -> set[str]:
    if not directory.is_dir():
        return set()
    # Dotfiles (Finder's .DS_Store, editor droppings) are OS noise, never bundle content.
    return {
        p.relative_to(directory).as_posix()
        for p in directory.rglob("*")
        if p.is_file() and not any(part.startswith(".") for part in p.relative_to(directory).parts)
    }


def directory_drift(canonical_dir: Path, bundled_dir: Path) -> list[str]:
    """Every way one bundled build differs from its canonical directory (empty = in sync)."""
    canonical = relative_files(canonical_dir)
    bundled = relative_files(bundled_dir)
    problems = [f"{name}: missing from bundle" for name in sorted(canonical - bundled)]
    problems += [f"{name}: in bundle but not canonical" for name in sorted(bundled - canonical)]
    problems += [
        f"{name}: bundle differs from canonical"
        for name in sorted(canonical & bundled)
        if (canonical_dir / name).read_bytes() != (bundled_dir / name).read_bytes()
    ]
    return problems


def unlisted_entries(kind: BundleKind, builds: Sequence[str]) -> list[str]:
    """Bundled entries of ``kind`` that do not belong to a bundled build."""
    if not kind.bundled_root.is_dir():
        return []
    return sorted(
        p.name
        for p in kind.bundled_root.iterdir()
        if not p.name.startswith(".") and not (p.is_dir() and p.name in builds)
    )


def missing_canonical(kind: BundleKind, builds: Sequence[str]) -> list[str]:
    """Required canonical files of the bundled builds that do not exist."""
    return [
        f"{build}/{name}"
        for build in builds
        for name in kind.required_files
        if not (kind.canonical_root / build / name).is_file()
    ]


def drift(kind: BundleKind, builds: Sequence[str] = BUNDLED_BUILDS) -> list[str]:
    """Every way the bundle of ``kind`` differs from the canonical data of ``builds``."""
    problems = [
        f"{build}/{problem}"
        for build in builds
        for problem in directory_drift(kind.canonical_root / build, kind.bundled_root / build)
    ]
    problems += [
        f"{name}: in bundle but not a bundled build (BUNDLED_BUILDS in scripts/mobile_bundles.py)"
        for name in unlisted_entries(kind, builds)
    ]
    return problems


def _file_count(kind: BundleKind, builds: Sequence[str]) -> int:
    return sum(len(relative_files(kind.canonical_root / build)) for build in builds)


def check(kind: BundleKind, builds: Sequence[str] = BUNDLED_BUILDS) -> int:
    problems = drift(kind, builds)
    if problems:
        print(f"mobile {kind.name} bundle is out of sync with canonical data:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(f"run: python {kind.script}", file=sys.stderr)
        return 1
    print(
        f"bundle in sync: {_file_count(kind, builds)} files of {len(builds)} builds "
        f"({', '.join(builds)}) match {kind.canonical_root}"
    )
    return 0


def preflight(kind: BundleKind, builds: Sequence[str] = BUNDLED_BUILDS) -> list[str]:
    """Every reason a sync must not start: checked before any file is written or removed.

    * a required canonical file of a bundled build is missing - a sync would mirror an
      incomplete build;
    * the bundle holds a build directory (or stray entry) that is not a bundled build -
      saved builds may reference it, and a sync must neither delete it nor run next to it.
    """
    problems = [f"{name}: canonical file missing" for name in missing_canonical(kind, builds)]
    problems += [
        f"{name}: in bundle but not a bundled build (BUNDLED_BUILDS in scripts/mobile_bundles.py)"
        for name in unlisted_entries(kind, builds)
    ]
    return problems


def sync(kind: BundleKind, builds: Sequence[str] = BUNDLED_BUILDS) -> int:
    """Mirror the canonical data of ``builds`` into the bundle - all or nothing.

    The preflight runs first; when it fails, nothing in the bundle is changed.
    """
    problems = preflight(kind, builds)
    if problems:
        print(
            f"refusing to sync the mobile {kind.name} bundle (nothing was changed):",
            file=sys.stderr,
        )
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "a bundled build that is not listed may be referenced by saved builds: add it to "
            "BUNDLED_BUILDS or retire it deliberately",
            file=sys.stderr,
        )
        return 1
    copied = removed = 0
    for build in builds:
        canonical_dir, bundled_dir = kind.canonical_root / build, kind.bundled_root / build
        canonical = relative_files(canonical_dir)
        for name in sorted(canonical):
            data = (canonical_dir / name).read_bytes()
            target = bundled_dir / name
            if target.is_file() and target.read_bytes() == data:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            copied += 1
        for name in sorted(relative_files(bundled_dir) - canonical):
            (bundled_dir / name).unlink()
            removed += 1
    print(
        f"synced {_file_count(kind, builds)} files of {len(builds)} builds into "
        f"{kind.bundled_root} ({copied} copied, {removed} removed)"
    )
    return 0


def run(kind: BundleKind, argv: list[str] | None = None, doc: str | None = None) -> int:
    parser = argparse.ArgumentParser(description=doc)
    parser.add_argument(
        "--check",
        action="store_true",
        help=f"verify the {kind.name} bundle matches the canonical data instead of copying",
    )
    args = parser.parse_args(argv)
    if not args.check:
        return sync(kind)  # all-or-nothing: its preflight runs before any file change
    missing = missing_canonical(kind, BUNDLED_BUILDS)
    if missing:
        print(f"canonical {kind.name} files are missing in {kind.canonical_root}:", file=sys.stderr)
        for name in missing:
            print(f"  - {name}", file=sys.stderr)
        return 1
    return check(kind)
