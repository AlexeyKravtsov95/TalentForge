package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.rememberTextMeasurer
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
import io.github.alexeykravtsov95.talentforge.ui.builds.BuildsPointsStyle
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * PR #22 accessibility review, as automated LAYOUT assertions (a semantics
 * lookup of an ellipsized string is NOT enough — the full text exists in the
 * semantics tree while being visually truncated):
 *
 * the All Builds row must keep the spent/total points VISIBLY readable at
 * 320 dp and 360 dp with 200 % font scale, with the priority build name →
 * points → class/version/time. Each test proves the points are a SEPARATE
 * element whose laid-out width covers the full intrinsic width of the label
 * (measured with the production style at the exact font scale), has nonzero
 * bounds and stays inside the screen — i.e. it is not clipped, wrapped or
 * ellipsized. The full trailing-column arrangement at 100 % is pinned too.
 */
class AllBuildsRowLayoutUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private lateinit var clock: FixedClock
    private val ids = SequentialIds()

    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    /** A build name near the 60-character rename limit — worst case for title space. */
    private val longName =
        "Rend and Deflection levelling route for solo battlegrounds"

    /** The intrinsic (unconstrained) width of the points label at the composed font scale. */
    private var pointsIntrinsicWidthPx: Int = 0

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
                    val textMeasurer = rememberTextMeasurer()
                    SideEffect {
                        pointsIntrinsicWidthPx =
                            textMeasurer.measure("13 / 51", BuildsPointsStyle).size.width
                    }
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

    private fun <T> loaded(outcome: BuildLibraryOutcome<T>): T =
        assertIs<BuildLibraryOutcome.Loaded<T>>(outcome).value

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
        // TF-UX-01: at the smallest width / 200 % the link is below the fold, under the purpose subtitle
        composeRule.onNodeWithText("All builds 1").performScrollTo().performClick()
        awaitText("Builds 1")
    }

    /**
     * The accessibility core: the points are their own element, with nonzero
     * bounds, laid out at (at least) the label's full intrinsic width — an
     * ellipsized or clipped element would report a narrower box — and fully
     * inside the screen.
     */
    private fun assertPointsElementFullyVisible(screenWidthPx: Int) {
        val node = composeRule.onNodeWithText("13 / 51").fetchSemanticsNode()
        val width = node.size.width.toFloat()
        assertTrue(width > 0f && node.size.height > 0, "the points element must have nonzero bounds")
        assertTrue(
            width + 0.5f >= pointsIntrinsicWidthPx,
            "the points label must be fully laid out, not clipped/ellipsized " +
                "(laid out $width px of intrinsic $pointsIntrinsicWidthPx px)",
        )
        val rightEdge = node.positionInRoot.x + width
        assertTrue(
            rightEdge <= screenWidthPx.toFloat(),
            "the points element must stay inside the screen (right edge $rightEdge of $screenWidthPx)",
        )
    }

    @Test
    fun atFullFontSizeTheTrailingPointsColumnIsFullyVisible() {
        composeSeededApp(widthDp = 360, heightDp = 800, fontScale = 1f) {
            persistLongNamedSpentBuild()
        }
        openAllBuilds()

        // the full arrangement: separate trailing mono column, fully laid out
        assertPointsElementFullyVisible(screenWidthPx = 360)
        // the meta line keeps its own full form (class · version · recency)
        composeRule.onNodeWithText("Warrior · Forever · Just now").assertExists()
        // the ⋯ target keeps its 48 dp width
        composeRule.onNodeWithContentDescription("build actions for $longName")
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun atDoubleFontScaleThePointsStayAVisibleSeparateElement() {
        composeSeededApp(widthDp = 360, heightDp = 800, fontScale = 2f) {
            persistLongNamedSpentBuild()
        }
        openAllBuilds()

        assertPointsElementFullyVisible(screenWidthPx = 360)
        composeRule.onNodeWithText(longName).assertExists()
        composeRule.onNodeWithContentDescription("build actions for $longName")
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun atSmallestWidthAndDoubleFontScaleThePointsStayAVisibleSeparateElement() {
        composeSeededApp(widthDp = 320, heightDp = 568, fontScale = 2f) {
            persistLongNamedSpentBuild()
        }
        openAllBuilds()

        assertPointsElementFullyVisible(screenWidthPx = 320)
        composeRule.onNodeWithText(longName).assertExists()
        composeRule.onNodeWithContentDescription("build actions for $longName")
            .assertWidthIsAtLeast(48.dp)
    }
}
