package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.tooltips.TalentTooltipRank
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.tree.TalentNodeDescriptions
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M5.DATA / M5.DATA.2 / M5.DATA.3 / M5.DATA.4: five datasets bundled side by
 * side. New drafts are created on the product dataset ([PRODUCT_BUNDLE],
 * 1.60.1.70450); a build saved on the reference baseline ([REFERENCE_BUNDLE],
 * 1.60.1.70009), on [BUNDLE_70245], on [BUNDLE_70338] (the same talent rows as
 * 70374) or on the previous product dataset ([PREVIOUS_PRODUCT_BUNDLE],
 * 1.60.1.70374) opens on its own build — same tree, ranks, points, icons and
 * descriptions — and is never re-read against the product dataset; a build of
 * a client build that is not bundled fails with a typed error instead of
 * falling back.
 *
 * Every bundled read goes through [RecordingAssetSource], so each test can
 * prove WHICH build's files answered.
 */
class DatasetVersionCompatibilityTest {

    private val game = GameId("forever")

    private lateinit var assets: RecordingAssetSource
    private lateinit var datasets: BundledTalentDatasetRepository
    private lateinit var storage: FakeBuildLibraryStorage
    private lateinit var repository: LocalBuildRepository

    /** Delegates to the real Compose resources and records every path read. */
    private class RecordingAssetSource(
        private val delegate: DatasetAssetSource = ComposeResourceDatasetAssetSource(),
    ) : DatasetAssetSource {
        val paths = mutableListOf<String>()

        override suspend fun read(path: String): ByteArray {
            paths += path
            return delegate.read(path)
        }
    }

    @BeforeTest
    fun setUp() {
        assets = RecordingAssetSource()
        datasets = BundledTalentDatasetRepository(assets)
        storage = FakeBuildLibraryStorage()
        repository = LocalBuildRepository(storage, FixedClock(), SequentialIds())
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun document(clientBuild: String, token: String): TalentDataDocument = runBlocking {
        assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            datasets.document(DatasetId(game, clientBuild, ClassToken(token))),
        ).value
    }

    private fun open(localId: String): EditorLoad = runBlocking {
        loadEditorSession(
            repository,
            datasets,
            localId,
            icons = TalentIconsLoader(assets),
            descriptions = TalentDescriptionsLoader(assets),
        )
    }

    private fun ranksOf(session: EditorSession, nodeId: Int): List<TalentTooltipRank> =
        assertNotNull(assertIs<TalentNodeDescriptions.Loaded>(session.descriptions).ranksOf(nodeId))

    private fun TalentTooltipRank.text(): String = segments.joinToString("") { it.text }

    /**
     * A real build with [target] at its max rank, reached by spending the
     * lowest-id allowed talent of the target's own section until the target
     * opens — spent through the engine, never hand-written.
     */
    private fun maxedThroughItsSection(engine: TalentAllocationEngine, target: Int): BuildState {
        val node = engine.tree.nodes.single { it.id == target }
        var state = engine.emptyState()
        while (!engine.canSpend(state, target).allowed) {
            val next = engine.tree.nodes
                .filter { it.sectionIndex == node.sectionIndex && it.id != target }
                .filter { engine.canSpend(state, it.id).allowed }
                .minOf { it.id }
            state = engine.spend(state, next).state
        }
        repeat(node.maxRank) { state = engine.spend(state, target).state }
        assertEquals(node.maxRank, state.rankOf(target))
        assertTrue(engine.validate(state).valid)
        return state
    }

    /**
     * A real 1.60.1.70009 Druid build with node 104951 (King of the Jungle on
     * 70009) at 3/3. On 1.60.1.70245 and every later build the same node id is
     * Shifting Power with max rank 1, so this build only exists on its own build.
     */
    private fun referenceDruidWithShiftingPowerMaxed(engine: TalentAllocationEngine): BuildState =
        maxedThroughItsSection(engine, SHIFTING_POWER).also { assertEquals(3, it.rankOf(SHIFTING_POWER)) }

    /** Saves [state] as a draft of [clientBuild] / [classToken] and returns its local id. */
    private fun saved(state: BuildState, clientBuild: String, classToken: String): String =
        loaded(repository.createDraft(buildFileOf(state, game, clientBuild, ClassToken(classToken)))).localId

