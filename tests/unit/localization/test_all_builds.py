"""M5.REL.3b.1: the ruRU overlays of every bundled build, all nine classes each.

The pilot (M5.REL.3a) committed Warrior only, for 70338 and 70374. This milestone
publishes 36 overlays: every class of every bundled build (70009, 70245, 70338, 70374),
each generated from its own build's enUS/ruRU exports - never copied across builds - and
each leaving the canonical English data and the previously committed pilots untouched.
Since M5.DATA.4 the product build 70450 is a fifth bundled build with its own nine overlays.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from talent_data.cli.main import main
from talent_data.games import get_game
from talent_data.localization.coverage import render_markdown
from talent_data.localization.pipeline import (
    check_canonical,
    localize_build,
    read_coverage_report,
)
from talent_data.sources.local import LocalCsvDb2Source
from talent_data.versions import ClientVersion
from tests.conftest import ALL_CLASSES
from tests.helpers import (
    BUILD_70245,
    BUILD_70338,
    NEW_BUILD,
    PREVIOUS_PRODUCT_BUILD,
    PRODUCT_BUILD,
)

ROOT = Path(__file__).resolve().parents[3]
GAME = get_game("forever")
LOCALE = "ruRU"
BUNDLED_BUILDS = (NEW_BUILD, BUILD_70245, BUILD_70338, PREVIOUS_PRODUCT_BUILD, PRODUCT_BUILD)
# 70338, 70374 and 70450 regenerate in their own suites (tests/unit/localization,
# test_build_70374, test_product_build); these two builds get fixtures with this milestone.
FIXTURED_BUILDS = (NEW_BUILD, BUILD_70245)

# (build, totals) of the committed coverage reports: names translated/untranslated/missing,
# descriptions rendered, rendered ranks / ranks. 70338 and 70374 share their ruRU exports
# (docs/talent-localization.md §11); 70009 and 70245 were measured in §9 step 2; 70450 by
# M5.DATA.4 (§14).
COVERAGE_TOTALS = {
    "1.60.1.70009": ((467, 2, 0), 386, (1143, 1333)),
    "1.60.1.70245": ((464, 6, 0), 365, (1097, 1333)),
    "1.60.1.70338": ((459, 10, 0), 355, (1058, 1329)),
    "1.60.1.70374": ((459, 10, 0), 355, (1058, 1329)),
    "1.60.1.70450": ((469, 0, 0), 383, (1130, 1328)),
}

# sha256 over the sorted (relative path, bytes) of the committed canonical English trees at
# the start of M5.REL.3b.1 (main after M5.DATA.3; 70450 as published by M5.DATA.4).
# Publishing overlays must not change a
# byte of the English data; a changed digest is a conscious dataset revision, never a side
# effect of localization.
ENGLISH_TREE_DIGESTS = {
    ("1.60.1.70009", "normalized"): (
        "sha256:2d12f752152836ebbc01fef9dfa7c632a2f9b6df9374b4c9031b2c361cf33629"
    ),
    ("1.60.1.70009", "tooltips"): (
        "sha256:8ebfbda478b85d8cd5a742c89e5bdd7421fe1cbf66b7120ce419a6b85ff77fe9"
    ),
    ("1.60.1.70245", "normalized"): (
        "sha256:83fbf79a54605648fcfabd08200d88475a2ce827e09c2ee4c66ea6a35bf13d01"
    ),
    ("1.60.1.70245", "tooltips"): (
        "sha256:1830c1628eb041a7da39dd6e82fd0d9b0c57400b65347ea536dd0465e57c1e34"
    ),
    ("1.60.1.70338", "normalized"): (
        "sha256:0a6fca97ee0ad8f88c9bfc3e7f1cc78de79482eeac3b6154d54b7996fd715849"
    ),
    ("1.60.1.70338", "tooltips"): (
        "sha256:5c3873bfff7fbb4b0d660499137bfc6daa9f9e8608c18b533441df205d087c2e"
    ),
    ("1.60.1.70374", "normalized"): (
        "sha256:f4c843835f8bb2f54edbacda3d47a933cf3bd720c9d2bd2425b65f1c795d01ba"
    ),
    ("1.60.1.70374", "tooltips"): (
        "sha256:b28912195f095d36a76f721b0e539189200d21573543242064660e2242416b3f"
    ),
    ("1.60.1.70450", "normalized"): (
        "sha256:67c6b73008ce9f448ed9423f941f4a362f833b68f649fa063a32a871bd58467d"
    ),
    ("1.60.1.70450", "tooltips"): (
        "sha256:6e1959a47c86ff5f22061d9995aaa9325b3e48ea8459fc7765f4735b41cdbc20"
    ),
}

# The Warrior pilots committed by M5.REL.3a (70338) and M5.DATA.3 (70374): publishing the
# other 35 overlays reproduces them byte for byte, because generation is deterministic.
PILOT_WARRIOR_DIGESTS = {
    "1.60.1.70338": "9fadbd21ec42085d863977cb213dab9afdd7054dc5c158060ce7c4177f5b9123",
    "1.60.1.70374": "7227b1553c4ab89243bf9f67483280a285f411d3546bdf2d731d2500f2c4af6d",
}


def tree_digest(root: Path) -> str:
    digest = hashlib.sha256()
    files = sorted(
        p.relative_to(root).as_posix()
        for p in root.rglob("*")
        if p.is_file() and not any(part.startswith(".") for part in p.relative_to(root).parts)
    )
    for name in files:
        digest.update(name.encode())
        digest.update(b"\0")
        digest.update((root / name).read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def overlay_path(build: ClientVersion, class_key: str) -> Path:
    return ROOT / "data" / "localization" / "forever" / str(build) / LOCALE / f"{class_key}.json"


# --------------------------------------------------------------------------- committed state


@pytest.mark.parametrize("build", BUNDLED_BUILDS)
def test_every_bundled_build_publishes_every_class(build, at_repo_root) -> None:
    """Nine overlays and nothing else per build; every one consistent with its build's
    dataset, English tooltips and coverage report."""
    overlays = ROOT / "data" / "localization" / "forever" / str(build) / LOCALE
    assert sorted(p.name for p in overlays.iterdir()) == sorted(
        f"{key}.json" for key in ALL_CLASSES
    )
    assert check_canonical(GAME, build, LOCALE) == []


@pytest.mark.parametrize("build", BUNDLED_BUILDS)
def test_cli_check_passes_for_every_bundled_build(build, at_repo_root, capsys) -> None:
    assert main(["localization", "check", "--build", str(build), "--locale", LOCALE]) == 0
    assert "consistent" in capsys.readouterr().out


@pytest.mark.parametrize("build", BUNDLED_BUILDS)
def test_the_coverage_report_publishes_every_class_with_the_measured_totals(build) -> None:
    report = read_coverage_report(
        ROOT / "artifacts" / "localization" / f"forever-{build}-{LOCALE}-coverage.json"
    )
    names, rendered, ranks = COVERAGE_TOTALS[str(build)]
    assert (
        report.totals.names.translated,
        report.totals.names.untranslated,
        report.totals.names.missing,
    ) == names
    assert report.totals.descriptions.rendered == rendered
    assert (report.totals.rendered_ranks, report.totals.ranks) == ranks
    assert [row.class_token for row in report.classes if row.published] == [
        key.upper() for key in ALL_CLASSES
    ]


def test_a_build_s_overlays_are_its_own() -> None:
    """Never a copy: every overlay carries its build's clientBuild and build- and
    locale-pinned provenance URLs, and the same class yields a different document on
    every build - even where two builds' ruRU exports are byte-identical (70338/70374)."""
    digests = {}
    for build in BUNDLED_BUILDS:
        for key in ALL_CLASSES:
            raw = json.loads(overlay_path(build, key).read_text(encoding="utf-8"))
            assert raw["clientBuild"] == str(build), (build, key)
            for table in raw["provenance"]["tables"]:
                assert f"build={build}" in table["url"], (build, key, table["table"])
        digests[build] = hashlib.sha256(overlay_path(build, "warrior").read_bytes()).hexdigest()
    assert len(set(digests.values())) == len(BUNDLED_BUILDS)


