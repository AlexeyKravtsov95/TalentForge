"""Parser for the client tooltip template syntax used by Forever talents.

It recognises exactly the constructs found in the 1.60.1.70009 templates (see
``docs/research/forever-tooltip-template-inventory.md``) and nothing more. Anything else is
a :class:`TemplateSyntaxError`: an unknown construct has an unknown extent, so the text
around it cannot be trusted either and the whole description is reported unresolved.

The parser only builds a tree; it evaluates nothing and needs no client data. Expressions
inside ``${...}`` are parsed by a small recursive-descent grammar (``+ - * /``, parentheses,
numeric literals with an optional leading minus, and references) - never by ``eval``.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from fractions import Fraction

from talent_data.models.tooltips import TokenCategory


class TemplateSyntaxError(ValueError):
    """The template uses syntax outside the supported grammar."""

    def __init__(self, message: str, token: str) -> None:
        super().__init__(message)
        self.token = token


# --------------------------------------------------------------------------- nodes


@dataclass(frozen=True)
class Text:
    text: str


@dataclass(frozen=True)
class LineBreak:
    raw: str


@dataclass(frozen=True)
class Color:
    """``|cAARRGGBB`` or ``|r``: presentation only, dropped from the rendered text."""

    raw: str


# Letters of a value reference: s/m effect value, o periodic total, a radius, t period,
# d duration, h proc chance, n proc charges, u max stacks.
REF_LETTERS = frozenset("smoatdhnu")


@dataclass(frozen=True)
class Ref:
    spell_id: int | None  # None = the spell whose template is being rendered
    letter: str  # lower-case, one of REF_LETTERS
    index: int | None  # 1-based effect index as written ($s1 -> 1); None when absent
    raw: str

    @property
    def category(self) -> TokenCategory:
        local = self.spell_id is None
        match self.letter:
            case "s" | "m":
                return "local-effect-value" if local else "cross-spell-effect-value"
            case "o":
                return "local-periodic-total" if local else "unclassified"
            case "a":
                return "local-radius" if local else "cross-spell-radius"
            case "t":
                return "local-period" if local else "cross-spell-period"
            case "d":
                return "local-duration" if local else "cross-spell-duration"
            case _:
                return "local-proc" if local else "cross-spell-proc"


@dataclass(frozen=True)
class NamedVariable:
    """``$rap``, ``$bh``, ``$proccooldown`` ...: a named value with no number."""

    name: str
    raw: str


@dataclass(frozen=True)
class DescriptionVariable:
    """``$<name>``: a variable defined in SpellDescriptionVariables."""

    name: str
    raw: str


@dataclass(frozen=True)
class Scaled:
    """``$/N;<ref>`` or ``$*N;<ref>``."""

    operator: str  # "/" or "*"
    factor: int
    ref: Ref
    raw: str


# Expression tree of ``${...}``.
@dataclass(frozen=True)
class Number:
    value: Fraction


@dataclass(frozen=True)
class Binary:
    operator: str  # + - * /
    left: ExprNode
    right: ExprNode


ExprNode = Number | Binary | Ref | NamedVariable | DescriptionVariable


@dataclass(frozen=True)
class Expression:
    """``${...}`` with an optional ``.N`` precision suffix."""

    tree: ExprNode | None  # None when the content is outside the expression grammar
    precision: int | None
    raw: str
    error: str | None  # why ``tree`` is None


@dataclass(frozen=True)
class Plural:
    """``$lsingular:plural;`` (``$L`` written the same way)."""

    singular: str
    plural: str
    raw: str


@dataclass(frozen=True)
class Conditional:
    """``$?<condition>[branch][branch]...``"""

    condition: str
    branches: tuple[tuple[Node, ...], ...]
    raw: str


@dataclass(frozen=True)
class Nested:
    """``$@spelltooltip<spell>``: another spell's description, inlined."""

    kind: str
    spell_id: int
    raw: str


Node = (
    Text
    | LineBreak
    | Color
    | Ref
    | NamedVariable
    | DescriptionVariable
    | Scaled
    | Expression
    | Plural
    | Conditional
    | Nested
)


def category_of(node: Node | ExprNode) -> TokenCategory:
    match node:
        case Ref():
            return node.category
        case NamedVariable():
            return "named-variable"
        case DescriptionVariable():
            return "description-variable"
        case Scaled():
            return "scaled-value"
        case Expression():
            return "arithmetic"
        case Plural():
            return "plural"
        case Conditional():
            return "conditional"
        case Nested():
            return "nested-tooltip"
        case Color():
            return "color"
        case LineBreak():
            return "line-break"
        case _:
            return "unclassified"


# --------------------------------------------------------------------------- tokenizer

