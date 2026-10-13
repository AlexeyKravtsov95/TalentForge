package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.appLanguageOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M5.REL.1: the approved specialization mapping, in full, both languages —
 * every one of the nine Forever classes × three section indices, exactly as
 * verified against each section's node membership on the bundled datasets —
 * plus the localized generic fallback for a pair the table does not name,
 * and the device-language rule (Russian only for a Russian primary
 * language; English for everything else).
 */
class TalentSpecializationNamesTest {

    /** The approved table of the milestone: class → section index → (EN, RU). */
    private val approved: Map<String, List<Pair<String, String>>> = mapOf(
        "WARRIOR" to listOf("Arms" to "Оружие", "Fury" to "Неистовство", "Protection" to "Защита"),
        "PALADIN" to listOf("Holy" to "Свет", "Protection" to "Защита", "Retribution" to "Воздаяние"),
        "HUNTER" to listOf(
            "Beast Mastery" to "Повелитель зверей",
            "Marksmanship" to "Стрельба",
            "Survival" to "Выживание",
        ),
        "ROGUE" to listOf("Assassination" to "Ликвидация", "Combat" to "Бой", "Subtlety" to "Скрытность"),
        "PRIEST" to listOf("Discipline" to "Послушание", "Holy" to "Свет", "Shadow" to "Тьма"),
        "SHAMAN" to listOf("Elemental" to "Стихии", "Enhancement" to "Совершенствование", "Restoration" to "Исцеление"),
        "MAGE" to listOf("Arcane" to "Тайная магия", "Fire" to "Огонь", "Frost" to "Лёд"),
        "WARLOCK" to listOf("Affliction" to "Колдовство", "Demonology" to "Демонология", "Destruction" to "Разрушение"),
        "DRUID" to listOf("Balance" to "Баланс", "Feral Combat" to "Сила зверя", "Restoration" to "Исцеление"),
    )

    @Test
    fun allTwentySevenMappingsResolveExactlyInBothLanguages() {
        for ((token, sections) in approved) {
            val classToken = ClassToken(token)
            assertEquals(3, sections.size, "$token publishes three sections")
            for (index in sections.indices) {
                val (english, russian) = sections[index]
                assertEquals(
                    english,
                    TalentSpecializationNames.displayNameOf(classToken, index, AppLanguage.ENGLISH),
                    "$token section $index, English",
                )
                assertEquals(
                    russian,
                    TalentSpecializationNames.displayNameOf(classToken, index, AppLanguage.RUSSIAN),
                    "$token section $index, Russian",
                )
            }
        }
    }

    @Test
    fun anUnknownClassOrSectionIsNeverGuessed() {
        assertNull(TalentSpecializationNames.displayNameOf(ClassToken("DEATHKNIGHT"), 0, AppLanguage.ENGLISH))
        assertNull(TalentSpecializationNames.displayNameOf(ClassToken("WARRIOR"), 3, AppLanguage.ENGLISH))
        assertNull(TalentSpecializationNames.displayNameOf(ClassToken("WARRIOR"), -1, AppLanguage.RUSSIAN))
        // resolution is deterministic: the same pair always answers the same
        repeat(3) {
            assertNull(TalentSpecializationNames.displayNameOf(ClassToken("MONK"), 1, AppLanguage.RUSSIAN))
        }
    }

    @Test
    fun theDisplayLabelChainFallsBackToTheLocalizedGenericLabel() {
        val warrior = ClassToken("WARRIOR")
        val unknown = ClassToken("DEATHKNIGHT")
        // no dataset name: the approved name, else the localized generic
        assertEquals("Arms", talentSectionDisplayLabel(null, warrior, 0, 0, AppLanguage.ENGLISH))
        assertEquals("Оружие", talentSectionDisplayLabel(null, warrior, 0, 0, AppLanguage.RUSSIAN))
        assertEquals("Tree 4", talentSectionDisplayLabel(null, unknown, 9, 3, AppLanguage.ENGLISH))
        assertEquals("Ветка 2", talentSectionDisplayLabel(null, unknown, 1, 1, AppLanguage.RUSSIAN))
        // a dataset-published section name wins over the approved mapping
        assertEquals("Named", talentSectionDisplayLabel("Named", warrior, 0, 0, AppLanguage.RUSSIAN))
        // ... but a blank one does not
        assertEquals("Fury", talentSectionDisplayLabel("   ", warrior, 1, 1, AppLanguage.ENGLISH))
    }

    @Test
    fun russianOnlyForARussianPrimaryLanguage() {
        val russian = listOf("ru", "RU", "ru-RU", "ru-KZ", "ru-Latn", "ru_ru")
        val other = listOf("en", "en-US", "en-GB", "de-DE", "fr", "fr-CA", "be", "uk-UA", "es-419", "", "-", "  ")
        for (tag in russian) {
            assertEquals(AppLanguage.RUSSIAN, appLanguageOf(tag), "'$tag' is a Russian primary language")
        }
        for (tag in other) {
            assertEquals(AppLanguage.ENGLISH, appLanguageOf(tag), "'$tag' presents in English")
        }
    }
}