# --------------------------------------------------------------------------- regeneration


@pytest.mark.parametrize("build", FIXTURED_BUILDS)
def test_regenerating_from_the_build_rows_reproduces_the_committed_files(
    build, at_repo_root
) -> None:
    """As for the pilot builds: the overlays, report and Markdown of 70009 and 70245 come
    from their own committed fixture rows, offline."""
    fixtures = ROOT / "tests" / "fixtures" / "localization" / "forever" / str(build)
    tooltips = ROOT / "tests" / "fixtures" / "tooltips" / "forever" / str(build)
    result = localize_build(
        LocalCsvDb2Source(fixtures / "enUS", per_version=False),
        LocalCsvDb2Source(fixtures / LOCALE, per_version=False),
        LocalCsvDb2Source(tooltips, per_version=False),
        GAME,
        build,
        LOCALE,
        source_name="wago.tools",
    )
    for token, overlay in result.overlays.items():
        assert overlay.to_json() == overlay_path(build, token.lower()).read_text(
            encoding="utf-8"
        ), token
    report = result.report(frozenset(result.overlays))
    assert report.to_json() == (
        ROOT / "artifacts" / "localization" / f"forever-{build}-{LOCALE}-coverage.json"
    ).read_text(encoding="utf-8")
    assert render_markdown(report) == (
        ROOT / "docs" / "research" / f"forever-{build}-{LOCALE}-coverage.md"
    ).read_text(encoding="utf-8")


# --------------------------------------------------------------------------- nothing moved


def test_the_warrior_pilots_are_unchanged() -> None:
    for build, digest in PILOT_WARRIOR_DIGESTS.items():
        pilot = (
            ROOT / "data" / "localization" / "forever" / build / LOCALE / "warrior.json"
        ).read_bytes()
        assert hashlib.sha256(pilot).hexdigest() == digest, build


@pytest.mark.parametrize("kind", ["normalized", "tooltips"])
def test_canonical_english_data_is_unchanged(kind) -> None:
    """The English dataset and tooltip trees of every bundled build are byte-identical to
    the state this milestone started from; localization writes only its own outputs."""
    for build in BUNDLED_BUILDS:
        tree = ROOT / "data" / kind / "forever" / str(build)
        assert tree_digest(tree) == ENGLISH_TREE_DIGESTS[(str(build), kind)].removeprefix(
            "sha256:"
        ), (build, kind)
