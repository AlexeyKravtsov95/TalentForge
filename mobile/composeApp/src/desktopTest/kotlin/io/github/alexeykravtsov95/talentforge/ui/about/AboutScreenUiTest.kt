package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.dataVersionOf
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.UI.ABOUT.1: the simplified About screen in English and Russian — the
 * icon/name/description/installed version header (the version from the host,
 * never invented), the talent data in use, the independent-project statement,
 * the compact privacy block whose every statement stays one explicit toggle
 * away, the policy link only when configured, the one row into the
 * third-party licenses, and NO technology, dependency or module names; 48 dp
 * targets at 320 dp / 200 %; and Home → About → Licenses → Back → Back
 * through the real app root. The licenses themselves: `LicensesScreenUiTest`.
 */
@OptIn(ExperimentalTestApi::class)
class AboutScreenUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val version = AppVersion(name = "0.1.0", code = 1)

    private fun onScreen(
        language: AppLanguage = AppLanguage.ENGLISH,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        uriHandler: RecordingUriHandler = RecordingUriHandler(),
        content: @Composable () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) = uiLanguage.runAboutUiTest(language, widthDp, heightDp, fontScale, uriHandler, content, block)

    private fun about(
        appVersion: AppVersion? = version,
        backup: BackupStatement = BackupStatement.AndroidBackupDisabled,
        privacyPolicyUrl: String? = null,
        onBack: () -> Unit = {},
        onOpenLicenses: () -> Unit = {},
    ): @Composable () -> Unit = {
        AboutScreen(
            appVersion = appVersion,
            backupStatement = backup,
            privacyPolicyUrl = privacyPolicyUrl,
            onBack = onBack,
            onOpenLicenses = onOpenLicenses,
        )
    }

    private val dataVersion = dataVersionOf(PRODUCT_BUNDLE.clientBuild)

    // ------------------------------------------------------------------- English

    @Test
    fun englishAboutShowsTheHeaderDataStatementAndCompactPrivacy() = onScreen(content = about()) {
        awaitText("About")
        awaitText("TalentForge")
        awaitText("Version 0.1.0 (1)")
        awaitText(
            "A talent planner: spend points across the talent trees, keep your builds on this device and share them as codes.",
        )
        awaitText("TALENT DATA")
        awaitText("Forever · talent data $dataVersion")
        awaitText("INDEPENDENT PROJECT")
        awaitText(
            "TalentForge is an independent, unofficial project. It is not affiliated with, endorsed by or " +
                "sponsored by the game's developer or publisher.",
        )
        awaitText("PRIVACY")
        awaitText("TalentForge works offline, without an account, ads or analytics", substring = true)
        awaitText("Show details")
        awaitText("Third-party licenses")
        awaitText("Open-source notices and full license texts")
        // compact: the detailed statements wait behind the toggle
        assertAbsent("The app turns off Android backup", substring = true)
        // OTA is off: no data-source line and no check action
        assertAbsent("Check for updates")
        assertAbsent("Included with the app")
        // no approved policy URL (LR-5): no link and no placeholder
        assertAbsent("Privacy policy")
    }

    @Test
    fun theMainScreenNamesNoTechnologyDependencyOrModule() {
        for (language in AppLanguage.entries) {
            onScreen(language = language, content = about()) {
                clickText(if (language == AppLanguage.RUSSIAN) "Подробнее" else "Show details")
                val shown = texts().joinToString("\n")
                for (term in TECHNOLOGY_TERMS) {
                    assertTrue(term !in shown.lowercase(), "'$term' is on the main About screen ($language): $shown")
                }
            }
        }
    }

    @Test
    fun everyPrivacyStatementIsOneToggleAway() = onScreen(content = about()) {
        clickText("Show details")
        awaitText("Your builds — class, talents, game-data version", substring = true)
        awaitText("The app turns off Android backup and transfer of its data to a new device", substring = true)
        awaitText("The app writes to the clipboard only when you tap Copy code", substring = true)
        awaitText("The app works without the internet and does not ask for access", substring = true)
        awaitText("Delete a build in the app, or uninstall the app or clear its storage", substring = true)
        awaitText("Hide details")

        clickText("Hide details")
        assertAbsent("The app turns off Android backup", substring = true)
        awaitText("TalentForge works offline", substring = true)
    }

    @Test
    fun theLicensesRowOpensTheLicenses() {
        var opened = 0
        onScreen(content = about(onOpenLicenses = { opened += 1 })) {
            awaitText("Third-party licenses")
            onAllNodes(clickLabelled("open the third-party licenses")).onFirst().performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, opened)
        }
    }

    @Test
    fun backIsTheVisibleChevron() {
        var backs = 0
        onScreen(content = about(onBack = { backs += 1 })) {
            awaitText("About")
            onAllNodesWithContentDescription("go back").onFirst().performClick()
            waitForIdle()
            assertEquals(1, backs)
        }
    }

    // ------------------------------------------------------------------- Russian

    @Test
    fun russianAboutIsFullyLocalized() = onScreen(language = AppLanguage.RUSSIAN, content = about()) {
        awaitText("О приложении")
        awaitText("Версия 0.1.0 (1)")
        awaitText(
            "Планировщик талантов: распределяйте очки по веткам талантов, храните билды на этом устройстве " +
                "и делитесь ими в виде кодов.",
        )
        awaitText("ДАННЫЕ ТАЛАНТОВ")
        awaitText("Forever · данные талантов $dataVersion")
        awaitText("НЕЗАВИСИМЫЙ ПРОЕКТ")
        awaitText(
            "TalentForge — независимый неофициальный проект. Он не связан с разработчиком и издателем игры, " +
                "не одобрен и не поддерживается ими.",
        )
        awaitText("КОНФИДЕНЦИАЛЬНОСТЬ")
        awaitText("Лицензии сторонних компонентов")
        awaitText("Уведомления об открытом ПО и полные тексты лицензий")
        assertAbsent("Приложение отключает резервное копирование Android", substring = true)
        clickText("Подробнее")
        awaitText("Приложение отключает резервное копирование Android", substring = true)
        awaitText("Скрыть подробности")
        assertEquals(1, onAllNodes(clickLabelled("открыть лицензии сторонних компонентов")).fetchSemanticsNodes().size)
        for (english in listOf("About", "Show details", "Hide details", "Third-party licenses", "PRIVACY")) {
            assertAbsent(english)
        }
    }

    // --------------------------------------------------------- platform variants

    @Test
    fun withoutAVersionNothingIsInventedAndIosGetsItsBackupStatement() = onScreen(
        content = about(appVersion = null, backup = BackupStatement.IosDeviceBackup),
    ) {
        awaitText("TalentForge")
        assertAbsent("Version ", substring = true)
        clickText("Show details")
        awaitText("If your device's backup (iCloud or a computer) is on", substring = true)
        assertAbsent("The app turns off Android backup", substring = true)
    }

    @Test
    fun aConfiguredPolicyUrlBecomesAnExplicitLink() {
        val handler = RecordingUriHandler()
        val url = "https://policy.example.org/talentforge"
        onScreen(uriHandler = handler, content = about(privacyPolicyUrl = url)) {
            // visible without opening the details
            clickText("Privacy policy")
            assertEquals(listOf(url), handler.opened)
        }
    }

    @Test
    fun largeTextOnANarrowPhoneKeepsEveryActionReachable() {
        for (language in AppLanguage.entries) {
            onScreen(language = language, widthDp = 320, heightDp = 640, fontScale = 2f, content = about()) {
                awaitText(if (language == AppLanguage.RUSSIAN) "Лицензии сторонних компонентов" else "Third-party licenses")
                val targets = onAllNodes(anyClickable).fetchSemanticsNodes()
                val labels = targets.map { it.config.getOrNull(SemanticsActions.OnClick)?.label }
                assertEquals(3, targets.size, "Back, the privacy toggle and the licenses row: $labels")
                for (target in targets) {
                    if (target.config.getOrNull(SemanticsActions.OnClick)?.label == BACK_LABEL[language]) {
                        // the shared header Back control (TalentTreeHeaderMetrics, 44 dp)
                        assertTrue(target.size.height >= 44 && target.size.width >= 44, "Back ${target.size} < 44 dp")
                    } else {
                        assertUsableTarget(target, widthDp = 320)
                    }
                }
            }
        }
    }

    /**
     * The name is one word: it never breaks, is never cut, and never runs past
     * the screen, whatever the platform font's real width. Widths down to
     * 260 dp at 200 % leave less room than "TalentForge" needs at full size
     * with any font (Linux CI's font is wider than macOS's), so the fit is
     * proven by measurement, not by one font happening to be narrow enough.
     */
    @Test
    fun theAppNameStaysOneWholeWordAtAnyWidthAndFontScale() {
        for (widthDp in listOf(320, 300, 280, 260)) {
            onScreen(widthDp = widthDp, heightDp = 640, fontScale = 2f, content = about()) {
                val (node, layout) = nameLayout()
                val where = "$widthDp dp @200 %"
                assertEquals("TalentForge", layout.layoutInput.text.text, "$where: the name is shown whole")
                assertEquals(1, layout.lineCount, "$where: the name breaks across lines")
                assertTrue(!layout.hasVisualOverflow, "$where: the name is clipped (${layout.size} for ${layout.multiParagraph.width})")
                assertTrue(
                    node.positionInRoot.x + layout.size.width <= widthDp,
                    "$where: the name runs past the screen (x ${node.positionInRoot.x} + ${layout.size.width})",
                )
            }
        }
        // where it fits, the name keeps the frozen ScreenTitle size (24 sp): it only ever shrinks to fit
        onScreen(content = about()) {
            assertEquals(TalentForgeTypography.title.fontSize, nameLayout().second.layoutInput.style.fontSize)
        }
    }

    private fun ComposeUiTest.nameLayout(): Pair<SemanticsNode, TextLayoutResult> {
        awaitText("TalentForge")
        val node = onAllNodesWithText("TalentForge").onFirst().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return node to layouts.single()
    }

    // ------------------------------------------------------------- through App

    @Test
    fun homeOpensAboutThenLicensesAndBackReturnsStepByStep() = onScreen(
        content = { App(storage = FakeBuildLibraryStorage(), clock = FixedClock(), ids = SequentialIds(), appVersion = version) },
    ) {
        awaitText("New build")
        // the Home entry: a 48 dp glyph named "About", one line with the wordmark
        val entry = onAllNodesWithContentDescription("About").fetchSemanticsNodes().single()
        assertTrue(entry.size.width >= 48 && entry.size.height >= 48, "About entry ${entry.size} < 48 dp")
        onAllNodes(clickLabelled("open About TalentForge")).onFirst().performClick()
        awaitText("Version 0.1.0 (1)")

        onAllNodes(clickLabelled("open the third-party licenses")).onFirst().performScrollTo().performClick()
        awaitText("AndroidX (Android Jetpack)")
        assertAbsent("Version 0.1.0 (1)")

        onAllNodesWithContentDescription("go back").onFirst().performClick()
        awaitText("Version 0.1.0 (1)")
        assertAbsent("AndroidX (Android Jetpack)")

        onAllNodesWithContentDescription("go back").onFirst().performClick()
        awaitText("New build")
        assertAbsent("Version 0.1.0 (1)")
    }

    @Test
    fun theRussianRouteWorksOnANarrowPhoneWithLargeText() = onScreen(
        language = AppLanguage.RUSSIAN,
        widthDp = 320,
        heightDp = 568,
        fontScale = 2f,
        content = { App(storage = FakeBuildLibraryStorage(), clock = FixedClock(), ids = SequentialIds()) },
    ) {
        awaitText("Новый билд")
        onAllNodes(clickLabelled("открыть сведения о TalentForge")).onFirst().performClick()
        awaitText("О приложении")
        awaitText("НЕЗАВИСИМЫЙ ПРОЕКТ")
        onAllNodes(clickLabelled("открыть лицензии сторонних компонентов")).onFirst().performScrollTo().performClick()
        awaitText("Лицензии сторонних компонентов")
        awaitText("Apache License 2.0 · 88 модулей")
        onAllNodesWithContentDescription("назад").onFirst().performClick()
        awaitText("НЕЗАВИСИМЫЙ ПРОЕКТ")
    }

    private companion object {
        /** Names the main About screen must not show (lower case); they belong to the licenses screen. */
        val TECHNOLOGY_TERMS = listOf(
            "kotlin", "compose", "androidx", "jetpack", "jetbrains", "multiplatform", "skia", "skiko",
            "gradle", "guava", "kotlinx", "ktor", "apache", "bsd", "mit license", "module", "модул",
            "library", "библиотек", "sdk", "framework",
        )

        val BACK_LABEL = mapOf(AppLanguage.ENGLISH to "go back", AppLanguage.RUSSIAN to "назад")
    }
}
