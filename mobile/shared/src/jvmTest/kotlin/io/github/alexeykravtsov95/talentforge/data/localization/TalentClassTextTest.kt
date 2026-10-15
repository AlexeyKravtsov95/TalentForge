package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipRankStatus
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipSegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

/**
 * The Kotlin port of `tests/unit/localization/test_display.py` — the English-fallback consumer
 * rule of `talent-localization.v1` (docs/talent-localization.md §5) on the committed overlays
 * (the 3a pilot build 70338 as in Python, and the product build) and on overlays that must
 * not be applied at all.
 */
class TalentClassTextTest {

    private val pilot = LocalizationCase("1.60.1.70338", "warrior")
    private val product = LocalizationCase(PRODUCT_BUILD, "warrior")

    private fun englishNames(document: TalentDataDocument): Map<Int, String?> =
        document.trees.flatMap { it.nodes }.flatMap { it.entries }.associate { it.id to it.name }

    private suspend fun LocalizationCase.resolved(
        overlay: LocalizationOutcome<TalentLocalization>? = null,
    ): TalentClassText = resolveClassTalentText(document, tooltips(), overlay)

    @Test
    fun withoutAnOverlayEverythingIsTheEnglishContent() = runTest {
        val tooltips = pilot.tooltips()
        val text = pilot.resolved(overlay = null)
        assertNull(text.overlayError)
        assertEquals(englishNames(pilot.document), text.entries.mapValues { it.value.name })
        for (entry in text.entries.values) {
            assertEquals(null to null, entry.nameLocale to entry.descriptionLocale)
            assertEquals(tooltips.ranksOf(entry.entryId), entry.englishRanks)
            assertNull(entry.localizedRanks)
        }
    }

    @Test
    fun thePilotShowsRussianWhereProvenAndEnglishElsewhere() = runTest {
        val text = pilot.resolved(pilot.load())
        assertNull(text.overlayError)
        assertEquals(englishNames(pilot.document).keys, text.entries.keys) // every entry, keyed by entry id

        val heroic = text.entries.getValue(Warrior.IMPROVED_HEROIC_STRIKE)
        assertEquals("Улучшенный удар героя" to TalentLocale.RU_RU, heroic.name to heroic.nameLocale)
        assertEquals(TalentLocale.RU_RU, heroic.descriptionLocale)
        assertNull(heroic.englishRanks)
        assertEquals(listOf(1, 2, 3), assertNotNull(heroic.localizedRanks).map { it.rank })

        val gore = text.entries.getValue(Warrior.GORE_DRINKER)
        assertEquals("Gore Drinker" to null, gore.name to gore.nameLocale)
    }

    @Test
    fun anUnrenderedDescriptionIsTheEnglishSidecarUnchanged() = runTest {
        // partial status, gaps and missing-value markers come from English, never mixed;
        // the name is still translated: a Russian name over an English description
        val tooltips = pilot.tooltips()
        for (entryId in listOf(Warrior.BLOODTHIRST, Warrior.FLURRY, Warrior.IMPROVED_SHIELD_WALL)) {
            val entry = pilot.resolved(pilot.load()).entries.getValue(entryId)
            assertEquals(TalentLocale.RU_RU, entry.nameLocale, "$entryId name")
            assertEquals(null to null, entry.descriptionLocale to entry.localizedRanks, "$entryId description")
            assertEquals(tooltips.ranksOf(entryId), entry.englishRanks)
        }
    }

    @Test
    fun theFallbackStatusesAreTheDocumentedGates() = runTest {
        val loaded = product.loaded()
        assertEquals(LocalizedDescriptionStatus.SOURCE_NOT_RESOLVED, loaded.entryOf(Warrior.BLOODTHIRST)!!.description.status)
        assertEquals(LocalizedDescriptionStatus.PLURAL_RULE_UNVERIFIED, loaded.entryOf(Warrior.FLURRY)!!.description.status)
        assertEquals(
            LocalizedDescriptionStatus.DECIMAL_FORMAT_UNVERIFIED,
            loaded.entryOf(Warrior.IMPROVED_SHIELD_WALL)!!.description.status,
        )
    }

    @Test
    fun bloodthirstKeepsItsEnglishMissingValueMarkers() = runTest {
        val entry = pilot.resolved(pilot.load()).entries.getValue(Warrior.BLOODTHIRST)
        val ranks = assertNotNull(entry.englishRanks)
        assertNotEquals(setOf(TooltipRankStatus.RESOLVED), ranks.map { it.status }.toSet())
        assertTrue(ranks.any { rank -> rank.gaps.isNotEmpty() && rank.segments.any { it.kind == TooltipSegmentKind.GAP } })
    }

    @Test
    fun aMismatchedOverlayIsNotAppliedAtAll() = runTest {
        val json = pilot.json()
        val mutations: Map<String, JsonObject> = mapOf(
            "dataset revision" to json.with("talentDataContentHash", "sha256:" + "0".repeat(64)),
            "tooltips revision" to json.with("tooltipsContentHash", "sha256:" + "1".repeat(64)),
            "client build" to json.with("clientBuild", "1.60.1.70245"),
            "class" to json.with("classToken", "MAGE"),
            "duplicate entry" to json.withEntries { it + it.first() },
            "missing entry" to json.withEntries { it.drop(1) },
            "spell identity" to json.withEntries { listOf(it.first().with("spellId", 1)) + it.drop(1) },
        )
        val names = englishNames(pilot.document)
        for ((label, mutated) in mutations) {
            val text = pilot.resolved(pilot.loadJson(mutated.rehashed()))
            assertNotNull(text.overlayError, label)
            assertEquals(names, text.entries.mapValues { it.value.name }, label)
            assertTrue(text.entries.values.all { it.localizedRanks == null && it.nameLocale == null }, label)
        }
    }

