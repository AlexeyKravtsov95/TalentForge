"""talent-localization.v1: strict contract, byte-identical regeneration, drift detection,
write boundaries, and independence from allocation and saved builds."""

from __future__ import annotations

import ast
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from talent_data.cli.main import json_schemas, main
from talent_data.localization.coverage import render_markdown
from talent_data.localization.generate import LocalizationInputs
from talent_data.localization.pipeline import (
    BuildLocalization,
    check_canonical,
    check_localization_document,
    publish,
    read_coverage_report,
)
from talent_data.models.localization import TalentLocalizationDocument, expected_content_hash
from talent_data.models.talents import TalentDataDocument
from talent_data.models.tooltips import TalentTooltipsDocument
from tests.helpers import NEW_BUILD, PREVIOUS_PRODUCT_BUILD, PRODUCT_BUILD
from tests.unit.localization.conftest import (
    GAME,
    LOCALE,
    MARKDOWN,
    OVERLAYS,
    REPORT,
    ROOT,
    with_russian,
)
from tests.unit.localization.test_generate import regenerate

WARRIOR_FILE = OVERLAYS / "warrior.json"


def warrior() -> dict[str, Any]:
    raw: dict[str, Any] = json.loads(WARRIOR_FILE.read_text(encoding="utf-8"))
    return raw


def rehash(raw: dict[str, Any]) -> TalentLocalizationDocument:
    document = TalentLocalizationDocument.model_validate(raw)
    return document.model_copy(update={"content_hash": expected_content_hash(document)})


# --------------------------------------------------------------------------- contract


def test_the_committed_pilot_validates_hashes_and_round_trips() -> None:
    assert sorted(p.name for p in OVERLAYS.iterdir()) == ["warrior.json"]
    document = TalentLocalizationDocument.model_validate_json(WARRIOR_FILE.read_text("utf-8"))
    assert document.content_hash == expected_content_hash(document)
    assert document.to_json() == WARRIOR_FILE.read_text(encoding="utf-8")


def _rendered(d: dict[str, Any]) -> dict[str, Any]:
    entry: dict[str, Any] = next(
        e for e in d["entries"] if e["description"]["status"] == "rendered"
    )
    description: dict[str, Any] = entry["description"]
    return description


@pytest.mark.parametrize(
    "mutate",
    [
        lambda d: d.update(extra=1),
        lambda d: d["entries"][0].update(extra=1),
        lambda d: d["entries"][0]["name"].update(extra=1),
        lambda d: d.update(schemaVersion=2),
        lambda d: d.update(locale="ruru"),
        lambda d: d.update(locale="enUS"),
        lambda d: d.update(locale="deDE"),
        lambda d: d.pop("tooltipsContentHash"),
        lambda d: d.pop("talentDataContentHash"),
        lambda d: d["entries"][0].pop("nodeId"),
        lambda d: d["entries"][0]["name"].pop("text"),  # nullable but required
        lambda d: d["entries"][0].update(entryId=str(d["entries"][0]["entryId"])),
        lambda d: d["entries"][0].update(maxRank=3.0),
        lambda d: d["entries"][0]["name"].update(status="machineTranslated"),
        lambda d: d["entries"][0]["name"].update(source="Spell.NameSubtext_lang"),
        lambda d: d["entries"][0]["description"].update(status="approximate"),
        # a translated name has Cyrillic text; a fallback name has none at all
        lambda d: d["entries"][0]["name"].update(status="translated", text=None),
        lambda d: d["entries"][0]["name"].update(status="translated", text="Heroic Strike"),
        lambda d: d["entries"][0]["name"].update(status="untranslated", text="Удар"),
        # only rendered descriptions have ranks, and then exactly 1..maxRank
        lambda d: _rendered(d).update(status="renderGap"),
        lambda d: _rendered(d).update(ranks=[]),
        lambda d: _rendered(d)["ranks"].pop(),
        lambda d: _rendered(d)["ranks"][0]["segments"][0].update(kind="gap"),
        lambda d: _rendered(d)["ranks"][0]["segments"][0].update(text="на $s1 ед."),
        lambda d: _rendered(d)["ranks"][0]["segments"][0].update(text=""),
    ],
)
def test_strict_parsing(mutate) -> None:
    document = warrior()
    mutate(document)
    with pytest.raises(ValidationError):
        TalentLocalizationDocument.model_validate(document)


def test_the_committed_schema_is_generated_from_the_model() -> None:
    name = "talent-localization.v1.schema.json"
    assert (ROOT / "docs" / "schema" / name).read_text(encoding="utf-8") == json_schemas()[name]


# --------------------------------------------------------------------------- regeneration


