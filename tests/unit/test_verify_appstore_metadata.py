"""The App Store metadata validator (M7.IOS.RC.1).

``scripts/verify_appstore_metadata.py`` checks the submission pack's editorial rules.
A PASS does not establish rights or authorize submission to App Store Connect.
The committed pack must pass; each editorial rule is broken on a copy and must fail closed.
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import sys
from pathlib import Path
from types import ModuleType

import pytest

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "docs/release/apple-appstore/submission/metadata"


def load_validator() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "verify_appstore_metadata", ROOT / "scripts" / "verify_appstore_metadata.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


v = load_validator()


@pytest.fixture
def pack(tmp_path: Path) -> Path:
    copy = tmp_path / "metadata"
    shutil.copytree(PACK, copy)
    return copy


def failed(metadata: Path) -> dict[str, str]:
    evaluation, _ = v.evaluate(metadata)
    return {check.id: check.detail for check in evaluation.checks if not check.passed}


def write(pack: Path, relative: str, text: str) -> None:
    (pack / relative).write_text(text, encoding="utf-8")


def test_the_committed_pack_passes() -> None:
    evaluation, report = v.evaluate(PACK)
    assert {c.id: c.detail for c in evaluation.checks if not c.passed} == {}
    assert report["result"] == "PASS"
    assert set(report["fields"]) >= {
        f"{locale}/{name}.txt" for locale in v.LOCALES for name in v.LOCALE_FILES
    }


@pytest.mark.parametrize("field", ["description", "promotional_text", "keywords"])
@pytest.mark.parametrize(
    ("locale", "claim"),
    [
        ("en-US", "Search builds"),
        ("en-US", "Favorites"),
        ("en-US", "Sort builds"),
        ("en-US", "Class filters"),
        ("en-US", "Class grouping"),
        ("ru", "Поиск билдов"),
        ("ru", "Избранное"),
        ("ru", "Сортируйте билды"),
        ("ru", "Фильтры классов"),
        ("ru", "Группировка по классу"),
    ],
)
def test_library_claims_are_no_longer_rejected_by_build_four_history(
    pack: Path, field: str, locale: str, claim: str
) -> None:
    path = pack / locale / f"{field}.txt"
    path.write_text(path.read_text().rstrip("\n") + "\n" + claim + "\n")
    evaluation, _ = v.evaluate(pack)
    assert "reviewed-build-claims" not in {c.id for c in evaluation.checks}
    # Length/keyword rules still apply; feature acceptance is a separate release gate.
    assert not any(c.id.startswith("branding:") and not c.passed for c in evaluation.checks)


@pytest.mark.parametrize("claim", ["search", "favorites", "sort", "filters", "grouping"])
def test_review_notes_allow_candidate_library_features(pack: Path, claim: str) -> None:
    path = pack / "review_information/notes.txt"
    path.write_text(path.read_text().rstrip("\n") + "\n" + claim + "\n")
    assert failed(pack) == {}


def test_no_whats_new_file_for_the_first_version() -> None:
    assert not list(PACK.rglob("release_notes.txt"))


@pytest.mark.parametrize(
    ("relative", "limit"),
    [
        ("en-US/name.txt", 30),
        ("ru/subtitle.txt", 30),
        ("en-US/promotional_text.txt", 170),
        ("ru/description.txt", 4000),
    ],
)
def test_character_limits(pack: Path, relative: str, limit: int) -> None:
    write(pack, relative, "x" * limit + "y\n")
    check = f"limit:{relative.removesuffix('.txt')}"
    assert check in failed(pack)


def test_keywords_are_limited_in_bytes_not_characters(pack: Path) -> None:
    # 58 characters, but Cyrillic letters take two bytes each: 109 bytes.
    write(pack, "ru/keywords.txt", "планировщик,калькулятор,дерево,маг,жрец,друид,охотник,воин\n")
    assert "bytes (limit 100)" in failed(pack)["keywords:ru"]


@pytest.mark.parametrize(
    ("keywords", "problem"),
    [
        ("planner, tree", "blanks around a comma"),
        ("planner,,tree", "empty keyword"),
        ("planner,tree,Planner", "repeated"),
        ("calculator,tree,build", "repeats the name or subtitle"),
        ("planner,talentforge", "repeats the name or subtitle"),
        ("planner,ui,tree", "two characters or fewer"),
    ],
)
def test_keyword_rules(pack: Path, keywords: str, problem: str) -> None:
    write(pack, "en-US/keywords.txt", keywords + "\n")
    assert problem in failed(pack)["keywords:en-US"]


@pytest.mark.parametrize(
    ("text", "problem"),
    [
        ("TalentForge", "exactly one newline"),
        ("TalentForge\n\n", "exactly one newline"),
        (" TalentForge\n", "leading or trailing blanks"),
        ("Talent\tForge\n", "carriage return or tab"),
        ("{{NAME}}\n", "placeholder"),
        ("\n", "empty"),
    ],
)
def test_file_format(pack: Path, text: str, problem: str) -> None:
    write(pack, "en-US/name.txt", text)
    assert problem in failed(pack)["format:en-US/name.txt"]


def test_a_missing_field_fails(pack: Path) -> None:
    (pack / "ru/privacy_url.txt").unlink()
    problems = failed(pack)
    assert problems["format:ru/privacy_url.txt"] == "missing"
    assert "urls:ru" in problems


@pytest.mark.parametrize(
    "name", ["ТалантФорж", "TalentForge: WoW Forever", "TalentForge: WoW Forever!"]
)
def test_the_name_is_the_app_name_in_every_locale(pack: Path, name: str) -> None:
    write(pack, "ru/name.txt", name + "\n")
    assert "name:ru" in failed(pack)


@pytest.mark.parametrize("locale", v.LOCALES)
def test_rejected_subtitles_cannot_return(pack: Path, locale: str) -> None:
    write(pack, f"{locale}/subtitle.txt", "Talent builds for WoW: Forever\n")
    assert f"positioning:{locale}/subtitle" in failed(pack)


@pytest.mark.parametrize("locale", v.LOCALES)
def test_game_title_is_optional_in_description(pack: Path, locale: str) -> None:
    path = pack / locale / "description.txt"
    path.write_text(path.read_text().replace("World of Warcraft: Forever", "Forever 1.60 data"))
    assert failed(pack) == {}


@pytest.mark.parametrize(
    ("locale", "phrase"), [("en-US", "nine classes"), ("ru", "три дерева талантов")]
)
def test_description_preserves_specialized_scope(pack: Path, locale: str, phrase: str) -> None:
    path = pack / locale / "description.txt"
    path.write_text(path.read_text().replace(phrase, "any game"))
    assert f"positioning:{locale}/description" in failed(pack)


@pytest.mark.parametrize("locale", v.LOCALES)
@pytest.mark.parametrize("field", ["keywords", "promotional_text"])
@pytest.mark.parametrize("brand", ["WoW", "World of Warcraft", "Blizzard", "Forever"])
def test_game_branding_cannot_return_to_discovery_fields(
    pack: Path, locale: str, field: str, brand: str
) -> None:
    write(pack, f"{locale}/{field}.txt", brand + "\n")
    assert f"branding:{locale}" in failed(pack)


def test_the_unofficial_status_and_disclaimer_must_stay(pack: Path) -> None:
    description = (pack / "en-US/description.txt").read_text(encoding="utf-8")
    write(pack, "en-US/description.txt", description.replace("unofficial", "official"))
    assert "unofficial" in failed(pack)["positioning:en-US/description"]
    description = (pack / "ru/description.txt").read_text(encoding="utf-8")
    unaffiliated = description.replace("не связан", "связан")
    write(pack, "ru/description.txt", unaffiliated)
    assert "Blizzard" in failed(pack)["positioning:ru/description"]


def test_an_ota_off_claim_fails(pack: Path) -> None:
    description = (pack / "en-US/description.txt").read_text(encoding="utf-8").rstrip("\n")
    write(pack, "en-US/description.txt", description + "\nThis version works entirely offline.\n")
    assert "en-US/description.txt" in failed(pack)["ota-on-claims"]


def test_the_review_notes_must_explain_ota(pack: Path) -> None:
    notes = (pack / "review_information/notes.txt").read_text(encoding="utf-8")
    write(pack, "review_information/notes.txt", notes.replace("https://ota.mythweld.com", "us"))
    assert "https://ota.mythweld.com" in failed(pack)["review-notes"]


@pytest.mark.parametrize(
    "phrase",
    [
        "not a game client",
        "rename",
        "duplicate",
        "delete",
        "automatically saved",
        "preview",
        "offline",
        "English or Russian",
        "rights remain unresolved",
    ],
)
def test_review_notes_preserve_product_and_rights_explanation(pack: Path, phrase: str) -> None:
    path = pack / "review_information/notes.txt"
    path.write_text(path.read_text().replace(phrase, "removed"))
    assert phrase in failed(pack)["review-notes"]


def test_the_review_notes_are_limited_in_bytes(pack: Path) -> None:
    notes = (pack / "review_information/notes.txt").read_text(encoding="utf-8").rstrip("\n")
    write(pack, "review_information/notes.txt", notes + "\n" + "й" * 1000 + "\n")
    assert "bytes (limit 4000)" in failed(pack)["limit:review_information/notes"]


def test_other_platforms_are_not_named(pack: Path) -> None:
    write(pack, "en-US/promotional_text.txt", "Also on Android.\n")
    assert "en-US/promotional_text.txt" in failed(pack)["other-platforms"]


@pytest.mark.parametrize(
    ("relative", "url"),
    [
        ("en-US/privacy_url.txt", "https://legal.mythweld.com/privacy-en.html"),
        ("ru/privacy_url.txt", "http://legal.mythweld.com/privacy-ios.html"),
        ("en-US/support_url.txt", "https://example.com/"),
    ],
)
def test_the_urls_are_the_ios_pages(pack: Path, relative: str, url: str) -> None:
    write(pack, relative, url + "\n")
    assert f"urls:{relative.split('/')[0]}" in failed(pack)


@pytest.mark.parametrize(("primary", "secondary"), [("Games", "Utilities"), ("Reference", "Ref")])
def test_categories(pack: Path, primary: str, secondary: str) -> None:
    write(pack, "primary_category.txt", primary + "\n")
    write(pack, "secondary_category.txt", secondary + "\n")
    assert "categories" in failed(pack)


def test_cli_writes_a_report_and_exits_by_result(pack: Path, tmp_path: Path) -> None:
    report = tmp_path / "report.json"
    assert v.main(["--metadata", str(pack), "--report", str(report)]) == v.EXIT_PASS
    assert json.loads(report.read_text(encoding="utf-8"))["result"] == "PASS"
    write(pack, "en-US/name.txt", "x" * 31 + "\n")
    assert v.main(["--metadata", str(pack)]) == v.EXIT_FAIL
    assert v.main(["--metadata", str(tmp_path / "missing")]) == v.EXIT_ERROR