    @Test
    fun aTamperedOverlayWithoutANewHashIsNotApplied() = runTest {
        val tampered = pilot.json().withEntry(Warrior.IMPROVED_HEROIC_STRIKE) {
            it.with("name", it.obj("name").with("text", "Подделка"))
        }
        val text = pilot.resolved(pilot.loadJson(tampered))
        assertIs<LocalizationLoadError.ContentHashMismatch>(text.overlayError)
        assertTrue("Подделка" !in text.entries.values.map { it.name })
        assertTrue(text.entries.values.none { it.nameLocale != null })
    }

    @Test
    fun aMissingOverlayShowsEnglishWithTheTypedReason() = runTest {
        val text = product.resolved(product.loadBytes(null))
        assertEquals(LocalizationLoadError.AssetMissing(product.path), text.overlayError)
        assertEquals(englishNames(product.document), text.entries.mapValues { it.value.name })
    }

    @Test
    fun inconsistentEnglishTooltipsAreAnErrorNotAFallback() = runTest {
        val mage = LocalizationCase("1.60.1.70338", "mage")
        assertFailsWith<IllegalArgumentException> { resolveClassTalentText(pilot.document, mage.tooltips(), null) }
    }

    @Test
    fun anOverlayVerifiedForAnotherDocumentIsAnError() = runTest {
        assertFailsWith<IllegalArgumentException> {
            resolveClassTalentText(pilot.document, pilot.tooltips(), product.load())
        }
    }

    @Test
    fun entriesSharingASpellResolveByTheirOwnIdentity() = runTest {
        // DUPLICATE_SPELL_IDENTITY: two entries, two display texts, both by entryId
        for ((token, entryIds) in listOf("hunter" to listOf(129646, 137851), "priest" to listOf(130594, 137847))) {
            val case = LocalizationCase(PRODUCT_BUILD, token)
            val text = case.resolved(case.load())
            assertNull(text.overlayError)
            val resolved = entryIds.map { text.entries.getValue(it) }
            assertEquals(entryIds, resolved.map { it.entryId })
            assertEquals(setOf(TalentLocale.RU_RU), resolved.map { it.nameLocale }.toSet())
        }
    }

    @Test
    fun localizedRanksCarryTheEnglishNumbers() = runTest {
        val tooltips = pilot.tooltips()
        val heroic = pilot.resolved(pilot.load()).entries.getValue(Warrior.IMPROVED_HEROIC_STRIKE)
        val ranks = assertNotNull(heroic.localizedRanks)
        for (mine in ranks) {
            val theirs = tooltips.rankOf(Warrior.IMPROVED_HEROIC_STRIKE, mine.rank)!!
            assertEquals(
                theirs.segments.filter { it.kind == TooltipSegmentKind.VALUE }.map { it.text },
                mine.segments.filter { it.kind == LocalizedSegmentKind.VALUE }.map { it.text },
            )
            assertNotEquals(theirs.segments.joinToString("") { it.text }, mine.segments.joinToString("") { it.text })
        }
    }

    @Test
    fun aDatasetWithoutANameKeepsNoNameUnlessTheOverlayTranslatesIt() = runTest {
        // the UI then shows its localized "Talent <id>" fallback; a translated overlay name wins
        val document = product.document
        val nameless = document.copy(
            trees = document.trees.map { tree ->
                tree.copy(
                    nodes = tree.nodes.map { node ->
                        node.copy(
                            entries = node.entries.map { entry ->
                                if (entry.id == Warrior.GORE_DRINKER || entry.id == Warrior.IMPROVED_HEROIC_STRIKE) {
                                    entry.copy(name = null)
                                } else {
                                    entry
                                }
                            },
                        )
                    },
                )
            },
        )
        val text = resolveClassTalentText(nameless, product.tooltips(), product.load())
        assertEquals(null to null, text.entries.getValue(Warrior.GORE_DRINKER).let { it.name to it.nameLocale })
        assertEquals("Улучшенный удар героя", text.entries.getValue(Warrior.IMPROVED_HEROIC_STRIKE).name)
    }

    @Test
    fun everyBuildResolvesEveryEntryOfEveryClass() = runTest {
        for (build in LOCALIZED_BUILDS) {
            for (token in io.github.alexeykravtsov95.talentforge.data.TestRepoAssets.classTokens) {
                val case = LocalizationCase(build, token)
                val text = case.resolved(case.load())
                assertNull(text.overlayError, "$build $token")
                for (entry in text.entries.values) {
                    assertTrue((entry.englishRanks == null) != (entry.localizedRanks == null), "$build $token ${entry.entryId}")
                    assertEquals(entry.localizedRanks != null, entry.descriptionLocale != null)
                }
                assertTrue(text.entries.values.any { it.nameLocale == TalentLocale.RU_RU }, "$build $token: no Russian name")
            }
        }
    }
}