def test_regenerating_from_the_build_rows_reproduces_the_committed_files(
    build: BuildLocalization,
) -> None:
    assert build.overlays["WARRIOR"].to_json() == WARRIOR_FILE.read_text(encoding="utf-8")
    report = build.report(frozenset({"WARRIOR"}))
    assert report.to_json() == REPORT.read_text(encoding="utf-8")
    assert render_markdown(report) == MARKDOWN.read_text(encoding="utf-8")


def test_product_build_coverage() -> None:
    report = read_coverage_report(REPORT)
    totals = report.totals
    assert (totals.entries, totals.ranks) == (469, 1329)
    names = totals.names
    assert (names.translated, names.untranslated, names.missing) == (459, 10, 0)
    assert totals.descriptions.model_dump() == {
        "missing": 3,
        "untranslated": 41,
        "source_not_resolved": 33,
        "plural_rule_unverified": 12,
        "structure_mismatch": 0,
        "render_gap": 0,
        "decimal_format_unverified": 25,
        "rendered": 355,
    }
    assert totals.rendered_ranks == 1058
    assert [row.class_token for row in report.classes if row.published] == ["WARRIOR"]
    # every fallback to English is listed with its reason
    assert len(report.name_exceptions) == names.untranslated + names.missing
    assert len(report.description_exceptions) == totals.entries - totals.descriptions.rendered


def test_source_data_drift_changes_the_overlay(
    build: BuildLocalization,
    inputs: LocalizationInputs,
    documents: dict[str, TalentDataDocument],
    tooltips: dict[str, TalentTooltipsDocument],
) -> None:
    """A changed client string regenerates a different overlay: the committed file no longer
    matches, so the regeneration test fails until the overlay is re-published."""
    changed = with_russian(inputs, names={12282: "Улучшенный героический удар"})
    drifted = regenerate("WARRIOR", changed, documents, tooltips)
    assert drifted.content_hash != build.overlays["WARRIOR"].content_hash
    assert drifted.to_json() != WARRIOR_FILE.read_text(encoding="utf-8")


# --------------------------------------------------------------------------- offline check


def test_the_committed_localization_is_consistent(at_repo_root) -> None:
    assert check_canonical(GAME, PRODUCT_BUILD, LOCALE) == []


@pytest.mark.parametrize("retained", [NEW_BUILD, PREVIOUS_PRODUCT_BUILD])
def test_retained_builds_have_no_overlay_and_say_so(at_repo_root, retained) -> None:
    """3a localizes only the product build; other bundled builds stay readable, unchanged."""
    problems = check_canonical(GAME, retained, LOCALE)
    assert len(problems) == 1
    assert problems[0].startswith(f"no ruRU localization of {retained}")


@pytest.fixture
def pair(
    documents: dict[str, TalentDataDocument], tooltips: dict[str, TalentTooltipsDocument]
) -> tuple[TalentDataDocument, TalentTooltipsDocument]:
    return documents["WARRIOR"], tooltips["WARRIOR"]


def test_a_stale_dataset_revision_is_detected(pair) -> None:
    overlay = rehash({**warrior(), "talentDataContentHash": "sha256:" + "0" * 64})
    problems = check_localization_document(overlay, *pair)
    assert any("belongs to talent-data" in p for p in problems)


def test_a_stale_tooltips_revision_is_detected(pair) -> None:
    overlay = rehash({**warrior(), "tooltipsContentHash": "sha256:" + "1" * 64})
    problems = check_localization_document(overlay, *pair)
    assert any("checked against tooltips" in p for p in problems)


@pytest.mark.parametrize(
    ("field", "value"),
    [("clientBuild", "1.60.1.70245"), ("classToken", "MAGE"), ("game", "retail")],
)
def test_a_foreign_overlay_is_detected(pair, field: str, value: str) -> None:
    overlay = rehash({**warrior(), field: value})
    assert check_localization_document(overlay, *pair) != []


def test_tampered_content_without_a_new_hash_is_detected(pair) -> None:
    raw = warrior()
    raw["entries"][0]["name"]["text"] = "Подделка"
    overlay = TalentLocalizationDocument.model_validate(raw)
    assert any("contentHash" in p for p in check_localization_document(overlay, *pair))


@pytest.mark.parametrize(
    ("mutate", "expected"),
    [
        (lambda d: d["entries"].pop(0), "without an overlay entry"),
        (lambda d: d["entries"].append({**d["entries"][0], "entryId": 999999}), "unknown talent"),
        (lambda d: d["entries"].append(d["entries"][0]), "duplicate entryId"),
        (lambda d: d["entries"].reverse(), "not ordered"),
        (lambda d: d["entries"][0].update(nodeId=d["entries"][0]["nodeId"] + 1), "node/definition"),
        (lambda d: d["entries"][0].update(spellId=1), "node/definition"),
    ],
)
def test_entry_identity_drift_is_detected(pair, mutate, expected: str) -> None:
    raw = warrior()
    mutate(raw)
    problems = check_localization_document(rehash(raw), *pair)
    assert any(expected in p for p in problems), problems


