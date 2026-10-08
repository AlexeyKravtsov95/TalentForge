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
 * M5.DATA: two datasets bundled side by side. New drafts are created on the
 * product dataset ([PRODUCT_BUNDLE], 1.60.1.70245); a build saved on the
 * reference baseline ([REFERENCE_BUNDLE], 1.60.1.70009) opens on 1.60.1.70009
 * — same tree, ranks, points, icons and descriptions — and is never re-read
 * against the product dataset; a build of a client build that is not bundled
 * fails with a typed error instead of falling back.
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
     * A real 1.60.1.70009 Druid build with node 104951 (King of the Jungle on
     * 70009) at 3/3 — spent through the engine, never hand-written. On
     * 1.60.1.70245 the same node id is Shifting Power with max rank 1, so this
     * build only exists on its own build.
     */
    private fun referenceDruidWithShiftingPowerMaxed(engine: TalentAllocationEngine): BuildState {
        var state = engine.emptyState()
        while (!engine.canSpend(state, SHIFTING_POWER).allowed) {
            val next = engine.tree.nodes
                .filter { it.sectionIndex == 1 && it.id != SHIFTING_POWER }
                .filter { engine.canSpend(state, it.id).allowed }
                .minOf { it.id }
            state = engine.spend(state, next).state
        }
        repeat(3) { state = engine.spend(state, SHIFTING_POWER).state }
        assertEquals(3, state.rankOf(SHIFTING_POWER))
        assertTrue(engine.validate(state).valid)
        return state
    }

    private fun Iterable<String>.allIn(clientBuild: String): Boolean = all { it.contains("/$clientBuild/") }

    // ------------------------------------------------------------------ product dataset

    @Test
    fun newBuildListsTheProductDatasetByItsExactClientBuild() {
        val choices = assertNotNull(runBlocking { loadNewBuildChoices(datasets) })

        assertEquals("Forever", choices.gameDisplayName)
        assertEquals("Talent data 1.60.1.70245", choices.versionDetail)
        assertEquals(9, choices.classes.size)
        assertTrue(choices.classes.all { it.available })
        assertTrue(assets.paths.allIn(PRODUCT_BUNDLE.clientBuild), "read: ${assets.paths}")
    }

    @Test
    fun aNewDraftIsCreatedAndOpensOnTheProductDataset() {
        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("DRUID")) },
        ).record
        assertEquals("1.60.1.70245", created.build.clientBuild)
        assertEquals(PRODUCT_BUNDLE.clientBuild, created.build.clientBuild)
        assertEquals(1089, created.build.treeId)

        val session = assertIs<EditorLoad.Ready>(open(created.localId)).session

        assertEquals("1.60.1.70245", session.engine.document.clientBuild)
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
        // dataset, visuals and descriptions all came from 1.60.1.70245
        assertTrue(assets.paths.any { it.startsWith("visuals/forever/1.60.1.70245/") })
        assertTrue(assets.paths.any { it.startsWith("tooltips/forever/1.60.1.70245/") })
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

    @Test
    fun buildCardsOfBothBuildsResolveTheirOwnDatasets() {
        val referenceEngine = TalentAllocationEngine(document(REFERENCE_BUNDLE.clientBuild, "DRUID"))
        val state = referenceDruidWithShiftingPowerMaxed(referenceEngine)
        val reference = loaded(
            repository.createDraft(buildFileOf(state, game, REFERENCE_BUNDLE.clientBuild, ClassToken("DRUID"))),
        )
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
        val productCard = cards.getValue(product.localId)
        assertEquals("Druid", productCard.classDisplayName)
        assertEquals(0, productCard.spentPoints)
        assertEquals(51, productCard.pointBudget)
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    fun aBuildOfAnUnbundledClientBuildFailsTypedWithoutFallback() {
        // 1.60.1.70170 is a real Forever build between the two bundled ones
        val productEngine = TalentAllocationEngine(document(PRODUCT_BUNDLE.clientBuild, "DRUID"))
        val record = loaded(
            repository.createDraft(buildFileOf(productEngine.emptyState(), game, "1.60.1.70170", ClassToken("DRUID"))),
        )
        assets.paths.clear()

        val failure = assertIs<EditorLoad.Failed>(open(record.localId)).failure

        val dataset = assertIs<EditorFailure.Dataset>(failure)
        val unavailable = assertIs<DatasetLoadError.ManifestUnavailable>(dataset.error)
        assertEquals(
            DatasetLoadError.AssetMissing("dataset/forever/1.60.1.70170/manifest.json"),
            unavailable.error,
        )
        // neither bundled dataset was consulted in its place
        assertEquals(listOf("dataset/forever/1.60.1.70170/manifest.json"), assets.paths)
        assertEquals("1.60.1.70170", loaded(repository.load()).single().build.clientBuild)
    }

    private companion object {
        /** Druid node 104951: "King of the Jungle" (max 3) on 1.60.1.70009, "Shifting Power" (max 1) on 1.60.1.70245. */
        const val SHIFTING_POWER = 104951

        /** Druid "Improved Shifting Power": new in 1.60.1.70245. */
        const val IMPROVED_SHIFTING_POWER = 113563
    }
}
