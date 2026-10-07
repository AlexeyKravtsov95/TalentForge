#!/usr/bin/env python3
"""Read-only inventory of the tooltip template syntax used by the bundled Forever talents.

Input to M5.V2 (tooltip rendering). M5.V1 renders no talent description: every
``descriptionTemplate`` of ``talent-data.v1`` is raw client tooltip markup, and this script
only *counts and classifies* that markup - it evaluates nothing and changes no data.

The classification is purely syntactic. Every ``$`` and ``|`` construct of every template
must land in a category; anything the tokenizer does not recognise is reported under
``unclassified`` (and fails ``--check``) instead of being ignored.

Outputs (deterministic, no timestamps):
    docs/research/forever-tooltip-template-inventory.json   machine-readable, per entry
    docs/research/forever-tooltip-template-inventory.md     human summary

Usage:
    python scripts/tooltip_template_inventory.py            # regenerate both files
    python scripts/tooltip_template_inventory.py --check    # fail if they are out of date
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

GAME = "forever"
BUILD = "1.60.1.70009"

REPO_ROOT = Path(__file__).resolve().parent.parent
DATASET_DIR = REPO_ROOT / "data" / "normalized" / GAME / BUILD
OUT_DIR = REPO_ROOT / "docs" / "research"
JSON_OUT = OUT_DIR / "forever-tooltip-template-inventory.json"
MD_OUT = OUT_DIR / "forever-tooltip-template-inventory.md"

# category -> what the construct looks like. Meanings in parentheses are the conventional
# reading of the client tooltip language; none of them is verified for Forever here.
CATEGORIES: dict[str, str] = {
    "local-effect-value": "`$sN` `$SN` `$mN` `$MN` `$m` - a value of this spell's effect N",
    "local-periodic-total": "`$oN` - (total of a periodic effect N of this spell)",
    "local-radius": "`$aN` - (radius of effect N of this spell)",
    "local-period": "`$tN` `$t` - (tick period of effect N of this spell)",
    "local-duration": "`$d` - (duration of this spell)",
    "local-proc": "`$h` `$n` `$u` - (proc chance / proc charges / max stacks of this spell)",
    "cross-spell-effect-value": "`$<spell>sN` `$<spell>mN` - an effect value of another spell",
    "cross-spell-radius": "`$<spell>aN` - (radius of an effect of another spell)",
    "cross-spell-period": "`$<spell>tN` - (tick period of an effect of another spell)",
    "cross-spell-duration": "`$<spell>d` - (duration of another spell)",
    "cross-spell-proc": "`$<spell>n` `$<spell>u` - (proc charges / stacks of another spell)",
    "scaled-value": "`$/N;<ref>` `$*N;<ref>` - a reference divided / multiplied by a constant",
    "arithmetic": "`${ ... }` with optional `.N` precision suffix - an expression",
    "named-variable": "`$proccooldown`, `$rap`, `$bh`, `$bc` ... - a named (not numbered) value",
    "description-variable": "`$<name>` - a variable defined outside the template",
    "plural": "`$lone:many;` `$Lone:many;` - singular/plural selected by the preceding number",
    "conditional": "`$?<cond>[then][else]` - text chosen by a client-side condition",
    "nested-tooltip": "`$@spelltooltip<spell>` - another spell's whole tooltip, inlined",
    "color": "`|CAARRGGBB` ... `|R` - colour markup",
    "line-break": "`\\r\\n` - explicit line breaks inside the template",
    "unclassified": "anything the tokenizer does not recognise (must stay empty)",
}

# Categories that need nothing but text to display. A template whose only constructs are
# these (or none at all) is static text; everything else needs evaluation.
_PRESENTATION_ONLY = frozenset({"line-break"})

_LOCAL_LETTERS = {
    "s": "local-effect-value",
    "m": "local-effect-value",
    "o": "local-periodic-total",
    "a": "local-radius",
    "t": "local-period",
    "d": "local-duration",
    "h": "local-proc",
    "n": "local-proc",
    "u": "local-proc",
}
_CROSS_LETTERS = {
    "s": "cross-spell-effect-value",
    "m": "cross-spell-effect-value",
    "a": "cross-spell-radius",
    "t": "cross-spell-period",
    "d": "cross-spell-duration",
    "n": "cross-spell-proc",
    "u": "cross-spell-proc",
}

_REF = re.compile(r"\$(\d+)?([A-Za-z]+)(\d+)?")
_SCALED = re.compile(r"\$[/*]\d+;(\d+)?[A-Za-z]+\d*")
_PLURAL = re.compile(r"\$[lL][^:;$\s]*:[^;$]*;")
_NESTED = re.compile(r"\$@[a-z]+\d+")
_VARIABLE = re.compile(r"\$<[A-Za-z_][A-Za-z0-9_]*>")
_COLOR_OPEN = re.compile(r"\|[cC][0-9A-Fa-f]{8}")
_COLOR_CLOSE = re.compile(r"\|[rR]")
_PRECISION = re.compile(r"\.\d")
_CONDITION = re.compile(r"\$\?[^\[\]\s]+")


@dataclass(frozen=True)
class Token:
    category: str
    text: str
    form: str  # the token with spell ids / indices replaced by N, for grouping
    nested_in: str | None = None  # "arithmetic" / "conditional" for inner tokens


def _form(text: str) -> str:
    return re.sub(r"\d+", "N", text)


def _classify_ref(match: re.Match[str]) -> str:
    spell, letters, _index = match.groups()
    if len(letters) == 1:
        table = _CROSS_LETTERS if spell else _LOCAL_LETTERS
        return table.get(letters.lower(), "unclassified")
    return "unclassified" if spell else "named-variable"


def _closing(text: str, start: int, opener: str, closer: str) -> int:
    """Index just past the bracket that closes the one at ``start`` (-1 if unbalanced)."""
    depth = 0
    for index in range(start, len(text)):
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index + 1
    return -1


def tokenize(text: str, nested_in: str | None = None) -> list[Token]:
    tokens: list[Token] = []

    def add(category: str, raw: str) -> None:
        tokens.append(Token(category, raw, _form(raw), nested_in))

    if nested_in is None and "\r\n" in text:
        tokens.extend(Token("line-break", "\\r\\n", "\\r\\n") for _ in range(text.count("\r\n")))

    position = 0
    while position < len(text):
        char = text[position]
        if char == "$":
            if text.startswith("${", position):
                end = _closing(text, position + 1, "{", "}")
                if end < 0:
                    add("unclassified", text[position : position + 12])
                    position += 2
                    continue
                precision = _PRECISION.match(text, end)
                whole_end = precision.end() if precision else end
                tokens.append(
                    Token(
                        "arithmetic",
                        text[position:whole_end],
                        "${...}" + (".N" if precision else ""),
                        nested_in,
                    )
                )
                tokens.extend(tokenize(text[position + 2 : end - 1], "arithmetic"))
                position = whole_end
                continue
            condition = _CONDITION.match(text, position)
            if condition and text.startswith("[", condition.end()):
                add("conditional", condition.group(0))
                position = condition.end()
                while text.startswith("[", position):
                    end = _closing(text, position, "[", "]")
                    if end < 0:
                        add("unclassified", text[position : position + 12])
                        position += 1
                        break
                    tokens.extend(tokenize(text[position + 1 : end - 1], "conditional"))
                    position = end
                continue
            for pattern, category in (
                (_NESTED, "nested-tooltip"),
                (_VARIABLE, "description-variable"),
                (_PLURAL, "plural"),
                (_SCALED, "scaled-value"),
            ):
                match = pattern.match(text, position)
                if match:
                    add(category, match.group(0))
                    position = match.end()
                    break
            else:
                ref = _REF.match(text, position)
                if ref:
                    add(_classify_ref(ref), ref.group(0))
                    position = ref.end()
                else:
                    add("unclassified", text[position : position + 8])
                    position += 1
            continue
        if char == "|":
            match = _COLOR_OPEN.match(text, position) or _COLOR_CLOSE.match(text, position)
            if match:
                add("color", match.group(0))
                position = match.end()
            else:
                add("unclassified", text[position : position + 8])
                position += 1
            continue
        position += 1
    return tokens


def load_entries() -> list[dict[str, object]]:
    manifest = json.loads((DATASET_DIR / "manifest.json").read_text(encoding="utf-8"))
    entries: list[dict[str, object]] = []
    for class_entry in manifest["classes"]:
        document = json.loads((DATASET_DIR / class_entry["file"]).read_text(encoding="utf-8"))
        for tree in document["trees"]:
            for node in tree["nodes"]:
                for entry in node["entries"]:
                    entries.append(
                        {
                            "classToken": class_entry["token"],
                            "nodeId": node["id"],
                            "entryId": entry["id"],
                            "name": entry["name"],
                            "template": entry["descriptionTemplate"],
                        }
                    )
    return entries


def build_inventory() -> dict[str, object]:
    entries = load_entries()
    per_entry: list[dict[str, object]] = []
    occurrences: dict[str, int] = defaultdict(int)
    users: dict[str, set[int]] = defaultdict(set)
    classes: dict[str, set[str]] = defaultdict(set)
    forms: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    examples: dict[str, list[dict[str, object]]] = defaultdict(list)
    static: list[dict[str, object]] = []
    missing = 0

    for entry in entries:
        template = entry["template"]
        entry_id = entry["entryId"]
        assert isinstance(entry_id, int)
        if template is None:
            missing += 1
            per_entry.append({**_identity(entry), "categories": [], "tokens": [], "static": False})
            continue
        assert isinstance(template, str)
        tokens = tokenize(template)
        used = sorted({token.category for token in tokens})
        is_static = set(used) <= _PRESENTATION_ONLY
        if is_static:
            static.append({**_identity(entry), "text": template})
        for token in tokens:
            occurrences[token.category] += 1
            users[token.category].add(entry_id)
            classes[token.category].add(str(entry["classToken"]))
            forms[token.category][token.form] += 1
            if len(examples[token.category]) < 3 and not any(
                e["entryId"] == entry_id for e in examples[token.category]
            ):
                examples[token.category].append({**_identity(entry), "token": token.text})
        per_entry.append(
            {
                **_identity(entry),
                "categories": used,
                "static": is_static,
                "tokens": [
                    {"category": t.category, "text": t.text, "nestedIn": t.nested_in}
                    for t in tokens
                ],
            }
        )

    categories = [
        {
            "category": name,
            "syntax": CATEGORIES[name],
            "occurrences": occurrences.get(name, 0),
            "entries": len(users.get(name, ())),
            "classes": sorted(classes.get(name, ())),
            "forms": dict(sorted(forms.get(name, {}).items())),
            "examples": examples.get(name, []),
        }
        for name in CATEGORIES
    ]
    return {
        "game": GAME,
        "clientBuild": BUILD,
        "source": f"data/normalized/{GAME}/{BUILD}/*.json (TalentEntry.descriptionTemplate)",
        "totalEntries": len(entries),
        "entriesWithoutTemplate": missing,
        "staticEntries": len(static),
        "entriesNeedingEvaluation": len(entries) - len(static) - missing,
        "categories": categories,
        "static": static,
        "entries": per_entry,
    }


def _identity(entry: dict[str, object]) -> dict[str, object]:
    return {
        "classToken": entry["classToken"],
        "nodeId": entry["nodeId"],
        "entryId": entry["entryId"],
        "name": entry["name"],
    }


def render_json(inventory: dict[str, object]) -> str:
    return json.dumps(inventory, indent=2, ensure_ascii=False) + "\n"


def render_markdown(inventory: dict[str, object]) -> str:
    categories = inventory["categories"]
    static = inventory["static"]
    assert isinstance(categories, list) and isinstance(static, list)
    lines = [
        "# Forever tooltip template inventory (input to M5.V2)",
        "",
        "> Generated by `python scripts/tooltip_template_inventory.py` from the committed",
        f"> dataset `data/normalized/{inventory['game']}/{inventory['clientBuild']}/`. Do not",
        "> edit by hand; `--check` (and the test suite) fails when this file is out of date.",
        "",
        "M5.V1 renders **no** talent description. Every talent entry carries a raw client",
        "tooltip template in `descriptionTemplate`; almost all of them contain markup the",
        "client evaluates at display time. This is a read-only, purely syntactic count of",
        "that markup - nothing is evaluated here, and the meanings in parentheses are the",
        "conventional reading of the tooltip language, **not** verified for Forever.",
        "",
        "## Totals",
        "",
        "| | entries |",
        "| --- | ---: |",
        f"| talent entries (9 classes) | {inventory['totalEntries']} |",
        f"| without a template | {inventory['entriesWithoutTemplate']} |",
        f"| static text (no `$`/`|` construct) | {inventory['staticEntries']} |",
        f"| need template evaluation | {inventory['entriesNeedingEvaluation']} |",
        "",
        "## Syntax categories present",
        "",
        "| category | syntax | occurrences | entries | classes |",
        "| --- | --- | ---: | ---: | ---: |",
    ]
    for category in categories:
        syntax = str(category["syntax"]).replace("|", "\\|")
        lines.append(
            f"| `{category['category']}` | {syntax} | {category['occurrences']} | "
            f"{category['entries']} | {len(category['classes'])} |"
        )
    lines += ["", "## Forms and examples", ""]
    for category in categories:
        if not category["occurrences"]:
            continue
        lines += [f"### `{category['category']}`", ""]
        forms = ", ".join(
            f"`{form.replace('|', '¦')}` x{count}" for form, count in category["forms"].items()
        )
        lines += [f"Forms (ids and indices shown as `N`; `¦` stands for `|`): {forms}", ""]
        for example in category["examples"]:
            token = str(example["token"]).replace("|", "¦")
            lines.append(
                f"- {example['classToken']} · {example['name']} "
                f"(entry {example['entryId']}): `{token}`"
            )
        lines.append("")
    lines += [
        "## Static entries",
        "",
        "The only templates that are plain text. They are listed for completeness; M5.V1",
        "does not display them either, so that no talent shows a description while its",
        "neighbours cannot.",
        "",
    ]
    for entry in static:
        text = str(entry["text"]).replace("\r\n", " ").strip()
        lines.append(
            f"- {entry['classToken']} · {entry['name']} (entry {entry['entryId']}): {text}"
        )
    lines += [
        "",
        "## What M5.V2 has to decide",
        "",
        "- which spell tables supply each reference kind (effect values, durations, radii,",
        "  periods, proc data) for the local spell and for cross-spell references;",
        "- how per-rank values (`effects[].perRank` in `talent-data.v1`) substitute into",
        "  `$sN`/`$mN` and into arithmetic that reads them;",
        "- the expression grammar of `${...}` (operators, precision suffix, named values);",
        "- the plural rule and which number a plural token binds to;",
        "- how conditionals are presented when the condition is client state (an aura, a",
        "  form) that a planner does not have;",
        "- whether nested tooltips and description variables are resolved or shown as",
        "  unavailable;",
        "- colour markup and line breaks as presentation.",
        "",
        "Until then the rule is: a raw template is never shown to the user.",
        "",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify the committed files")
    args = parser.parse_args(argv)
    inventory = build_inventory()
    outputs = {JSON_OUT: render_json(inventory), MD_OUT: render_markdown(inventory)}
    categories = inventory["categories"]
    assert isinstance(categories, list)
    unclassified = next(c for c in categories if c["category"] == "unclassified")
    if unclassified["occurrences"]:
        print(f"unclassified template constructs: {unclassified['forms']}", file=sys.stderr)
        return 1
    if args.check:
        stale = [
            str(path.relative_to(REPO_ROOT))
            for path, text in outputs.items()
            if not path.is_file() or path.read_text(encoding="utf-8") != text
        ]
        if stale:
            print(f"tooltip template inventory is out of date: {stale}", file=sys.stderr)
            print("run: python scripts/tooltip_template_inventory.py", file=sys.stderr)
            return 1
        print(f"tooltip template inventory in sync ({inventory['totalEntries']} entries)")
        return 0
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for path, text in outputs.items():
        path.write_text(text, encoding="utf-8")
        print(f"wrote {path.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
