package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.ui.library.PREVIOUS_PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.REL.1 on the real editor screen: the section tabs present the resolved
 * specialization names (English default; Russian through the device-language
 * override), in portrait and landscape, at 320 dp and at 200 % font scale —
 * and a language change is presentation ONLY: the open section, its camera,
 * the build and the autosave observer never see it. An unresolvable class
 * keeps the localized generic "Tree N" / "Ветка N" instead of a guess.
 *
 * Density is pinned to 1, so px == dp.
 */
class TalentSpecializationNamesUiTest {

    /** UI copy follows the platform locale since M5.REL.2a: pinned explicitly. */
    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    @get:Rule
    val composeRule = createComposeRule()

    private val reported = mutableListOf<BuildState>()

    @BeforeTest
    fun reset() {
        reported.clear()
    }

    /** A Ready session of one class of [bundle], the product bundle by default (icons are not needed here). */
    private fun readyStateOf(classToken: String, bundle: DatasetBundleId = PRODUCT_BUNDLE): TalentTreeUiState.Ready = runBlocking {
        val document = loadedDocument(classToken, bundle)
        val engine = TalentAllocationEngine(document)
        TalentTreeUiState.Ready(
            classDisplayName = document.classInfo.name,
            gameDisplayName = "Forever",
            classToken = document.classInfo.token,
            model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model,
            engine = engine,
            buildState = engine.emptyState(),
            buildTitle = "Draft",
            classAccent = classAccentFor(document.classInfo.token).color,
        )
    }