_REF = re.compile(r"\$(\d+)?([A-Za-z]+)(\d+)?")
_SCALED = re.compile(r"\$([/*])(\d+);(\d+)?([A-Za-z])(\d+)?")
_PLURAL = re.compile(r"\$[lL]([^:;$\s]*):([^;$]*);")
_NESTED = re.compile(r"\$@([a-z]+)(\d+)")
_VARIABLE = re.compile(r"\$<([A-Za-z_][A-Za-z0-9_]*)>")
_COLOR = re.compile(r"\|(?:[cC][0-9A-Fa-f]{8}|[rR])")
_PRECISION = re.compile(r"\.(\d)(?!\d)")
_CONDITION = re.compile(r"\$\?([^\[\]\s]+)")


def _closing(text: str, start: int, opener: str, closer: str) -> int:
    """Index just past the bracket closing the one at ``start``; -1 if unbalanced."""
    depth = 0
    for index in range(start, len(text)):
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index + 1
    return -1


def _ref(spell: str | None, letters: str, index: str | None, raw: str) -> Ref:
    letter = letters.lower()
    # Upper case is only written for effect values ($S1 / $M1), with the same meaning.
    if len(letters) != 1 or letter not in REF_LETTERS or (letters.isupper() and letter not in "sm"):
        raise TemplateSyntaxError(f"unknown reference {raw!r}", raw)
    return Ref(
        spell_id=int(spell) if spell else None,
        letter=letter,
        index=int(index) if index else None,
        raw=raw,
    )


def parse(template: str) -> tuple[Node, ...]:
    """Parse a whole template. Raises :class:`TemplateSyntaxError` on unknown syntax."""
    nodes: list[Node] = []
    buffer: list[str] = []
    position = 0

    def flush() -> None:
        if buffer:
            nodes.append(Text("".join(buffer)))
            buffer.clear()

    while position < len(template):
        char = template[position]
        if char in "\r\n":
            flush()
            end = position + 2 if template.startswith("\r\n", position) else position + 1
            nodes.append(LineBreak(template[position:end]))
            position = end
            continue
        if char == "|":
            match = _COLOR.match(template, position)
            if not match:
                raise TemplateSyntaxError(
                    f"unknown '|' markup at {position}", template[position : position + 10]
                )
            flush()
            nodes.append(Color(match.group(0)))
            position = match.end()
            continue
        if char != "$":
            buffer.append(char)
            position += 1
            continue

        flush()
        node, position = _parse_dollar(template, position)
        nodes.append(node)
    flush()
    return tuple(nodes)


def _parse_dollar(template: str, position: int) -> tuple[Node, int]:
    if template.startswith("${", position):
        end = _closing(template, position + 1, "{", "}")
        if end < 0:
            raise TemplateSyntaxError("unbalanced '${'", template[position : position + 12])
        precision = _PRECISION.match(template, end)
        whole_end = precision.end() if precision else end
        raw = template[position:whole_end]
        inner = template[position + 2 : end - 1]
        try:
            tree: ExprNode | None = parse_expression(inner)
            error = None
        except TemplateSyntaxError as exc:
            tree, error = None, str(exc)
        return (
            Expression(
                tree=tree,
                precision=int(precision.group(1)) if precision else None,
                raw=raw,
                error=error,
            ),
            whole_end,
        )

    condition = _CONDITION.match(template, position)
    if condition and template.startswith("[", condition.end()):
        branches: list[tuple[Node, ...]] = []
        cursor = condition.end()
        while template.startswith("[", cursor):
            end = _closing(template, cursor, "[", "]")
            if end < 0:
                raise TemplateSyntaxError("unbalanced conditional branch", template[cursor:])
            branches.append(parse(template[cursor + 1 : end - 1]))
            cursor = end
        return (
            Conditional(
                condition=condition.group(1),
                branches=tuple(branches),
                raw=template[position:cursor],
            ),
            cursor,
        )

    for pattern in (_NESTED, _VARIABLE, _PLURAL, _SCALED):
        match = pattern.match(template, position)
        if not match:
            continue
        raw = match.group(0)
        if pattern is _NESTED:
            return Nested(kind=match.group(1), spell_id=int(match.group(2)), raw=raw), match.end()
        if pattern is _VARIABLE:
            return DescriptionVariable(name=match.group(1), raw=raw), match.end()
        if pattern is _PLURAL:
            return Plural(singular=match.group(1), plural=match.group(2), raw=raw), match.end()
        operator, factor, spell, letters, index = match.groups()
        ref = _ref(spell, letters, index, raw)
        if ref.letter not in "sm":
            raise TemplateSyntaxError(f"scaled reference to {letters!r} in {raw!r}", raw)
        return Scaled(operator=operator, factor=int(factor), ref=ref, raw=raw), match.end()

    reference = _REF.match(template, position)
    if reference:
        spell, letters, index = reference.groups()
        raw = reference.group(0)
        if len(letters) > 1 and spell is None and index is None:
            return NamedVariable(name=letters, raw=raw), reference.end()
        return _ref(spell, letters, index, raw), reference.end()
    raise TemplateSyntaxError(f"unknown '$' construct at {position}", template[position:][:10])


