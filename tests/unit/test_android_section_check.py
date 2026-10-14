"""The device section check's localized selector sets, offline (M5.REL.2b fix).

``scripts/android_section_check.py`` drives the real app on a real phone with
adb, so the one thing that can break silently is its selectors: after the
EN/RU localization a Russian device shows Russian copy, and a stale English
selector would make every gesture fail. These tests pin the English and the
Russian ``UiCopy`` sets and cross-check every field against the very resources
the app renders (``composeResources/values[-ru]/strings.xml``), so renamed copy
fails HERE instead of on a device. They also verify the parts a device run
depends on: the app's language rule (via ``language_of``), the
never-translated dataset talent name inside the + control's accessibility
label, and ``Hierarchy`` reading talents while filtering the control labels of
the matching language (``not_talents``).
"""

from __future__ import annotations

import importlib.util
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "mobile/composeApp/src/commonMain/composeResources"

# The UiCopy fields that are plain strings of one resource each:
# script field -> resource key in values/strings.xml and values-ru/strings.xml.
PLAIN_RESOURCE_KEYS = {
    "new_build": "common_new_build",
    "warrior": "class_name_warrior",
    "continue_editing": "home_continue_editing",
    "undo_change": "editor_undo_action",
}
# The + control's label carries the talent name as its argument (%1$s).
ADD_RANK_RESOURCE_KEY = "inspector_add_rank"
# The accessibility labels that must never count as talents (Hierarchy.talents),
# each exactly one resource — the set equality catches missing AND stale members.
NOT_TALENTS_RESOURCE_KEYS = (
    "common_go_back",
    "editor_undo_action",
    "editor_redo_action",
    "editor_build_actions_open",
    "section_fit_tree",
    "inspector_expand",
    "inspector_collapse",
    "inspector_max_rank_reached",
)
# The dataset talent the scenario spends on — game content, never a resource.
TALENT = "Improved Rend"


def load_section_check() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "android_section_check", ROOT / "scripts" / "android_section_check.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def resource_values(language: str) -> dict[str, str]:
    """string name -> value of one resource set (plurals are irrelevant here)."""
    folder = "values" if language == "en" else f"values-{language}"
    root = ET.parse(RESOURCES / folder / "strings.xml").getroot()
    return {node.get("name"): node.text or "" for node in root.findall("string")}


def ui_node(text: str = "", desc: str = "", selected: str = "false") -> str:
    """One uiautomator ``node`` element with only what Hierarchy reads."""
    return (
        f'<node text="{text}" content-desc="{desc}" selected="{selected}" '
        'clickable="true" bounds="[0,0][100,100]" />'
    )


# --------------------------------------------------------------------- the sets


def test_exactly_two_language_sets() -> None:
    check = load_section_check()
    assert set(check.UI_COPY_BY_LANGUAGE) == {"en", "ru"}


def test_english_selectors() -> None:
    check = load_section_check()
    copy = check.UI_COPY_BY_LANGUAGE["en"]
    assert copy.tabs == ("Arms", "Fury", "Protection")
    assert copy.add_rank(TALENT) == "Add rank to Improved Rend"
    # every localized field is set: an empty selector can never find its control
    for field in ("new_build", "warrior", "continue_editing", "undo_change", "add_rank_to"):
        assert getattr(copy, field)
    assert copy.not_talents


def test_russian_selectors() -> None:
    check = load_section_check()
    copy = check.UI_COPY_BY_LANGUAGE["ru"]
    assert copy.tabs == ("Оружие", "Неистовство", "Защита")
    assert copy.add_rank(TALENT) == "Добавить ранг: Improved Rend"
    for field in ("new_build", "warrior", "continue_editing", "undo_change", "add_rank_to"):
        assert getattr(copy, field)
    assert copy.not_talents