    private fun loadedDocument(classToken: String, bundle: DatasetBundleId = PRODUCT_BUNDLE): TalentDataDocument = runBlocking {
        assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())
                .document(DatasetId(bundle.game, bundle.clientBuild, ClassToken(classToken))),
        ).value
    }

    private fun compose(
        state: TalentTreeUiState.Ready,
        language: AppLanguage = AppLanguage.ENGLISH,
        width: Dp = 393.dp,
        height: Dp = 852.dp,
        fontScale: Float = 1f,
        cameras: TalentTreeSectionCameras = TalentTreeSectionCameras(
            sessionKey = "spec-names-test",
            treeId = state.model.treeId,
        ),
    ) {
        uiLanguage.language = language
        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalAppLanguage provides language,
                ) {
                    Box(Modifier.size(width, height)) {
                        TalentTreeScreen(
                            state = state,
                            onBuildStateChanged = { reported += it },
                            sectionCameras = cameras,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun tabState(label: String): String =
        composeRule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun select(talent: String) {
        composeRule.onNodeWithContentDescription(talent).performClick()
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------ resolved labels

    @Test
    fun portraitTabsShowTheApprovedNamesInEnglishByDefault() {
        compose(readyStateOf("WARRIOR"))

        composeRule.onNodeWithText("Arms").assertIsSelected()
        composeRule.onNodeWithText("Fury").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fury").assertIsSelected()
        assertEquals("0 points spent", tabState("Fury"))
        composeRule.onNodeWithText("Protection").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Protection").assertIsSelected()
        assertTrue(reported.isEmpty(), "tab switches never change the build")
    }

    @Test
    fun portraitTabsShowTheApprovedNamesInRussian() {
        compose(readyStateOf("PALADIN"), language = AppLanguage.RUSSIAN)

        composeRule.onNodeWithText("Свет").assertIsSelected()
        composeRule.onNodeWithText("Защита").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Защита").assertIsSelected()
        composeRule.onNodeWithText("Воздаяние").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Воздаяние").assertIsSelected()
        // the per-tab point counts stay attached to the localized labels (localized since M5.REL.2b)
        assertEquals("потрачено 0 очков", tabState("Воздаяние"))
    }

    @Test
    fun anUnresolvableClassKeepsTheLocalizedGenericLabels() {
        val state = readyStateOf("WARRIOR").copy(classToken = ClassToken("DEATHKNIGHT"))

        compose(state)
        composeRule.onNodeWithText("Tree 1").assertIsSelected()
        composeRule.onNodeWithText("Tree 2").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tree 2").assertIsSelected()

        compose(state, language = AppLanguage.RUSSIAN)
        composeRule.onNodeWithText("Ветка 1").assertIsSelected()
        composeRule.onNodeWithText("Ветка 3").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ветка 3").assertIsSelected()
    }

    // ------------------------------------------------------------------ language is presentation only

    @Test
    fun changingTheLanguageChangesOnlyTheLabels() {
        // Mage's tabs on 1.60.1.70374: the product build's Mage tree publishes no sections
        val state = readyStateOf("MAGE", PREVIOUS_PRODUCT_BUNDLE)
        val cameras = TalentTreeSectionCameras(sessionKey = "language-switch", treeId = state.model.treeId)
        var language by mutableStateOf(AppLanguage.ENGLISH)

        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalAppLanguage provides language,
                ) {
                    Box(Modifier.size(393.dp, 852.dp)) {
                        TalentTreeScreen(
                            state = state,
                            onBuildStateChanged = { reported += it },
                            sectionCameras = cameras,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // open Fire, buy one rank there, and move its camera off the fit
        composeRule.onNodeWithText("Fire").performClick()
        composeRule.waitForIdle()
        val fire = firstBuyableTalentOf(state, section = 1)
        select(fire)
        composeRule.onNodeWithText("+").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            swipe(Offset(300f, 300f), Offset(300f, 380f), durationMillis = 300)
        }
        composeRule.waitForIdle()
        val cameraBefore = assertNotNull(cameras.stateOf(1).viewport)
        assertTrue(cameras.stateOf(1).userMoved, "the pan moved Fire's camera")
        assertEquals(1, reported.size)
        val buildBefore = reported.single()

        composeRule.runOnIdle {
            uiLanguage.language = AppLanguage.RUSSIAN
            language = AppLanguage.RUSSIAN
        }
        composeRule.waitForIdle()

        // the labels localized, nothing else moved
        composeRule.onNodeWithText("Огонь").assertIsSelected()
        composeRule.onNodeWithText("Тайная магия").assertExists()
        composeRule.onNodeWithText("Лёд").assertExists()
        // M5.REL.2b: the tab's spoken point count is localized with the labels
        assertEquals("потрачено 1 очко", tabState("Огонь"))
        assertEquals(buildBefore, reported.single(), "a language change never reports a build change")
        assertEquals(cameraBefore, cameras.stateOf(1).viewport, "the camera is untouched")
        composeRule.onNodeWithText("1 / 51").assertExists()
        assertEquals(1, cameras.selectedSection)

        // and back: the same section stays open, the build stays bought
        composeRule.runOnIdle {
            uiLanguage.language = AppLanguage.ENGLISH
            language = AppLanguage.ENGLISH
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Fire").assertIsSelected()
        assertEquals(cameraBefore, cameras.stateOf(1).viewport)
        assertEquals(buildBefore, reported.single())
    }

    // ------------------------------------------------------------------ adaptive edges

    @Test
    fun atDoubleFontScaleTheTabShowsItsNumberAndTheFullNameForAccessibility() {
        // Mage's tabs on 1.60.1.70374: the product build's Mage tree publishes no sections
        val mage = readyStateOf("MAGE", PREVIOUS_PRODUCT_BUNDLE)

        compose(mage, fontScale = 2f)
        // 1A v2 S8: from 175 % a tab reads its position number; its full
        // specialization name stays the accessibility description
        composeRule.onNode(hasText("2") and hasContentDescription("Fire")).assertExists()
        composeRule.onNode(hasText("3") and hasContentDescription("Frost")).assertExists()
        composeRule.onNodeWithText("Arcane").assertDoesNotExist()

        compose(mage, language = AppLanguage.RUSSIAN, fontScale = 2f)
        composeRule.onNode(hasText("2") and hasContentDescription("Огонь")).assertExists()
        composeRule.onNode(hasText("3") and hasContentDescription("Лёд")).assertExists()
    }

    @Test
    fun narrowWidthsKeepLongLocalizedLabelsTappable() {
        val hunter = readyStateOf("HUNTER")

        compose(hunter, width = 320.dp, height = 568.dp)
        composeRule.onNodeWithText("Beast Mastery").assertIsSelected()
        composeRule.onNodeWithText("Marksmanship").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Marksmanship").assertIsSelected()

        compose(hunter, language = AppLanguage.RUSSIAN, width = 320.dp, height = 568.dp)
        composeRule.onNodeWithText("Повелитель зверей").assertIsSelected()
        composeRule.onNodeWithText("Стрельба").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Стрельба").assertIsSelected()
        composeRule.onNodeWithText("Выживание").assertExists()
    }

    @Test
    fun rotationKeepsTheResolvedLabelTheOpenSectionAndTheSelection() {
        val paladin = readyStateOf("PALADIN")
        var window by mutableStateOf(IntSize(393, 852))
        val cameras = TalentTreeSectionCameras(sessionKey = "rotate-ru", treeId = paladin.model.treeId)
        uiLanguage.language = AppLanguage.RUSSIAN

        composeRule.setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalAppLanguage provides AppLanguage.RUSSIAN,
                ) {
                    Box(Modifier.size(window.width.dp, window.height.dp)) {
                        TalentTreeScreen(
                            state = paladin,
                            onBuildStateChanged = { reported += it },
                            sectionCameras = cameras,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Защита").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle { window = IntSize(852, 393) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Защита").assertIsSelected()
        assertEquals(1, cameras.selectedSection)

        composeRule.runOnIdle { window = IntSize(393, 852) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Защита").assertIsSelected()
        assertTrue(reported.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    /** A first-tier talent of [section] the empty build can buy. */
    private fun firstBuyableTalentOf(state: TalentTreeUiState.Ready, section: Int): String {
        val sections = buildTalentTreeSections(state.engine.tree, state.model) as TalentTreeSectionsResult.Sectioned
        val id = sections.section(section)!!.readingOrderNodeIds
            .first { state.engine.canSpend(state.engine.emptyState(), it).allowed }
        return assertNotNull(state.model.nodes.first { it.id == id }.name)
    }
}
