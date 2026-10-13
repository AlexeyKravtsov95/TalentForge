package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.ClassArtworkSlot
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.newbuild.ClassPickerLayout
import io.github.alexeykravtsov95.talentforge.ui.newbuild.NewBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.newbuild.classPickerLayout
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The M5.UI.2 B+ Class Selection contract against the REAL app root, real
 * bundled datasets and a fake injected storage: the nine manifest classes in
 * order, the responsive 3/2-column grid across the milestone's viewport and
 * font-scale matrix, the one-tap creation with its held-Back policy, the
 * per-class and manifest failure states, and the retry paths.
 *
 * All compositions pin [LocalReducedMotion] to `true`: the creating-card
 * progress mark then renders statically, keeping the scenarios deterministic
 * (no infinite animation while a held write is in flight).
 */
class NewBuildClassSelectionUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var storage: FakeBuildLibraryStorage
    private val clock = FixedClock()
    private val ids = SequentialIds()

    private val realDatasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    private val manifestClassOrder = listOf(
        "Warrior", "Paladin", "Hunter", "Rogue", "Priest", "Shaman", "Mage", "Warlock", "Druid",
    )

    @BeforeTest
    fun setUp() {
        storage = FakeBuildLibraryStorage()
    }

    private fun repository() = LocalBuildRepository(storage, clock, ids)

    private fun records(): List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord> =
        assertIs<BuildLibraryOutcome.Loaded<List<io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord>>>(
            repository().load(),
        ).value

    // ------------------------------------------------------------ composition

    private fun composeApp(widthDp: Int = 360, heightDp: Int = 800, fontScale: Float = 1f) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalReducedMotion provides true,
                ) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        App(storage = storage, clock = clock, ids = ids)
                    }
                }
            }
        }
    }

    /** Composes the screen directly (no App root) so a failing dataset repository can be injected. */
    private fun composeScreen(
        datasets: TalentDatasetRepository = realDatasets,
        widthDp: Int = 360,
        heightDp: Int = 800,
        fontScale: Float = 1f,
        onDraftCreated: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalReducedMotion provides true,
                ) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        NewBuildScreen(
                            repository = repository(),
                            datasets = datasets,
                            onBack = {},
                            onDraftCreated = onDraftCreated,
                        )
                    }
                }
            }
        }
    }

    private fun openClassSelection(widthDp: Int = 360, heightDp: Int = 800, fontScale: Float = 1f) {
        composeApp(widthDp, heightDp, fontScale)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("New build").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("New build").performClick()
        awaitText("Choose a class")
        // the real class list resolves asynchronously — wait for the grid
        awaitText("Warrior")
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitCreatingMark() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription("Creating build").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitEditor(className: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("$className · Forever").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun topLeftOf(text: String): androidx.compose.ui.geometry.Offset =
        composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot

    /**
     * The merged card node of a class label: the cards merge descendants
     * (clickable/semantics), so the label query resolves to the card itself.
     */
    private fun cardOf(label: String): SemanticsNode =
        assertNotNull(composeRule.onNodeWithText(label).fetchSemanticsNode())

    // ------------------------------------------------------------- the grid

    @Test
    fun nineClassesAppearInManifestReadingOrder() {
        openClassSelection()

        for (name in manifestClassOrder) {
            composeRule.onNodeWithText(name).assertExists()
        }
        // reading order: every next class is strictly after its predecessor —
        // lower row, or same row further right
        val positions = manifestClassOrder.map { topLeftOf(it) }
        positions.zipWithNext().forEach { (current, next) ->
            val after = next.y > current.y + 1f || (abs(next.y - current.y) <= 1f && next.x > current.x)
            assertTrue(after, "expected reading order; predecessor at $current, successor at $next")
        }
    }

    @Test
    fun threeColumnsAtTheDefaultViewport() {
        openClassSelection(widthDp = 360, heightDp = 800)

        val warrior = topLeftOf("Warrior")
        val paladin = topLeftOf("Paladin")
        val hunter = topLeftOf("Hunter")
        val rogue = topLeftOf("Rogue")
        assertTrue(abs(warrior.y - paladin.y) <= 1f && abs(paladin.y - hunter.y) <= 1f, "first three classes share a row")
        assertTrue(paladin.x > warrior.x && hunter.x > paladin.x, "three columns across")
        assertTrue(abs(rogue.y - warrior.y) > 1f, "the fourth class starts the second row")
        assertTrue(abs(rogue.x - warrior.x) <= 1f, "rows restart at the leading edge")
        assertNothingOverflowsHorizontally(360f)
    }

    @Test
    fun threeColumnsOnTheCompactViewport() {
        openClassSelection(widthDp = 320, heightDp = 568)

        val warrior = topLeftOf("Warrior")
        val paladin = topLeftOf("Paladin")
        val hunter = topLeftOf("Hunter")
        val rogue = topLeftOf("Rogue")
        assertTrue(abs(warrior.y - paladin.y) <= 1f && abs(paladin.y - hunter.y) <= 1f, "compact grid keeps three columns")
        assertTrue(paladin.x > warrior.x && hunter.x > paladin.x)
        assertTrue(abs(rogue.y - hunter.y) > 1f && abs(rogue.x - warrior.x) <= 1f)
        assertNothingOverflowsHorizontally(320f)
    }

    @Test
    fun font130KeepsThreeReadableColumns() {
        openClassSelection(widthDp = 360, heightDp = 800, fontScale = 1.3f)

        val warrior = topLeftOf("Warrior")
        val paladin = topLeftOf("Paladin")
        val hunter = topLeftOf("Hunter")
        assertTrue(abs(warrior.y - paladin.y) <= 1f && abs(paladin.y - hunter.y) <= 1f, "130% keeps the three-column grid")
        assertTrue(abs(topLeftOf("Rogue").y - warrior.y) > 1f)
    }

    @Test
    fun font200DropsToTwoColumns() {
        openClassSelection(widthDp = 360, heightDp = 800, fontScale = 2f)

        val warrior = topLeftOf("Warrior")
        val paladin = topLeftOf("Paladin")
        val hunter = topLeftOf("Hunter")
        val rogue = topLeftOf("Rogue")
        val priest = topLeftOf("Priest")
        assertTrue(abs(warrior.y - paladin.y) <= 1f, "first two classes share a row")
        assertTrue(abs(hunter.y - warrior.y) > 1f && abs(hunter.y - rogue.y) <= 1f, "the second row is the third and fourth class")
        assertTrue(abs(priest.y - hunter.y) > 1f, "the grid continues in two columns")
        assertNothingOverflowsHorizontally(360f)
    }

    @Test
    fun theLastClassIsReachableByScrollingAt200Percent() {
        openClassSelection(widthDp = 320, heightDp = 568, fontScale = 2f)

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Druid"))

        composeRule.onNodeWithText("Druid").assertIsDisplayed()
    }

    /** No class label crosses the viewport edge — nothing is clipped. */
    private fun assertNothingOverflowsHorizontally(viewportWidth: Float) {
        for (name in manifestClassOrder) {
            val node = composeRule.onNodeWithText(name).fetchSemanticsNode()
            val right = node.positionInRoot.x + node.size.width
            assertTrue(right <= viewportWidth + 0.5f, "$name overflows: right edge $right > $viewportWidth")
            assertTrue(node.positionInRoot.x >= -0.5f, "$name starts left of the viewport")
        }
    }

    // ------------------------------------------------------ responsive policy

    @Test
    fun gridPolicyMatchesTheResponsiveMatrix() {
        // widest-label widths of the real names around 100 % / 130 % / 200 %
        // font scale; grid widths are the viewport minus the screen inset
        assertEquals(ClassPickerLayout(3, ClassArtworkSlot.Picker), classPickerLayout(361.dp, 52.dp))
        assertEquals(ClassPickerLayout(3, ClassArtworkSlot.Picker), classPickerLayout(328.dp, 52.dp))
        // the narrow viewport keeps three columns on the compact tile
        assertEquals(ClassPickerLayout(3, ClassArtworkSlot.PickerCompact), classPickerLayout(288.dp, 52.dp))
        // 130 % labels still fit a three-column card
        assertEquals(ClassPickerLayout(3, ClassArtworkSlot.Picker), classPickerLayout(328.dp, 66.dp))
        // 200 % labels no longer fit three columns → the two-column layout
        assertEquals(ClassPickerLayout(2, ClassArtworkSlot.Picker), classPickerLayout(328.dp, 104.dp))
        assertEquals(ClassPickerLayout(2, ClassArtworkSlot.Picker), classPickerLayout(288.dp, 104.dp))
        // M5.REL.2a: a word wider than a two-column card (Russian "Чернокнижник"
        // at 200 %) is never broken mid-word — the grid drops to one column
        assertEquals(ClassPickerLayout(2, ClassArtworkSlot.Picker), classPickerLayout(288.dp, 124.dp, widestWord = 124.dp))
        assertEquals(ClassPickerLayout(1, ClassArtworkSlot.Picker), classPickerLayout(288.dp, 190.dp, widestWord = 190.dp))
        assertEquals(ClassPickerLayout(1, ClassArtworkSlot.Picker), classPickerLayout(361.dp, 190.dp, widestWord = 161.dp))
    }

    // ------------------------------------------------------------- creation

    @Test
    fun oneTapPersistsTheDraftAndOpensTheEditor() {
        openClassSelection()

        composeRule.onNodeWithText("Warrior").performClick()

        awaitEditor("Warrior")
        val stored = assertNotNull(storage.payload, "a real Warrior BuildFile must be stored")
        assertTrue(stored.contains("\"class\":\"WARRIOR\""))
        assertTrue(stored.contains("\"allocations\":[]"), "a fresh draft starts empty")
    }

    @Test
    fun backFromTheCreatedDraftGoesHomeNotClassSelection() {
        openClassSelection()
        composeRule.onNodeWithText("Mage").performClick()
        awaitEditor("Mage")

        composeRule.onNodeWithContentDescription("go back").performClick()

        awaitText("CONTINUE")
        composeRule.onNodeWithText("Choose a class").assertDoesNotExist()
    }

    @Test
    fun repeatedTapsWhileCreatingProduceExactlyOneDraft() {
        storage.writeDelayMillis = 600
        openClassSelection()

        composeRule.onNodeWithText("Warrior").performClick()
        awaitCreatingMark()
        // duplicate taps on the creating card and on other cards do nothing
        composeRule.onNodeWithText("Warrior").performClick()
        composeRule.onNodeWithText("Mage").performClick()

        awaitEditor("Warrior")
        assertEquals(1, records().size, "repeated taps must not create a second draft")
    }

    @Test
    fun theCreatingCardShowsProgressAndEveryOtherCardDisables() {
        storage.writeDelayMillis = 800
        openClassSelection()

        composeRule.onNodeWithText("Warrior").performClick()
        awaitCreatingMark()

        composeRule.onNodeWithContentDescription("Creating build").assertExists()
        // while the creation is in flight no card offers a create action
        composeRule.waitUntil(10_000) { !cardOf("Mage").config.contains(SemanticsActions.OnClick) }
        // the class identity stays visible on the creating card
        composeRule.onNodeWithText("Warrior").assertExists()

        awaitEditor("Warrior")
    }

    @Test
    fun backIsHeldWhileTheCreationIsInFlight() {
        storage.writeDelayMillis = 800
        openClassSelection()

        composeRule.onNodeWithText("Warrior").performClick()
        awaitCreatingMark()

        // the visible back affordance must not leave mid-creation
        composeRule.onNodeWithContentDescription("go back").performClick()
        composeRule.onNodeWithText("Choose a class").assertExists()
        composeRule.onNodeWithText("No builds yet").assertDoesNotExist()

        // the creation settles: only then the editor opens
        awaitEditor("Warrior")
        assertEquals(1, records().size)
    }

    // --------------------------------------------------------- failure states

    @Test
    fun anUnavailableClassStaysDisabledAndDoesNotAffectTheOthers() {
        var created = 0
        composeScreen(
            datasets = FailingDatasets(realDatasets, failingClassToken = "SHAMAN"),
            onDraftCreated = { created++ },
        )

        awaitText("SHAMAN") // the honest token label of the failed class document
        for (name in manifestClassOrder.filter { it != "Shaman" }) {
            composeRule.onNodeWithText(name).assertExists()
        }
        // the unavailable card reports disabled and offers no create action
        val shamanCard = cardOf("SHAMAN")
        assertTrue(shamanCard.config.contains(SemanticsProperties.Disabled), "the unavailable card must report disabled")
        assertFalse(
            shamanCard.config.contains(SemanticsActions.OnClick),
            "an unavailable class must not be selectable",
        )
        val mageCard = cardOf("Mage")
        assertFalse(mageCard.config.contains(SemanticsProperties.Disabled))
        assertTrue(mageCard.config.contains(SemanticsActions.OnClick), "unrelated classes stay available")

        // tapping it changes nothing…
        composeRule.onNodeWithText("SHAMAN").performClick()
        composeRule.onNodeWithText("Choose a class").assertExists()
        assertEquals(0, created)
        assertNull(storage.payload)

        // …while an available class still creates
        composeRule.onNodeWithText("Mage").performClick()
        composeRule.waitUntil(10_000) { created == 1 }
        assertTrue(storage.payload!!.contains("\"class\":\"MAGE\""))
    }

    @Test
    fun manifestFailureShowsTheRecoverableErrorAndRetryReloads() {
        val datasets = FailingDatasets(realDatasets, failManifest = true)
        composeScreen(datasets = datasets)

        awaitText("Couldn't load classes")
        composeRule.onNodeWithText("The local talent data could not be read.").assertExists()
        composeRule.onNodeWithText("Warrior").assertDoesNotExist()

        // retry against the still-failing bundle stays on the error state
        composeRule.onNodeWithText("Retry").performClick()
        awaitText("Couldn't load classes")

        // a healthy bundle recovers into the real nine classes
        datasets.failManifest = false
        composeRule.onNodeWithText("Retry").performClick()
        awaitText("Warrior")
        composeRule.onNodeWithText("Druid").assertExists()
    }

    @Test
    fun persistenceFailureKeepsTheScreenAndARetrySucceeds() {
        storage.failWrites = true
        openClassSelection()

        composeRule.onNodeWithText("Warrior").performClick()
        awaitText("The draft could not be saved on this device.")
        composeRule.onNodeWithText("Choose a class").assertExists()
        assertNull(storage.payload)

        // retry = tapping the class again, after the storage recovered
        storage.failWrites = false
        composeRule.onNodeWithText("Warrior").performClick()
        awaitEditor("Warrior")
        assertEquals(1, records().size)
    }

    /**
     * Deterministic dataset double over the real bundle: the manifest and
     * exactly ONE class document can be made to fail, everything else reads
     * the real bundled data (no substitution with another dataset).
     */
    private class FailingDatasets(
        private val delegate: TalentDatasetRepository,
        var failManifest: Boolean = false,
        var failingClassToken: String? = null,
    ) : TalentDatasetRepository {
        override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
            if (failManifest) {
                DatasetOutcome.Failed(
                    DatasetLoadError.AssetUnreadable(
                        path = "dataset/${bundle.game.value}/${bundle.clientBuild}/manifest.json",
                        detail = "injected manifest failure",
                    ),
                )
            } else {
                delegate.manifest(bundle)
            }

        override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> {
            if (id.classToken.value == failingClassToken) {
                return DatasetOutcome.Failed(
                    DatasetLoadError.AssetUnreadable(
                        path = "dataset/${id.game.value}/${id.clientBuild}/${id.classToken.value.lowercase()}.json",
                        detail = "injected class document failure",
                    ),
                )
            }
            return delegate.document(id)
        }
    }
}

private fun abs(value: Float): Float = kotlin.math.abs(value)
