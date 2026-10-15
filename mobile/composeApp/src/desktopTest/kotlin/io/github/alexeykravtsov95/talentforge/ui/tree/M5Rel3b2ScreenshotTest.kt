package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.lang.LocalAppLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule

/**
 * M5.REL.3b.2 PR evidence: the REAL editor with the bundled product data, its icons, English
 * descriptions and the verified ruRU overlay, desktop-rendered — the same talents in the
 * English and the Russian inspector (unspent and with a CURRENT / NEXT RANK comparison), a
 * Russian name over its English description, the longest Russian description and the longest
 * Russian name in portrait (expanded sheet) and landscape (side panel), at 100 % and 200 %,
 * and a corrupt overlay falling back to English.
 *
 * Skipped unless the caller opts in (evidence, not a regression assertion — the assertions
 * live in `TalentLocalizationUiTest` and `EditorLocalizationLayoutUiTest`):
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*M5Rel3b2ScreenshotTest*" \
 *     -Dtalentforge.m5rel3b2.screenshots=<output-dir>
 * ```
 */
@OptIn(ExperimentalTestApi::class)
class M5Rel3b2ScreenshotTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val outputDir: File
        get() = File(System.getProperty(PROPERTY)!!)

    @BeforeTest
    fun setUp() {
        assumeTrue("screenshot capture not requested", System.getProperty(PROPERTY) != null)
        outputDir.mkdirs()
    }

    private fun onEditor(
        language: AppLanguage,
        state: TalentTreeUiState.Ready,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        section: Int? = null,
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        val cameras = TalentTreeSectionCameras("rel3b2-shots", state.model.treeId, initialSelectedSection = section)
        runDesktopComposeUiTest(width = (widthDp * DENSITY).toInt(), height = (heightDp * DENSITY).toInt()) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(DENSITY, fontScale),
                        LocalReducedMotion provides true,
                        LocalAppLanguage provides language,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                            TalentTreeScreen(state = state, onBack = {}, sectionCameras = cameras)
                        }
                    }
                }
            }
            waitForIdle()
            block()
        }
    }

    private fun ComposeUiTest.select(talent: String) {
        onNodeWithContentDescription(talent).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.expand(language: AppLanguage) {
        onNodeWithContentDescription(EditorCopy.of(language).expand).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.shoot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outputDir, name))
    }

    /** A real build with one rank in [target] (its section's open talents spent first). */
    private fun TalentTreeUiState.Ready.withOneRank(target: Int): TalentTreeUiState.Ready {
        val section = engine.tree.nodes.single { it.id == target }.sectionIndex
        var build = engine.emptyState()
        while (!engine.canSpend(build, target).allowed) {
            val next = engine.tree.nodes
                .filter { it.id != target && engine.canSpend(build, it.id).allowed }
                .minWith(compareBy({ it.sectionIndex != section }, { it.id }))
            build = engine.spend(build, next.id).state
        }
        return copy(buildState = engine.spend(build, target).state)
    }

    private fun TalentTreeUiState.Ready.nodeOf(englishName: String) = engine.tree.nodes.single { it.name == englishName }

    @Test
    fun m5rel3b2RussianTalentText() {
        val warrior = localizedProductState("WARRIOR")
        val rend = warrior.nodeOf(WarriorText.REND_EN)

        // the same talent in both languages: rank-1 preview (compact) and 1/3 with the comparison
        for (language in AppLanguage.entries) {
            val tag = if (language == AppLanguage.RUSSIAN) "ru" else "en"
            val name = if (language == AppLanguage.RUSSIAN) WarriorText.REND_RU else WarriorText.REND_EN
            onEditor(language, warrior) {
                select(name)
                shoot("m5rel3b2-$tag-393x852-compact.png")
            }
            onEditor(language, warrior.withOneRank(rend.id)) {
                select(name)
                expand(language)
                shoot("m5rel3b2-$tag-393x852-expanded-comparison.png")
            }
            onEditor(language, warrior.withOneRank(rend.id), widthDp = 915, heightDp = 412) {
                select(name)
                shoot("m5rel3b2-$tag-915x412-landscape-comparison.png")
            }
        }

        // a Russian name over its English description (Improved Slam: unverified decimal)
        onEditor(AppLanguage.RUSSIAN, warrior) {
            select("Улучшенное сокрушение")
            expand(AppLanguage.RUSSIAN)
            shoot("m5rel3b2-ru-393x852-russian-name-english-description.png")
        }

        // the longest Russian description (Warlock) and the longest Russian name (Warrior)
        val warlock = localizedProductState("WARLOCK")
        val localization = warlock.localization as TalentNodeLocalization.Loaded
        val longest = localization.ranks.maxBy { (_, ranks) -> ranks.maxOf { r -> r.segments.sumOf { it.text.length } } }.key
        val longestNode = warlock.engine.tree.nodes.single { it.id == longest }
        val longestName = localization.names.getValue(longest)
        for ((size, fontScale) in listOf((393 to 852) to 1f, (393 to 852) to 2f, (915 to 412) to 1f, (915 to 412) to 2f)) {
            val (width, height) = size
            val scale = "${(fontScale * 100).toInt()}"
            onEditor(AppLanguage.RUSSIAN, warlock.withOneRank(longest), width, height, fontScale, longestNode.sectionIndex) {
                select(longestName)
                if (width < height) expand(AppLanguage.RUSSIAN)
                shoot("m5rel3b2-ru-${width}x$height-font$scale-longest-description.png")
            }
        }
        val tactical = warrior.nodeOf("Improved Tactical Mastery")
        for ((size, fontScale) in listOf((320 to 568) to 2f, (915 to 412) to 2f)) {
            val (width, height) = size
            onEditor(AppLanguage.RUSSIAN, warrior, width, height, fontScale, tactical.sectionIndex) {
                select(WarriorText.TACTICAL_MASTERY_RU)
                if (width < height) expand(AppLanguage.RUSSIAN)
                shoot("m5rel3b2-ru-${width}x$height-font200-longest-name.png")
            }
        }

        // a corrupt overlay: the whole class falls back to English under the Russian UI copy
        val path = "localization/forever/${PRODUCT_BUNDLE.clientBuild}/ruRU/warrior.json"
        val corrupt = runBlocking {
            io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource().read(path)
        }.decodeToString().replace(WarriorText.REND_RU, "Подделка").encodeToByteArray()
        val fallback = localizedStateOnBundle(PRODUCT_BUNDLE.clientBuild, "WARRIOR", OverridingAssetSource(mapOf(path to corrupt)))
        onEditor(AppLanguage.RUSSIAN, fallback) {
            select(WarriorText.REND_EN)
            shoot("m5rel3b2-ru-393x852-corrupt-overlay-english.png")
        }
    }

    private companion object {
        const val PROPERTY = "talentforge.m5rel3b2.screenshots"
        const val DENSITY = 2f
    }
}
