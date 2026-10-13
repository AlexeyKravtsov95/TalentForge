package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage

/**
 * M5.REL.1: the ONE place a talent specialization display name comes from.
 *
 * The published Forever datasets split each class tree into three sections
 * but publish no `TalentSection.name`, so the tabs used to read "Tree N".
 * This resolver maps the stable pair (class token, `TalentSection.index`)
 * to the approved specialization names (English and Russian) — the mapping
 * was verified in this repository against every section's node membership
 * on every bundled client build (each section holds exactly its
 * specialization's talents, e.g. Warrior 0 = Mortal Strike's tree = Arms).
 * Identity never passes through here: section indices, ids and builds are
 * untouched, and the resolved label is never persisted into a
 * `BuildState` or a saved build.
 *
 * A dataset that PUBLISHES a section name still wins (see
 * [talentSectionDisplayLabel]) — the dataset remains the source of truth;
 * a class or section outside the approved table falls back to the localized
 * generic label ("Tree N" / "Ветка N"), never a guessed specialization.
 */
object TalentSpecializationNames {

    /** One section's approved display names, by [AppLanguage]. */
    private data class Names(val english: String, val russian: String)

    /** The approved mapping: class token → section index (0-based, ascending) → names. */
    private val names: Map<ClassToken, List<Names>> = mapOf(
        token("WARRIOR") to listOf(
            Names("Arms", "Оружие"),
            Names("Fury", "Неистовство"),
            Names("Protection", "Защита"),
        ),
        token("PALADIN") to listOf(
            Names("Holy", "Свет"),
            Names("Protection", "Защита"),
            Names("Retribution", "Воздаяние"),
        ),
        token("HUNTER") to listOf(
            Names("Beast Mastery", "Повелитель зверей"),
            Names("Marksmanship", "Стрельба"),
            Names("Survival", "Выживание"),
        ),
        token("ROGUE") to listOf(
            Names("Assassination", "Ликвидация"),
            Names("Combat", "Бой"),
            Names("Subtlety", "Скрытность"),
        ),
        token("PRIEST") to listOf(
            Names("Discipline", "Послушание"),
            Names("Holy", "Свет"),
            Names("Shadow", "Тьма"),
        ),
        token("SHAMAN") to listOf(
            Names("Elemental", "Стихии"),
            Names("Enhancement", "Совершенствование"),
            Names("Restoration", "Исцеление"),
        ),
        token("MAGE") to listOf(
            Names("Arcane", "Тайная магия"),
            Names("Fire", "Огонь"),
            Names("Frost", "Лёд"),
        ),
        token("WARLOCK") to listOf(
            Names("Affliction", "Колдовство"),
            Names("Demonology", "Демонология"),
            Names("Destruction", "Разрушение"),
        ),
        token("DRUID") to listOf(
            Names("Balance", "Баланс"),
            Names("Feral Combat", "Сила зверя"),
            Names("Restoration", "Исцеление"),
        ),
    )

    private fun token(value: String) = ClassToken(value)

    /**
     * The approved specialization name of [classToken]'s section
     * [sectionIndex] in [language], or `null` when the pair is not in the
     * approved table (unknown class, or a section index the table does not
     * cover). `null` is a "do not guess" answer, never an error.
     */
    fun displayNameOf(classToken: ClassToken, sectionIndex: Int, language: AppLanguage): String? =
        names[classToken]?.getOrNull(sectionIndex)?.let {
            when (language) {
                AppLanguage.ENGLISH -> it.english
                AppLanguage.RUSSIAN -> it.russian
            }
        }

    /**
     * The localized generic section label of a tab at [ordinal] (0-based),
     * for a section the approved table does not name: "Tree N" in English,
     * "Ветка N" in Russian.
     */
    fun genericSectionLabel(ordinal: Int, language: AppLanguage): String =
        when (language) {
            AppLanguage.ENGLISH -> "Tree ${ordinal + 1}"
            AppLanguage.RUSSIAN -> "Ветка ${ordinal + 1}"
        }
}

/**
 * The display label of one tree section (M5.REL.1), the single resolution
 * chain every editor surface (portrait tabs, landscape tabs, accessibility,
 * the Back chip) reads:
 *
 * 1. the dataset's own published section [datasetName], when it has one —
 *    the dataset stays the source of truth;
 * 2. otherwise the approved specialization name of
 *    ([classToken], [sectionIndex]) in [language];
 * 3. otherwise the localized generic label ([TalentSpecializationNames.genericSectionLabel]).
 *
 * Presentation only: the caller's section identity ([sectionIndex]) is an
 * input, never an output, and nothing here reaches a build.
 */
fun talentSectionDisplayLabel(
    datasetName: String?,
    classToken: ClassToken,
    sectionIndex: Int,
    ordinal: Int,
    language: AppLanguage,
): String =
    datasetName?.takeIf { it.isNotBlank() }
        ?: TalentSpecializationNames.displayNameOf(classToken, sectionIndex, language)
        ?: TalentSpecializationNames.genericSectionLabel(ordinal, language)