    private fun Iterable<String>.allIn(clientBuild: String): Boolean = all { it.contains("/$clientBuild/") }

    // ------------------------------------------------------------------ product dataset

    @Test
    fun newBuildListsTheProductDatasetByItsExactClientBuild() {
        val choices = assertNotNull(runBlocking { loadNewBuildChoices(datasets) })

        assertEquals("Forever", choices.gameDisplayName)
        // the header's human version, derived from the bundle's exact client
        // build (1.60.1.70450 → 1.60.1)
        assertEquals("1.60.1", choices.dataVersion)
        assertEquals(9, choices.classes.size)
        assertTrue(choices.classes.all { it.available })
        assertTrue(assets.paths.allIn(PRODUCT_BUNDLE.clientBuild), "read: ${assets.paths}")
    }

    @Test
    fun aNewDraftIsCreatedAndOpensOnTheProductDataset() {
        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("DRUID")) },
        ).record
        assertEquals("1.60.1.70450", created.build.clientBuild)
        assertEquals(PRODUCT_BUNDLE.clientBuild, created.build.clientBuild)
        assertEquals(1089, created.build.treeId)

        val session = assertIs<EditorLoad.Ready>(open(created.localId)).session

        assertEquals("1.60.1.70450", session.engine.document.clientBuild)
        assertEquals(52, session.engine.tree.nodes.size)
        val improved = session.engine.tree.nodes.single { it.id == IMPROVED_SHIFTING_POWER }
        assertEquals("Improved Shifting Power", improved.name)
        val newNode = session.engine.tree.nodes.single { it.id == SHIFTING_POWER }
        assertEquals("Shifting Power", newNode.name)
        assertEquals(1, newNode.maxRank)
        // the product build's own icon and description of its new talent
        assertTrue(IMPROVED_SHIFTING_POWER in session.icons.nodeIds)
        assertEquals(
            listOf(
                "Reduces the cooldown of your Shifting Power spell by 4 sec.",
                "Reduces the cooldown of your Shifting Power spell by 8 sec.",
            ),
            ranksOf(session, IMPROVED_SHIFTING_POWER).map { it.text() },
        )
        // dataset, visuals and descriptions all came from 1.60.1.70450
        assertTrue(assets.paths.any { it.startsWith("visuals/forever/1.60.1.70450/") })
        assertTrue(assets.paths.any { it.startsWith("tooltips/forever/1.60.1.70450/") })
        assertTrue(assets.paths.allIn(PRODUCT_BUNDLE.clientBuild), "read: ${assets.paths}")
    }

    @Test
    fun aNewWarriorDraftIsCreatedOnTheReworkedTree() {
        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("WARRIOR")) },
        ).record
        assertEquals("1.60.1.70450", created.build.clientBuild)
        assertEquals(1117, created.build.treeId)

        val session = assertIs<EditorLoad.Ready>(open(created.localId)).session

        val nodes = session.engine.tree.nodes.associateBy { it.id }
        assertEquals(52, nodes.size)
        assertFalse(TOUGHNESS in nodes, "Toughness is gone since 1.60.1.70338")
        assertEquals("Gore Drinker", nodes.getValue(GORE_DRINKER).name)
        assertEquals(listOf(ENRAGE), nodes.getValue(GORE_DRINKER).prerequisites.map { it.nodeId })
        assertEquals("Iron Will", nodes.getValue(IRON_WILL).name)
        assertEquals("Furious Precision", nodes.getValue(BOUNDLESS_RAGE_NODE).name)
        assertEquals("Lingering Rage", nodes.getValue(IRON_WILL_NODE).name)
        // the product build's own icon and description of its new talent
        assertTrue(GORE_DRINKER in session.icons.nodeIds)
        assertEquals(
            "Your Enrage, Berserker Rage, Bloodrage, Death Wish, and Bloodthirst abilities cause " +
                "your next 3 melee attacks to restore 1.0% of your maximum Health.",
            ranksOf(session, GORE_DRINKER)[1].text(),
        )
        assertEquals(
            "Increases your chance to hit with off-hand attacks by 10%.",
            ranksOf(session, BOUNDLESS_RAGE_NODE)[2].text(),
        )
        assertTrue(assets.paths.allIn(PRODUCT_BUNDLE.clientBuild), "read: ${assets.paths}")
    }

    @Test
    fun aNewMageDraftOpensOnTheProductTreeThatPublishesNoSections() {
        // 1.60.1.70450: Arcane Wrath belongs to no gate group, so the Mage tree has no
        // derivable tabs (docs/forever-dataset.md); the draft still opens, whole and exact
        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("MAGE")) },
        ).record
        assertEquals("1.60.1.70450", created.build.clientBuild)
        assertEquals(1112, created.build.treeId)

        val session = assertIs<EditorLoad.Ready>(open(created.localId)).session

        val tree = session.engine.tree
        assertEquals(55, tree.nodes.size)
        assertTrue(tree.sections.isEmpty())
        assertTrue(tree.nodes.all { it.sectionIndex == null })
        val wrath = tree.nodes.single { it.id == ARCANE_WRATH }
        assertEquals("Arcane Wrath", wrath.name)
        assertTrue(wrath.unlockRequirements.isEmpty())
        assertEquals(listOf(ARCANE_BLAST), wrath.prerequisites.map { it.nodeId })
        assertFalse(session.engine.canSpend(session.buildState, ARCANE_WRATH).allowed)
        assertTrue(ARCANE_WRATH in session.icons.nodeIds)
        assertTrue(assets.paths.allIn(PRODUCT_BUNDLE.clientBuild), "read: ${assets.paths}")
    }

    // ------------------------------------------------------------------ saved reference builds

    @Test
    fun aBuildSavedOnTheReferenceBaselineOpensOnItsOwnBuildUnchanged() {
        val referenceEngine = TalentAllocationEngine(document(REFERENCE_BUNDLE.clientBuild, "DRUID"))
        val state = referenceDruidWithShiftingPowerMaxed(referenceEngine)
        val file = buildFileOf(state, game, "1.60.1.70009", ClassToken("DRUID"))
        val record = loaded(repository.createDraft(file))
        assets.paths.clear()

        val session = assertIs<EditorLoad.Ready>(open(record.localId)).session

        // exact identity, ranks and points: nothing migrated, re-mapped or recounted
        assertEquals(file, session.record.build)
        assertEquals(state, session.buildState)
        assertEquals(referenceEngine.spentPoints(state), session.engine.spentPoints(session.buildState))
        assertEquals("1.60.1.70009", session.engine.document.clientBuild)
        assertEquals(51, session.engine.tree.nodes.size)
        assertFalse(session.engine.tree.nodes.any { it.id == IMPROVED_SHIFTING_POWER })
        val oldNode = session.engine.tree.nodes.single { it.id == SHIFTING_POWER }
        assertEquals("King of the Jungle", oldNode.name)
        assertEquals(3, oldNode.maxRank)
        // the reference build's own icon and description
        assertTrue(SHIFTING_POWER in session.icons.nodeIds)
        assertEquals(
            "Tiger's Fury now instantly grants you 60 Energy.",
            ranksOf(session, SHIFTING_POWER)[2].text(),
        )
        assertTrue(assets.paths.any { it.startsWith("visuals/forever/1.60.1.70009/") })
        assertTrue(assets.paths.any { it.startsWith("tooltips/forever/1.60.1.70009/") })
        assertTrue(assets.paths.allIn(REFERENCE_BUNDLE.clientBuild), "read: ${assets.paths}")
        // opening wrote nothing but lastOpenedAt
        assertEquals(file, loaded(repository.load()).single().build)

        // why it must open on 1.60.1.70009: the product dataset rejects the same ranks
        val productEngine = TalentAllocationEngine(document(PRODUCT_BUNDLE.clientBuild, "DRUID"))
        assertFalse(productEngine.validate(state).valid)
    }

    // ------------------------------------------------------------------ saved 1.60.1.70245 builds

    @Test
    fun aBuildSavedOn70245OpensOnItsOwnBuildUnchanged() {
        // Warrior Toughness 5/5: a valid 1.60.1.70245 build whose node no longer exists since 70338
        val previousEngine = TalentAllocationEngine(document(BUNDLE_70245.clientBuild, "WARRIOR"))
        val state = maxedThroughItsSection(previousEngine, TOUGHNESS)
        val localId = saved(state, "1.60.1.70245", "WARRIOR")
        val file = loaded(repository.load()).single().build
        assets.paths.clear()

        val session = assertIs<EditorLoad.Ready>(open(localId)).session

        // exact identity, ranks and points: nothing migrated, re-mapped or recounted
        assertEquals(file, session.record.build)
        assertEquals(state, session.buildState)
        assertEquals(previousEngine.spentPoints(state), session.engine.spentPoints(session.buildState))
        assertEquals("1.60.1.70245", session.engine.document.clientBuild)
        val nodes = session.engine.tree.nodes.associateBy { it.id }
        assertEquals(53, nodes.size)
        assertFalse(GORE_DRINKER in nodes)
        assertEquals("Toughness", nodes.getValue(TOUGHNESS).name)
        assertEquals(5, session.buildState.rankOf(TOUGHNESS))
        // the previous product build's own icon and description
        assertTrue(TOUGHNESS in session.icons.nodeIds)
        assertEquals("Increases your Armor value from items by 10%.", ranksOf(session, TOUGHNESS)[4].text())
        assertTrue(assets.paths.any { it.startsWith("visuals/forever/1.60.1.70245/") })
        assertTrue(assets.paths.any { it.startsWith("tooltips/forever/1.60.1.70245/") })
        assertTrue(assets.paths.allIn(BUNDLE_70245.clientBuild), "read: ${assets.paths}")
        assertEquals(file, loaded(repository.load()).single().build)

        // why it must open on 1.60.1.70245: the product dataset rejects the same ranks
        val productEngine = TalentAllocationEngine(document(PRODUCT_BUNDLE.clientBuild, "WARRIOR"))
        assertFalse(productEngine.validate(state).valid)
    }

    @Test
    fun a70245BuildThatStaysValidStillOpensWithItsOwnTalents() {
        // node 105953 at 3/3 validates on BOTH builds, but it is Boundless Rage on
        // 70245 and Furious Precision since 70338: validity is no license to migrate
        val previousEngine = TalentAllocationEngine(document(BUNDLE_70245.clientBuild, "WARRIOR"))
        val state = maxedThroughItsSection(previousEngine, BOUNDLESS_RAGE_NODE)
        val productEngine = TalentAllocationEngine(document(PRODUCT_BUNDLE.clientBuild, "WARRIOR"))
        assertTrue(productEngine.validate(state).valid)
        val localId = saved(state, BUNDLE_70245.clientBuild, "WARRIOR")
        assets.paths.clear()

        val session = assertIs<EditorLoad.Ready>(open(localId)).session

        assertEquals("1.60.1.70245", session.engine.document.clientBuild)
        assertEquals(state, session.buildState)
        assertEquals("Boundless Rage", session.engine.tree.nodes.single { it.id == BOUNDLESS_RAGE_NODE }.name)
        assertEquals("Increases your maximum Rage by 30.", ranksOf(session, BOUNDLESS_RAGE_NODE)[2].text())
        assertTrue(assets.paths.allIn(BUNDLE_70245.clientBuild), "read: ${assets.paths}")
    }

    // ------------------------------------------------------------------ saved 1.60.1.70338 builds

    @Test
    fun aBuildSavedOn70338OpensOnItsOwnBuildAlthoughThe70374DataIsIdentical() {
        // Warrior Gore Drinker maxed on 1.60.1.70338. 70374 has the same talent rows, so its
        // engine accepts the same ranks - and the build still opens on 70338 only.
        val previousEngine = TalentAllocationEngine(document(BUNDLE_70338.clientBuild, "WARRIOR"))
        val state = maxedThroughItsSection(previousEngine, GORE_DRINKER)
        val productDocument = document(PREVIOUS_PRODUCT_BUNDLE.clientBuild, "WARRIOR")
        assertTrue(TalentAllocationEngine(productDocument).validate(state).valid)
        assertEquals(previousEngine.document.trees, productDocument.trees)
        val localId = saved(state, "1.60.1.70338", "WARRIOR")
        val file = loaded(repository.load()).single().build
        assets.paths.clear()

        val session = assertIs<EditorLoad.Ready>(open(localId)).session

        // exact identity, ranks and points: nothing migrated, re-mapped or recounted
        assertEquals(file, session.record.build)
        assertEquals("1.60.1.70338", session.record.build.clientBuild)
        assertEquals(state, session.buildState)
        assertEquals(previousEngine.spentPoints(state), session.engine.spentPoints(session.buildState))
        assertEquals("1.60.1.70338", session.engine.document.clientBuild)
        assertEquals(previousEngine.document.contentHash, session.engine.document.contentHash)
        assertTrue(session.engine.document.contentHash != productDocument.contentHash)
        // its own build's icon and description, never the product build's identical copy
        assertTrue(GORE_DRINKER in session.icons.nodeIds)
        assertTrue(assets.paths.any { it.startsWith("visuals/forever/1.60.1.70338/") })
        assertTrue(assets.paths.any { it.startsWith("tooltips/forever/1.60.1.70338/") })
        assertTrue(assets.paths.allIn(BUNDLE_70338.clientBuild), "read: ${assets.paths}")
        // opening wrote nothing but lastOpenedAt
        assertEquals(file, loaded(repository.load()).single().build)
    }

    // ------------------------------------------------------------------ saved previous-product builds

    @Test
    fun aBuildSavedOnThePreviousProductDatasetOpensOnItsOwnBuildUnchanged() {
        // Priest Improved Mind Blast 5/5: a valid 1.60.1.70374 build whose node is gone in 70450
        val previousEngine = TalentAllocationEngine(document(PREVIOUS_PRODUCT_BUNDLE.clientBuild, "PRIEST"))
        val state = maxedThroughItsSection(previousEngine, IMPROVED_MIND_BLAST_70374)
        val localId = saved(state, "1.60.1.70374", "PRIEST")
        val file = loaded(repository.load()).single().build
        assets.paths.clear()

        val session = assertIs<EditorLoad.Ready>(open(localId)).session

        // exact identity, ranks and points: nothing migrated, re-mapped or recounted
        assertEquals(file, session.record.build)
        assertEquals("1.60.1.70374", session.record.build.clientBuild)
        assertEquals(state, session.buildState)
        assertEquals(previousEngine.spentPoints(state), session.engine.spentPoints(session.buildState))
        assertEquals("1.60.1.70374", session.engine.document.clientBuild)
        assertEquals(previousEngine.document.contentHash, session.engine.document.contentHash)
        val removed = session.engine.tree.nodes.single { it.id == IMPROVED_MIND_BLAST_70374 }
        assertEquals("Improved Mind Blast", removed.name)
        assertEquals(5, removed.maxRank)
        // its own build's icon and description
        assertTrue(IMPROVED_MIND_BLAST_70374 in session.icons.nodeIds)
        assertEquals(
            "Reduces the cooldown of your Mind Blast spell by 2.5 sec.",
            ranksOf(session, IMPROVED_MIND_BLAST_70374)[4].text(),
        )
        assertTrue(assets.paths.any { it.startsWith("visuals/forever/1.60.1.70374/") })
        assertTrue(assets.paths.any { it.startsWith("tooltips/forever/1.60.1.70374/") })
        assertTrue(assets.paths.allIn(PREVIOUS_PRODUCT_BUNDLE.clientBuild), "read: ${assets.paths}")
        // opening wrote nothing but lastOpenedAt
        assertEquals(file, loaded(repository.load()).single().build)

        // why it must open on 1.60.1.70374: the product dataset rejects the same ranks
        val productEngine = TalentAllocationEngine(document(PRODUCT_BUNDLE.clientBuild, "PRIEST"))
        assertFalse(productEngine.validate(state).valid)
    }

    @Test
    fun buildCardsOfEveryBundledBuildResolveTheirOwnDatasets() {
        val referenceEngine = TalentAllocationEngine(document(REFERENCE_BUNDLE.clientBuild, "DRUID"))
        val state = referenceDruidWithShiftingPowerMaxed(referenceEngine)
        val reference = loaded(
            repository.createDraft(buildFileOf(state, game, REFERENCE_BUNDLE.clientBuild, ClassToken("DRUID"))),
        )
        val engine70245 = TalentAllocationEngine(document(BUNDLE_70245.clientBuild, "WARRIOR"))
        val state70245 = maxedThroughItsSection(engine70245, TOUGHNESS)
        val id70245 = saved(state70245, BUNDLE_70245.clientBuild, "WARRIOR")
        val previousEngine = TalentAllocationEngine(document(PREVIOUS_PRODUCT_BUNDLE.clientBuild, "PRIEST"))
        val firstTalent = previousEngine.tree.nodes
            .filter { previousEngine.canSpend(previousEngine.emptyState(), it.id).allowed }
            .minOf { it.id }
        val previousState = previousEngine.spend(previousEngine.emptyState(), firstTalent).state
        val previousId = saved(previousState, PREVIOUS_PRODUCT_BUNDLE.clientBuild, "PRIEST")
        val product = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("DRUID")) },
        ).record

        val cards = assertIs<LibraryData.Ready<List<BuildCardModel>>>(
            runBlocking { loadBuildsModel(repository, datasets) },
        ).value.associateBy { it.record.localId }

        val referenceCard = cards.getValue(reference.localId)
        assertEquals("Druid", referenceCard.classDisplayName)
        assertEquals(referenceEngine.spentPoints(state), referenceCard.spentPoints)
        assertEquals(51, referenceCard.pointBudget)
        val card70245 = cards.getValue(id70245)
        assertEquals("Warrior", card70245.classDisplayName)
        assertEquals(engine70245.spentPoints(state70245), card70245.spentPoints)
        assertEquals(51, card70245.pointBudget)
        val previousCard = cards.getValue(previousId)
        assertEquals("Priest", previousCard.classDisplayName)
        assertEquals(previousEngine.spentPoints(previousState), previousCard.spentPoints)
        assertEquals(51, previousCard.pointBudget)
        val productCard = cards.getValue(product.localId)
        assertEquals("Druid", productCard.classDisplayName)
        assertEquals(0, productCard.spentPoints)
        assertEquals(51, productCard.pointBudget)
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    fun aBuildOfAnUnbundledClientBuildFailsTypedWithoutFallback() {
        // real Forever builds that are not bundled: 1.60.1.70170 (between 70009 and
        // 70245), 1.60.1.70334 (talent data identical to 70338 and 70374) and 1.60.1.70425
        // (trait tables identical to 70450) - still no stand-in
        val productEngine = TalentAllocationEngine(document(PRODUCT_BUNDLE.clientBuild, "DRUID"))
        for (clientBuild in listOf("1.60.1.70170", "1.60.1.70334", "1.60.1.70425")) {
            val localId = saved(productEngine.emptyState(), clientBuild, "DRUID")
            assets.paths.clear()

            val failure = assertIs<EditorLoad.Failed>(open(localId)).failure

            val dataset = assertIs<EditorFailure.Dataset>(failure)
            val unavailable = assertIs<DatasetLoadError.ManifestUnavailable>(dataset.error)
            assertEquals(
                DatasetLoadError.AssetMissing("dataset/forever/$clientBuild/manifest.json"),
                unavailable.error,
            )
            // no bundled dataset was consulted in its place
            assertEquals(listOf("dataset/forever/$clientBuild/manifest.json"), assets.paths)
            assertTrue(loaded(repository.load()).any { it.localId == localId && it.build.clientBuild == clientBuild })
        }
    }

    private companion object {
        /** Druid node 104951: "King of the Jungle" (max 3) on 1.60.1.70009, "Shifting Power" (max 1) since 1.60.1.70245. */
        const val SHIFTING_POWER = 104951

        /** Druid "Improved Shifting Power": new in 1.60.1.70245. */
        const val IMPROVED_SHIFTING_POWER = 113563

        /** Warrior "Toughness" (Protection, max 5): on 1.60.1.70245, removed in 1.60.1.70338. */
        const val TOUGHNESS = 105973

        /** Warrior node 105953: "Boundless Rage" on 1.60.1.70245, "Furious Precision" on 1.60.1.70338 (max 3 on both). */
        const val BOUNDLESS_RAGE_NODE = 105953

        /** Warrior node 110857: "Iron Will" on 1.60.1.70245, "Lingering Rage" on 1.60.1.70338. */
        const val IRON_WILL_NODE = 110857

        /** Warrior "Gore Drinker" (requires Enrage) and the moved "Iron Will": new in 1.60.1.70338. */
        const val GORE_DRINKER = 113569
        const val IRON_WILL = 113570
        const val ENRAGE = 105931

        /** Priest "Improved Mind Blast" (max 5): on 1.60.1.70374, removed in 1.60.1.70450. */
        const val IMPROVED_MIND_BLAST_70374 = 105827

        /** Mage "Arcane Wrath" (new in 1.60.1.70425, outside every gate group) and its prerequisite. */
        const val ARCANE_WRATH = 113627
        const val ARCANE_BLAST = 105806
    }
}