def test_the_two_sets_differ_field_by_field() -> None:
    check = load_section_check()
    en, ru = check.UI_COPY_BY_LANGUAGE["en"], check.UI_COPY_BY_LANGUAGE["ru"]
    # a selector shared by both languages would be a selector one of them
    # does not actually use — every field must be language-specific
    assert en.tabs != ru.tabs
    assert en.new_build != ru.new_build
    assert en.warrior != ru.warrior
    assert en.continue_editing != ru.continue_editing
    assert en.undo_change != ru.undo_change
    assert en.add_rank_to != ru.add_rank_to
    assert en.not_talents != ru.not_talents
    assert not (en.not_talents & ru.not_talents)


def test_selectors_mirror_the_apps_resources() -> None:
    """Every selector equals the string the app renders in that language — the
    script never invents copy the device does not show."""
    check = load_section_check()
    for language in ("en", "ru"):
        copy = check.UI_COPY_BY_LANGUAGE[language]
        values = resource_values(language)
        for field, key in PLAIN_RESOURCE_KEYS.items():
            assert getattr(copy, field) == values[key], f"{language}.{field} vs {key}"
        # the resource's %1$s placeholder becomes the script's {} template
        assert copy.add_rank_to == values[ADD_RANK_RESOURCE_KEY].replace("%1$s", "{}")
        assert copy.not_talents == frozenset(values[key] for key in NOT_TALENTS_RESOURCE_KEYS), (
            f"{language}.not_talents vs the resources"
        )


def test_talent_names_are_never_localized() -> None:
    """Dataset talent names are game content: on every device the scenario
    selects and spends on the same English node."""
    check = load_section_check()
    assert check.TALENT == TALENT
    for copy in check.UI_COPY_BY_LANGUAGE.values():
        label = copy.add_rank(check.TALENT)
        assert label.endswith(check.TALENT)  # the + label embeds the raw name
        assert check.TALENT not in copy.not_talents


# ----------------------------------------------------------------- the language


def test_primary_locale_selects_the_matching_set() -> None:
    """The app's own rule: Russian ONLY for a ru primary language; every other
    tag — and an unreadable one — falls back to the English set."""
    check = load_section_check()
    cases = {
        "ru": "ru",
        "ru-RU": "ru",
        "ru_KZ": "ru",
        "RU": "ru",
        "en": "en",
        "en-US": "en",
        "de-DE": "en",
        "fr": "en",
        "": "en",
        "not-a-locale": "en",
    }
    for tag, language in cases.items():
        assert check.language_of(tag) == language, tag
        assert (
            check.UI_COPY_BY_LANGUAGE[check.language_of(tag)]
            is (check.UI_COPY_BY_LANGUAGE[language])
        ), tag


# ------------------------------------------------------------------- hierarchy


def test_hierarchy_filters_control_labels_of_its_own_language() -> None:
    """``talents()`` reads the screen's talents and must drop the control
    ACCESSIBILITY labels of the dump's language — the review's NOT_TALENTS
    point. Like the real ``dataset_sections()`` keys (exact talent names), the
    sections map here contains the ``not_talents`` labels as keys too: only the
    filter can keep such a label from reading as a talent on screen."""
    check = load_section_check()
    for language in ("en", "ru"):
        copy = check.UI_COPY_BY_LANGUAGE[language]
        controls = [copy.add_rank(TALENT), *sorted(copy.not_talents)]
        # section 0's talents, plus every control label masquerading as one
        sections = dict.fromkeys([TALENT, "Deflection", *copy.not_talents], 0)
        xml = (
            "<hierarchy>"
            + ui_node(text=copy.tabs[0], selected="true")  # the active first tab
            + "".join(ui_node(desc=desc) for desc in controls)
            + ui_node(desc=TALENT)
            + ui_node(desc="Deflection")
            + "</hierarchy>"
        )
        ui = check.Hierarchy(xml, copy)
        assert ui.find(copy.add_rank(TALENT)) is not None  # the + control is found
        assert ui.active_tab() == copy.tabs[0]
        assert ui.talents(sections) == [TALENT, "Deflection"]