# --------------------------------------------------------------------------- expressions

_EXPR_TOKEN = re.compile(
    r"\s*(?:(?P<number>\d+(?:\.\d+)?)|(?P<ref>\$(?:<[A-Za-z_][A-Za-z0-9_]*>|\d*[A-Za-z]+\d*))"
    r"|(?P<op>[-+*/()]))"
)


def parse_expression(text: str) -> ExprNode:
    """``expr := term (('+'|'-') term)*``, ``term := factor (('*'|'/') factor)*``,
    ``factor := number | '-' number | reference | '(' expr ')'``."""
    tokens: list[tuple[str, str]] = []
    position = 0
    while position < len(text):
        if text[position:].strip() == "":
            break
        match = _EXPR_TOKEN.match(text, position)
        if not match or match.end() == position:
            raise TemplateSyntaxError(
                f"unsupported expression syntax {text[position:]!r}", "${" + text + "}"
            )
        kind = match.lastgroup
        assert kind is not None
        tokens.append((kind, match.group(kind)))
        position = match.end()

    cursor = 0

    def peek() -> tuple[str, str] | None:
        return tokens[cursor] if cursor < len(tokens) else None

    def take() -> tuple[str, str]:
        nonlocal cursor
        token = peek()
        if token is None:
            raise TemplateSyntaxError("unexpected end of expression", "${" + text + "}")
        cursor += 1
        return token

    def expr() -> ExprNode:
        node = term()
        while (token := peek()) in (("op", "+"), ("op", "-")):
            assert token is not None
            take()
            node = Binary(token[1], node, term())
        return node

    def term() -> ExprNode:
        node = factor()
        while (token := peek()) in (("op", "*"), ("op", "/")):
            assert token is not None
            take()
            node = Binary(token[1], node, factor())
        return node

    def factor() -> ExprNode:
        kind, value = take()
        if kind == "number":
            return Number(Fraction(value))
        if kind == "op" and value == "-":
            next_kind, next_value = take()
            if next_kind != "number":
                raise TemplateSyntaxError(
                    "a leading minus is only supported before a number", "${" + text + "}"
                )
            return Number(-Fraction(next_value))
        if kind == "op" and value == "(":
            node = expr()
            if take() != ("op", ")"):
                raise TemplateSyntaxError("unbalanced parenthesis", "${" + text + "}")
            return node
        if kind == "ref":
            return _expression_ref(value)
        raise TemplateSyntaxError(f"unexpected {value!r} in expression", "${" + text + "}")

    tree = expr()
    if cursor != len(tokens):
        raise TemplateSyntaxError(
            f"unexpected {tokens[cursor][1]!r} in expression", "${" + text + "}"
        )
    return tree


def _expression_ref(raw: str) -> ExprNode:
    variable = _VARIABLE.fullmatch(raw)
    if variable:
        return DescriptionVariable(name=variable.group(1), raw=raw)
    match = _REF.fullmatch(raw)
    if not match:
        raise TemplateSyntaxError(f"unsupported reference {raw!r} in expression", raw)
    spell, letters, index = match.groups()
    if len(letters) > 1 and spell is None and index is None:
        return NamedVariable(name=letters, raw=raw)
    return _ref(spell, letters, index, raw)


def referenced_spells(nodes: tuple[Node, ...]) -> tuple[set[int], set[int]]:
    """``(value references, nested tooltips)``: the other spells a parsed template reads."""
    values: set[int] = set()
    nested: set[int] = set()

    def walk_expression(node: ExprNode) -> None:
        match node:
            case Binary():
                walk_expression(node.left)
                walk_expression(node.right)
            case Ref() if node.spell_id is not None:
                values.add(node.spell_id)

    def walk(items: tuple[Node, ...]) -> None:
        for node in items:
            match node:
                case Ref() if node.spell_id is not None:
                    values.add(node.spell_id)
                case Scaled() if node.ref.spell_id is not None:
                    values.add(node.ref.spell_id)
                case Expression() if node.tree is not None:
                    walk_expression(node.tree)
                case Conditional():
                    for branch in node.branches:
                        walk(branch)
                case Nested():
                    nested.add(node.spell_id)

    walk(nodes)
    return values, nested
