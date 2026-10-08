package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The inspector's description block, built from the REAL bundled
 * `talent-tooltips.v1` resources through the production loader (packaged
 * Compose resources, no network).
 */
class TalentInspectorDescriptionTest {

    private class Loaded(val document: TalentDataDocument, val descriptions: TalentNodeDescriptions.Loaded) {
        fun nodeOf(name: String) = document.trees.single().nodes.single { it.name == name }
    }

    private fun load(classToken: String): Loaded = runBlocking {
        val assets = ComposeResourceDatasetAssetSource()
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(GameId("forever"), "1.60.1.70009", ClassToken(classToken))),
        ).value
        val load = TalentDescriptionsLoader(assets).load(document, document.trees.single())
        assertNull(load.error, "$classToken descriptions load")
        Loaded(document, assertIs<TalentNodeDescriptions.Loaded>(load.descriptions))
    }

    private fun Loaded.describe(name: String, rank: Int): TalentInspectorDescription {
        val node = nodeOf(name)
        return assertNotNull(buildTalentInspectorDescription(descriptions, node.id, rank, node.maxRank))
    }

    private fun TalentInspectorDescription.text(): String = (body as DescriptionBody.Text).plainText

    private val warrior by lazy { load("WARRIOR") }

    @Test
    fun rankZeroShowsRankOneAsTheProspectiveEffect() {
        val description = warrior.describe("Improved Rend", 0)
        assertTrue(description.prospective)
        assertEquals("Increases the Bleed damage done by your Rend ability by 12%.", description.text())
        assertEquals(RankComparison.Value(current = null, next = "12%"), description.comparison)
    }

    @Test
    fun partialRankShowsTheCurrentDescriptionAndTheNextValue() {
        val one = warrior.describe("Improved Rend", 1)
        assertEquals("Increases the Bleed damage done by your Rend ability by 12%.", one.text())
        assertEquals(RankComparison.Value(current = "12%", next = "23%"), one.comparison)

        val two = warrior.describe("Improved Rend", 2)
        assertEquals("Increases the Bleed damage done by your Rend ability by 23%.", two.text())
        assertEquals(RankComparison.Value(current = "23%", next = "35%"), two.comparison)
    }

    @Test
    fun maxRankHasNoNextRankSection() {
        val max = warrior.describe("Improved Rend", 3)
        assertEquals("Increases the Bleed damage done by your Rend ability by 35%.", max.text())
        assertNull(max.comparison)
        assertNull(warrior.describe("Mortal Strike", 1).comparison)
    }

    @Test
    fun singleRankTalentAtRankZeroHasNoComparisonColumns() {
        val strike = warrior.describe("Mortal Strike", 0)
        assertTrue(strike.prospective)
        assertNull(strike.comparison)
        assertTrue(strike.text().startsWith("A vicious strike that deals weapon damage plus 85"))
    }

    @Test
    fun severalChangingValuesShowTheNextRankTextWithTheChangesMarked() {
        val weaponmaster = warrior.describe("Weaponmaster", 1)
        val next = assertIs<RankComparison.NextRankText>(weaponmaster.comparison)
        val changed = next.body.spans.filterIsInstance<DescriptionSpan.Value>().filter { it.changed }
        assertEquals(listOf("2%", "6%", "2%"), changed.map { it.text })
        assertTrue(next.body.plainText.contains("\n\nAxe/Polearm: Increases your\ncritical strike chance by 2%."))
    }

    @Test
    fun pluralWordsFollowTheirNumberAcrossRanks() {
        val rogue = load("ROGUE")
        val expose = rogue.describe("Improved Expose Armor", 1)
        assertTrue(expose.text().contains("refunds 1 Combo Point when"))
        val next = assertIs<RankComparison.NextRankText>(expose.comparison)
        assertTrue(next.body.plainText.contains("refunds 2 Combo Points when"))
    }

    @Test
    fun partialDescriptionMarksTheMissingValueAndExplainsIt() {
        val bloodthirst = warrior.describe("Bloodthirst", 0)
        val body = assertIs<DescriptionBody.Text>(bloodthirst.body)
        assertTrue(DescriptionSpan.Missing in body.spans)
        assertEquals(MISSING_NOTE_CHARACTER, body.missingNote)
        assertTrue(body.plainText.contains("Attack Power plus $MISSING_MARK and increasing"))
    }

    @Test
    fun unavailableDescriptionsUseTheNeutralFallback() {
        val node = warrior.nodeOf("Improved Rend")
        val unavailable = assertNotNull(
            buildTalentInspectorDescription(TalentNodeDescriptions.Unavailable, node.id, 1, node.maxRank),
        )
        assertEquals(DescriptionBody.Unavailable, unavailable.body)
        assertEquals("Talent description unavailable for this client data.", DescriptionBody.Unavailable.TEXT)
        assertNull(unavailable.comparison)
        // a node the sidecar has nothing for is also unavailable, never guessed
        val missing = assertNotNull(
            buildTalentInspectorDescription(TalentNodeDescriptions.Loaded(emptyMap()), node.id, 1, node.maxRank),
        )
        assertEquals(DescriptionBody.Unavailable, missing.body)
        // no descriptions provided at all: no description section
        assertNull(buildTalentInspectorDescription(TalentNodeDescriptions.None, node.id, 1, node.maxRank))
    }

    @Test
    fun rawTemplateSyntaxNeverReachesTheInspectorInAnyClassOrRank() {
        var described = 0
        for (token in listOf("WARRIOR", "PALADIN", "HUNTER", "ROGUE", "PRIEST", "SHAMAN", "MAGE", "WARLOCK", "DRUID")) {
            val loaded = load(token)
            for (node in loaded.document.trees.single().nodes) {
                for (rank in 0..node.maxRank) {
                    val description = assertNotNull(
                        buildTalentInspectorDescription(loaded.descriptions, node.id, rank, node.maxRank),
                    )
                    val texts = buildList {
                        (description.body as? DescriptionBody.Text)?.let { add(it.plainText) }
                        when (val comparison = description.comparison) {
                            is RankComparison.Value -> addAll(listOfNotNull(comparison.current, comparison.next))
                            is RankComparison.NextRankText -> add(comparison.body.plainText)
                            null -> Unit
                        }
                    }
                    for (text in texts) {
                        assertTrue('$' !in text && '|' !in text, "$token ${node.name} rank $rank: $text")
                    }
                    described += 1
                }
            }
        }
        // every rank 0..maxRank of every node of the nine trees
        assertTrue(described > 1333)
    }

    @Test
    fun mageRogueAndDruidDescriptionsAreReal() {
        assertEquals(
            "Reduces the casting time of your Frostbolt spell by 0.1 sec.",
            load("MAGE").describe("Improved Frostbolt", 1).text(),
        )
        assertTrue(
            load("ROGUE").describe("Master of Deception", 3).text()
                .endsWith("as if you were 3 levels higher."),
        )
        assertTrue(load("DRUID").describe("Feral Charge", 0).text().endsWith("Leap behind an enemy."))
    }
}