def test_a_changed_number_in_a_rendered_rank_is_detected(pair) -> None:
    raw = warrior()
    entry = next(e for e in raw["entries"] if e["entryId"] == 130688)
    value = next(s for s in entry["description"]["ranks"][0]["segments"] if s["kind"] == "value")
    value["text"] = "7"
    problems = check_localization_document(rehash(raw), *pair)
    assert any("shows numbers ['7'], English shows ['1']" in p for p in problems)


def _copy_repo(tmp: Path) -> Path:
    for relative in (
        f"data/normalized/forever/{PRODUCT_BUILD}",
        f"data/tooltips/forever/{PRODUCT_BUILD}",
        f"data/localization/forever/{PRODUCT_BUILD}",
    ):
        shutil.copytree(ROOT / relative, tmp / relative)
    for path in (REPORT, MARKDOWN):
        target = tmp / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(path, target)
    return tmp


def test_report_and_files_must_agree(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root = _copy_repo(tmp_path)
    monkeypatch.setenv("TALENT_DATA_ROOT", str(root))
    assert check_canonical(GAME, PRODUCT_BUILD, LOCALE) == []
    overlays = root / OVERLAYS.relative_to(ROOT)
    (overlays / "notes.json").write_text("{}", encoding="utf-8")
    problems = check_canonical(GAME, PRODUCT_BUILD, LOCALE)
    assert any("notes.json is not part" in p for p in problems)
    (overlays / "notes.json").unlink()
    markdown = root / MARKDOWN.relative_to(ROOT)
    markdown.write_text(markdown.read_text("utf-8") + "edited\n", encoding="utf-8")
    problems = check_canonical(GAME, PRODUCT_BUILD, LOCALE)
    assert any("does not match the coverage report" in p for p in problems)


# --------------------------------------------------------------------------- boundaries


def _digests(root: Path, *relatives: str) -> dict[str, str]:
    return {
        str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
        for relative in relatives
        for p in sorted((root / relative).rglob("*"))
        if p.is_file()
    }


def test_publishing_writes_only_localization_outputs(
    build: BuildLocalization, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = _copy_repo(tmp_path)
    shutil.rmtree(root / "data" / "localization")
    (root / REPORT.relative_to(ROOT)).unlink()
    (root / MARKDOWN.relative_to(ROOT)).unlink()
    canonical = ("data/normalized", "data/tooltips")
    before = _digests(root, *canonical)
    existing = {p for p in root.rglob("*") if p.is_file()}
    monkeypatch.setenv("TALENT_DATA_ROOT", str(root))
    publish(build, frozenset({"WARRIOR"}))
    written = {p for p in root.rglob("*") if p.is_file()} - existing
    assert {str(p.relative_to(root)) for p in written} == {
        f"data/localization/forever/{PRODUCT_BUILD}/ruRU/warrior.json",
        str(REPORT.relative_to(ROOT)),
        str(MARKDOWN.relative_to(ROOT)),
    }
    assert _digests(root, *canonical) == before


def _imports(package: Path) -> set[str]:
    names: set[str] = set()
    for path in package.rglob("*.py"):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.ImportFrom) and node.module:
                names.add(node.module)
            elif isinstance(node, ast.Import):
                names.update(alias.name for alias in node.names)
    return names


@pytest.mark.parametrize("package", ["allocation", "normalization", "validation", "diff"])
def test_allocation_and_dataset_code_never_depend_on_localization(package: str) -> None:
    imports = _imports(ROOT / "src" / "talent_data" / package)
    assert not {name for name in imports if "localization" in name}


def test_saved_build_and_allocation_contracts_are_untouched() -> None:
    """The overlay adds a contract; it changes none of those saved builds and allocation use."""
    schemas = json_schemas()
    for name in ("allocation-build.v1.schema.json", "talent-data.v1.schema.json"):
        assert '"locale"' not in schemas[name]  # no locale property anywhere
        assert (ROOT / "docs" / "schema" / name).read_text(encoding="utf-8") == schemas[name]


# --------------------------------------------------------------------------- CLI


def test_cli_check_passes_on_the_committed_files(at_repo_root, capsys) -> None:
    argv = ["localization", "check", "--build", str(PRODUCT_BUILD), "--locale", "ruRU"]
    assert main(argv) == 0
    assert "consistent" in capsys.readouterr().out


def test_cli_refuses_an_unsupported_locale(at_repo_root, capsys) -> None:
    argv = ["localization", "check", "--build", str(PRODUCT_BUILD), "--locale", "ruru"]
    assert main(argv) == 1
    assert "not supported" in capsys.readouterr().err
