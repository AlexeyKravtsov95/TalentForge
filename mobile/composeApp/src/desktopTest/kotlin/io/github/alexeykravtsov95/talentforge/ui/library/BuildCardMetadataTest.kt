package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.allocation.toBuildState
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.allocation.BuildFile
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/**
 * M5.1 review fix: build cards resolve each record's EXACT dataset identity
 * (game / clientBuild / classToken / treeId) — never the currently bundled
 * baseline — and derive point metadata only when the exact dataset loads,
 * the saved tree exists and the saved allocations validate with the real
 * engine. Anything else leaves the derived fields honestly absent.
 */
class BuildCardMetadataTest {

    private val game = GameId("forever")
    private val clientBuild = "1.60.1.70009"

    private lateinit var datasets: BundledTalentDatasetRepository
    private lateinit var storage: FakeBuildLibraryStorage
    private lateinit var repository: LocalBuildRepository

    @BeforeTest
    fun setUp() {
        datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
        storage = FakeBuildLibraryStorage()
        repository = LocalBuildRepository(storage, FixedClock(), SequentialIds())
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    private fun warriorDocument() = runBlocking {
        assertIs<DatasetOutcome.Loaded<io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument>>(
            datasets.document(DatasetId(game, clientBuild, ClassToken("WARRIOR"))),
        ).value
    }

    private fun warriorEngine() = TalentAllocationEngine(warriorDocument())

    /** A real, engine-spent Warrior build persisted through the repository. */
    private fun persistWarrior(ranks: Map<String, Int>): LocalBuildRecord {
        val engine = warriorEngine()
        var state = engine.emptyState()
        for ((name, count) in ranks) {
            val nodeId = engine.tree.nodes.first { it.name == name }.id
            repeat(count) { state = engine.spend(state, nodeId).state }
        }
        return persistWarriorFile(buildFileOf(state, game, clientBuild, ClassToken("WARRIOR")))
    }

    private fun persistWarriorFile(file: BuildFile): LocalBuildRecord {
        val created = loaded(repository.createDraft(buildFileOf(warriorEngine().emptyState(), game, clientBuild, ClassToken("WARRIOR"))))
        if (file.allocations.isNotEmpty()) {
            return loaded(repository.saveBuild(created.localId, file))
        }
        return loaded(repository.open(created.localId))
    }

    private fun buildsModel() = runBlocking { loadBuildsModel(repository, datasets) }

    private fun homeModel() = runBlocking { loadHomeModel(repository, datasets) }

    // ------------------------------------------------------------------ A: unavailable dataset

    @Test
    fun recordWithUnavailableClientBuildGetsNoBaselineDerivedMetadata() {
        val record = persistWarrior(mapOf("Improved Rend" to 3))
        loaded(
            repository.saveBuild(record.localId, record.build.copy(clientBuild = "9.9.9.99999")),
        )

        val card = assertIs<LibraryData.Ready<List<BuildCardModel>>>(buildsModel()).value.single()

        // no silent current-dataset substitution: nothing derives from the baseline
        assertEquals(null, card.classDisplayName)
        assertEquals(null, card.spentPoints)
        assertEquals(null, card.pointBudget)
        // the persisted facts themselves stay visible; the class document did
        // not load, so the display fallback capitalizes the class token
        assertEquals("Warrior build", card.title)
        assertEquals("9.9.9.99999", card.record.build.clientBuild)

        val homeCard = assertIs<LibraryData.Ready<HomeModel>>(homeModel()).value.continueCard
        assertEquals(null, assertNotNull(homeCard).spentPoints)
        assertEquals(null, homeCard.pointBudget)
    }

    // ------------------------------------------------------------------ B: wrong tree

    @Test
    fun recordWithWrongTreeIdGetsNoEngineDerivedPointMetadata() {
        val record = persistWarrior(mapOf("Improved Rend" to 3))
        loaded(repository.saveBuild(record.localId, record.build.copy(treeId = 999_999)))

        val card = assertIs<LibraryData.Ready<List<BuildCardModel>>>(buildsModel()).value.single()

        // the exact document loaded, so its real class name is honest…
        assertEquals("Warrior", card.classDisplayName)
        // …but the saved tree does not exist in it: no engine-derived values
        assertEquals(null, card.spentPoints)
        assertEquals(null, card.pointBudget)
    }

    // ------------------------------------------------------------------ C: invalid saved allocations

    @Test
    fun recordWithInvalidAllocationsGetsNoFakeSpentOrBudget() {
        val record = persistWarrior(mapOf("Improved Rend" to 3))
        val broken = record.build.toBuildState().withAllocation(
            nodeId = record.build.allocations.single().nodeId,
            entryId = record.build.allocations.single().entryId,
            rank = 99,
        )
        loaded(
            repository.saveBuild(
                record.localId,
                buildFileOf(broken, game, clientBuild, ClassToken("WARRIOR")),
            ),
        )

        val card = assertIs<LibraryData.Ready<List<BuildCardModel>>>(buildsModel()).value.single()

        assertEquals("Warrior", card.classDisplayName)
        assertEquals(null, card.spentPoints, "an invalid build must not show engine-derived points")
        assertEquals(null, card.pointBudget)
    }

    // ------------------------------------------------------------------ D: valid records

    @Test
    fun validBaselineRecordsKeepTheirRealMetadata() {
        persistWarrior(mapOf("Improved Rend" to 3, "Deflection" to 2))
        assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("MAGE")) },
        )

        val cards = assertIs<LibraryData.Ready<List<BuildCardModel>>>(buildsModel()).value
            .associateBy { it.record.build.classToken.value }

        val warrior = cards.getValue("WARRIOR")
        assertEquals("Warrior", warrior.classDisplayName)
        assertEquals(5, warrior.spentPoints)
        assertEquals(51, warrior.pointBudget)

        val mage = cards.getValue("MAGE")
        assertEquals("Mage", mage.classDisplayName)
        assertEquals(0, mage.spentPoints)
        assertEquals(mageEngineBudget(), mage.pointBudget)
    }

    private fun mageEngineBudget(): Int = runBlocking {
        val document = assertIs<DatasetOutcome.Loaded<io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument>>(
            datasets.document(DatasetId(game, clientBuild, ClassToken("MAGE"))),
        ).value
        TalentAllocationEngine(document).pointBudget
    }

    // ------------------------------------------------- M5.UI.1: display-only names

    @Test
    fun unnamedBuildsDisplayTheDescriptiveClassFallback() {
        assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("MAGE")) },
        )

        val card = assertIs<LibraryData.Ready<List<BuildCardModel>>>(buildsModel()).value.single()

        assertEquals("Mage build", card.title)
        // display-only: the persisted record stays unnamed
        assertEquals(null, card.record.name)
    }

    @Test
    fun anExplicitlyNamedDraftIsNeverReplacedByTheFallback() {
        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("MAGE")) },
        ).record
        loaded(repository.renameBuild(created.localId, "Draft"))

        val card = assertIs<LibraryData.Ready<List<BuildCardModel>>>(buildsModel()).value.single()

        assertEquals("Draft", card.title)
        assertEquals("Draft", card.record.name)
    }

    @Test
    fun aCustomNameAlwaysTakesPrecedenceEverywhere() {
        val created = assertIs<DraftCreation.Created>(
            runBlocking { createClassDraft(repository, datasets, ClassToken("WARRIOR")) },
        ).record
        loaded(repository.renameBuild(created.localId, "Rend spam levelling"))

        val card = assertIs<LibraryData.Ready<List<BuildCardModel>>>(buildsModel()).value.single()
        val homeCard = assertIs<LibraryData.Ready<HomeModel>>(homeModel()).value.continueCard

        assertEquals("Rend spam levelling", assertNotNull(homeCard).title)
        assertEquals("Rend spam levelling", card.title)
    }
}
