package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * M5.REL.1 PR evidence from the real screen (desktop-rendered, product
 * dataset 1.60.1.70338, real icons): the resolved specialization tab labels
 * in English and Russian — Paladin Holy/Protection/Retribution and
 * Свет/Защита/Воздаяние, Mage Arcane/Fire/Frost and Тайная магия/Огонь/Лёд —
 * landscape section navigation, a 320 dp phone, and 200 % font scale (where
 * the tabs read their position numbers).
 *
 * Skipped unless requested — it is evidence, not a regression assertion:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*TalentSpecializationNamesScreenshotTest*" \
 *     -Dtalentforge.m5rel1.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class TalentSpecializationNamesScreenshotTest {

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    /** A full Ready session (real icons and descriptions) of one product-bundle class. */
    private fun readyStateOf(classToken: String, title: String): TalentTreeUiState.Ready = runBlocking {
        val assets = ComposeResourceDatasetAssetSource()
        val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            BundledTalentDatasetRepository(assets)
                .document(DatasetId(PRODUCT_BUNDLE.game, PRODUCT_BUNDLE.clientBuild, ClassToken(classToken))),
        ).value
        val engine = TalentAllocationEngine(document)
        TalentTreeUiState.Ready(
            classDisplayName = document.classInfo.name,
            gameDisplayName = "Forever",
            classToken = document.classInfo.token,
            model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model,
            engine = engine,
            buildState = engine.emptyState(),
            buildTitle = title,
            classAccent = classAccentFor(document.classInfo.token).color,
            icons = TalentIconsLoader(assets).load(document, document.trees.single()).icons,
            descriptions = TalentDescriptionsLoader(assets).load(document, document.trees.single()).descriptions,
        )
    }

    /** Shows [state] on a [width] × [height] dp window at [fontScale], in [language]. */
    private fun onPhone(
        state: TalentTreeUiState.Ready,
        width: Int,
        height: Int,
        language: AppLanguage = AppLanguage.ENGLISH,
        fontScale: Float = 1f,
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = (width * DENSITY).toInt(), height = (height * DENSITY).toInt()) {
        val cameras = TalentTreeSectionCameras(sessionKey = "m5rel1", treeId = state.model.treeId)
        setContent {
            TalentForgeTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(DENSITY, fontScale),
                    LocalAppLanguage provides language,
                ) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        TalentTreeScreen(state, sectionCameras = cameras)
                    }
                }
            }
        }
        settle()
        block()
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(800)
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    private fun ComposeUiTest.openTab(label: String) {
        onNodeWithText(label).performClick()
        settle()
    }

    @Test
    fun paladinAndMageTabsInBothLanguages() {
        val paladin = readyStateOf("PALADIN", "Retlev, Holy leveling")
        onPhone(paladin, 393, 852) {
            shoot("m5rel1-393x852-paladin-en-1-holy.png")
            openTab("Protection")
            shoot("m5rel1-393x852-paladin-en-2-protection.png")
            openTab("Retribution")
            shoot("m5rel1-393x852-paladin-en-3-retribution.png")
        }
        onPhone(paladin, 393, 852, language = AppLanguage.RUSSIAN) {
            shoot("m5rel1-393x852-paladin-ru-1-svet.png")
            openTab("Защита")
            shoot("m5rel1-393x852-paladin-ru-2-zashchita.png")
            openTab("Воздаяние")
            shoot("m5rel1-393x852-paladin-ru-3-vozdayanie.png")
        }

        val mage = readyStateOf("MAGE", "Arcane raid PvE")
        onPhone(mage, 393, 852) {
            shoot("m5rel1-393x852-mage-en-1-arcane.png")
            openTab("Fire")
            shoot("m5rel1-393x852-mage-en-2-fire.png")
            openTab("Frost")
            shoot("m5rel1-393x852-mage-en-3-frost.png")
        }
        onPhone(mage, 393, 852, language = AppLanguage.RUSSIAN) {
            shoot("m5rel1-393x852-mage-ru-1-taynaya-magiya.png")
            openTab("Огонь")
            shoot("m5rel1-393x852-mage-ru-2-ogon.png")
            openTab("Лёд")
            shoot("m5rel1-393x852-mage-ru-3-lyod.png")
        }
    }

    @Test
    fun landscapeNavigationNarrowWidthAndDoubleFontScale() {
        val paladin = readyStateOf("PALADIN", "Retlev, Holy leveling")
        onPhone(paladin, 915, 412) {
            shoot("m5rel1-915x412-landscape-en-1-holy.png")
            openTab("Protection")
            shoot("m5rel1-915x412-landscape-en-2-protection.png")
            openTab("Retribution")
            shoot("m5rel1-915x412-landscape-en-3-retribution.png")
        }
        onPhone(paladin, 915, 412, language = AppLanguage.RUSSIAN) {
            openTab("Воздаяние")
            shoot("m5rel1-915x412-landscape-ru-vozdayanie.png")
        }

        // 320 dp phone: the row scrolls, each tab keeps ≥ 72 dp
        val hunter = readyStateOf("HUNTER", "Beast leveling")
        onPhone(hunter, 320, 568) {
            shoot("m5rel1-320x568-hunter-en-beast-mastery.png")
        }
        onPhone(hunter, 320, 568, language = AppLanguage.RUSSIAN) {
            shoot("m5rel1-320x568-hunter-ru-povelitel-zverey.png")
        }

        // 200 % font scale: the tabs read their position numbers (1A v2 S8)
        val mage = readyStateOf("MAGE", "Arcane raid PvE")
        onPhone(mage, 393, 852, fontScale = 2f) {
            shoot("m5rel1-393x852-mage-en-font200.png")
        }
        onPhone(mage, 393, 852, language = AppLanguage.RUSSIAN, fontScale = 2f) {
            shoot("m5rel1-393x852-mage-ru-font200.png")
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5rel1.screenshots"
        const val DENSITY = 2f
    }
}
