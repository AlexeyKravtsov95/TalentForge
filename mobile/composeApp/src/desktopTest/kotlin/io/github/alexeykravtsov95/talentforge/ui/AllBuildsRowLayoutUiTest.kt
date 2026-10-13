package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.GameId
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * PR #22 review finding 1, as automated layout assertions: at 320 dp width
 * and 200 % font scale the All Builds row's FIXED elements (36 dp artwork,
 * the mono points column, the 48 dp `⋯` button) must never crowd the build
 * name into unreadability. The row answers with a responsive arrangement:
 * with room (360 dp at 100 %) the points stay a trailing column; without it
 * (200 % font scale, narrow screens) the points fold into the meta line and
 * the freed width goes to the title — while the artwork, the points FACT and
 * the 48 dp actions target are always preserved.
 */
class AllBuildsRowLayoutUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private lateinit var clock: FixedClock
    private val ids = SequentialIds()

    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    /** A build name near the 60-character rename limit — worst case for title space. */
    private val longName =
        "Rend and Deflection levelling route for solo battlegrounds"

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
        clock = FixedClock()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun composeApp(widthDp: Int, heightDp: Int, fontScale: Float) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        App(storage = storage, clock = clock, ids = ids)
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Composes the app FIRST on the fresh empty library, seeds the scenario,
     * then recomposes. Composing a seeded library as the VERY FIRST
     * composition of a suite run (heavy `runBlocking` dataset resolution
     * before any composition) can leave the desktop pipeline's first
     * composition suspended at Loading forever; warming it on the empty
     * library first is deterministic in single- and multi-class runs alike.
     */
    private fun composeSeededApp(widthDp: Int, heightDp: Int, fontScale: Float, seed: () -> Unit) {
        composeApp(widthDp, heightDp, fontScale)
        awaitText("No builds yet")
        seed()
        composeApp(widthDp, heightDp, fontScale)
        awaitText("CONTINUE")
    }

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

    /**
     * A real engine-spent Warrior build with a two-digit points label
     * (`13 / 51` — the widest label class, same width as `51 / 51`), renamed
     * to a maximum-length custom name.
     */
    private fun persistLongNamedSpentBuild() {
        val document = runBlocking {
            assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
                datasets.document(DatasetId(GameId("forever"), "1.60.1.70338", ClassToken("WARRIOR"))),
            ).value
        }
        val engine = TalentAllocationEngine(document)
        var state = engine.emptyState()
        for (talent in listOf("Deflection", "Cruelty", "Improved Heroic Strike")) {
            val node = engine.tree.nodes.first { it.name == talent }
            repeat(node.maxRank) { state = engine.spend(state, node.id).state }
        }
        val repository = repository()
        val created = loaded(
            repository.createDraft(
                buildFileOf(
                    state = engine.emptyState(),
                    game = document.game,
                    clientBuild = document.clientBuild,
                    classToken = document.classInfo.token,
                ),
            ),
        )
        loaded(
            repository.saveBuild(
                created.localId,
                buildFileOf(
                    state = state,
                    game = document.game,
                    clientBuild = document.clientBuild,
                    classToken = document.classInfo.token,
                ),
            ),
        )
        loaded(repository.renameBuild(created.localId, longName))
    }

    private fun openAllBuilds() {
        awaitText("CONTINUE")
        composeRule.onNodeWithText("All builds 1").performClick()
        awaitText("Builds 1")
    }

    @Test
    fun withRoomThePointsStayATrailingColumn() {
        composeSeededApp(widthDp = 360, heightDp = 800, fontScale = 1f) {
            persistLongNamedSpentBuild()
        }
        openAllBuilds()

        // the full arrangement: points are their own trailing mono column…
        composeRule.onNodeWithText("13 / 51").assertExists()
        // …and the meta line stays class · version · recency (points NOT folded)
        composeRule.onNodeWithText("Warrior · Forever · Just now").assertExists()
        // the ⋯ target keeps its 48 dp width
        composeRule.onNodeWithContentDescription("build actions for $longName")
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun atDoubleFontScaleThePointsFoldIntoTheMetaLine() {
        composeSeededApp(widthDp = 360, heightDp = 800, fontScale = 2f) {
            persistLongNamedSpentBuild()
        }
        openAllBuilds()

        // compact arrangement: no separate squeezed points column…
        composeRule.onNodeWithText("13 / 51").assertDoesNotExist()
        // …the points FACT survives inside the meta line…
        composeRule.onNodeWithText("Warrior · Forever · 13 / 51 · Just now").assertExists()
        // …the long title is present…
        composeRule.onNodeWithText(longName).assertExists()
        // …and the ⋯ target still keeps its 48 dp width
        composeRule.onNodeWithContentDescription("build actions for $longName")
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun atSmallestWidthAndDoubleFontScaleTheRowStaysReadable() {
        composeSeededApp(widthDp = 320, heightDp = 568, fontScale = 2f) {
            persistLongNamedSpentBuild()
        }
        openAllBuilds()

        composeRule.onNodeWithText("13 / 51").assertDoesNotExist()
        composeRule.onNodeWithText("Warrior · Forever · 13 / 51 · Just now").assertExists()
        composeRule.onNodeWithText(longName).assertExists()
        composeRule.onNodeWithContentDescription("build actions for $longName")
            .assertWidthIsAtLeast(48.dp)
    }
}
