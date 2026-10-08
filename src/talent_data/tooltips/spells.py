"""Build-pinned spell lookup for tooltip evaluation.

Everything a tooltip can reference - its own spell, another spell (``$12976s1``), a nested
tooltip - is answered from the tables of ONE client build, loaded here. There is no
fallback to another build, another difficulty or a default value: a missing row is a
missing row.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable, Mapping
from dataclasses import dataclass

from talent_data.db2 import tables as t
from talent_data.sources.base import Db2Source, SourceError
from talent_data.tooltips.values import DurationFormats
from talent_data.versions import ClientVersion

# Player talents use the difficulty-independent rows (same rule as the normalizer).
DEFAULT_DIFFICULTY_ID = 0


def _unique[R](rows: Iterable[R], table: str) -> dict[int, R]:
    out: dict[int, R] = {}
    for row in rows:
        key = getattr(row, "id")  # noqa: B009 - every record has an int ``id``
        if key in out:
            raise SourceError(f"{table}: duplicate ID={key}")
        out[key] = row
    return out


def _by_spell[R](rows: Iterable[R]) -> dict[int, tuple[R, ...]]:
    grouped: dict[int, list[R]] = defaultdict(list)
    for row in rows:
        grouped[getattr(row, "spell_id")].append(row)  # noqa: B009
    return {
        spell_id: tuple(sorted(group, key=lambda r: getattr(r, "id")))  # noqa: B009
        for spell_id, group in grouped.items()
    }


@dataclass(frozen=True)
class SpellLookup:
    version: ClientVersion
    descriptions: Mapping[int, str]  # Spell.ID -> Description_lang
    effects: Mapping[int, tuple[t.SpellEffectValues, ...]]  # by SpellID, every difficulty
    misc: Mapping[int, tuple[t.SpellMiscDuration, ...]]  # by SpellID, every difficulty
    durations: Mapping[int, t.SpellDuration]
    radii: Mapping[int, t.SpellRadius]
    aura_options: Mapping[int, tuple[t.SpellAuraOptions, ...]]  # by SpellID
    description_variables: Mapping[int, tuple[t.SpellDescriptionVariables, ...]]  # by SpellID
    duration_formats: DurationFormats

    @classmethod
    def load(cls, source: Db2Source, version: ClientVersion) -> SpellLookup:
        def rows[R: t.Db2Record](record: type[R]) -> list[R]:
            return t.parse_table(source.load_table(version, record.TABLE), record.parse)

        spells = _unique(rows(t.Spell), "Spell")
        effects = rows(t.SpellEffectValues)
        _unique(effects, "SpellEffect")
        misc = rows(t.SpellMiscDuration)
        _unique(misc, "SpellMisc")
        options = rows(t.SpellAuraOptions)
        _unique(options, "SpellAuraOptions")
        variables = _unique(rows(t.SpellDescriptionVariables), "SpellDescriptionVariables")
        links = rows(t.SpellXDescriptionVariables)
        _unique(links, "SpellXDescriptionVariables")
        by_spell: dict[int, list[t.SpellDescriptionVariables]] = defaultdict(list)
        for link in links:
            definition = variables.get(link.spell_description_variables_id)
            if definition is None:
                raise SourceError(
                    f"SpellXDescriptionVariables {link.id} links unknown "
                    f"SpellDescriptionVariables {link.spell_description_variables_id}"
                )
            by_spell[link.spell_id].append(definition)
        return cls(
            version=version,
            descriptions={spell_id: spell.description for spell_id, spell in spells.items()},
            effects=_by_spell(effects),
            misc=_by_spell(misc),
            durations=_unique(rows(t.SpellDuration), "SpellDuration"),
            radii=_unique(rows(t.SpellRadius), "SpellRadius"),
            aura_options=_by_spell(options),
            description_variables={k: tuple(v) for k, v in by_spell.items()},
            duration_formats=DurationFormats.from_global_strings(rows(t.GlobalString)),
        )

    # ------------------------------------------------------------------ default rows

    def effect_rows(self, spell_id: int, effect_index: int) -> list[t.SpellEffectValues]:
        return [
            row
            for row in self.effects.get(spell_id, ())
            if row.effect_index == effect_index and row.difficulty_id == DEFAULT_DIFFICULTY_ID
        ]

    def default_effects(self, spell_id: int) -> list[t.SpellEffectValues]:
        return [
            row
            for row in self.effects.get(spell_id, ())
            if row.difficulty_id == DEFAULT_DIFFICULTY_ID
        ]

    def misc_rows(self, spell_id: int) -> list[t.SpellMiscDuration]:
        return [
            row for row in self.misc.get(spell_id, ()) if row.difficulty_id == DEFAULT_DIFFICULTY_ID
        ]

    def aura_option_rows(self, spell_id: int) -> list[t.SpellAuraOptions]:
        return [
            row
            for row in self.aura_options.get(spell_id, ())
            if row.difficulty_id == DEFAULT_DIFFICULTY_ID
        ]
