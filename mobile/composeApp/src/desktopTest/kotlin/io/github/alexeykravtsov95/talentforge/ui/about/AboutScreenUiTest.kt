package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.library.dataVersionOf
import io.github.alexeykravtsov95.talentforge.ui.ota.OtaStatus
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTypography
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.UI.ABOUT.1/2: the simplified About screen in English and Russian — the
 * icon/name/installed version header (the version name from the host, never
 * invented, and never the internal build number), the talent data in use and
 * its source, about the project (description and the independent-project
 * statement), the compact privacy block whose every statement stays one
 * explicit toggle away, the published policy link of the UI language where a
 * policy is published for the platform and none on iOS (M5.LEGAL.LINK), the
 * quiet open-source licenses link at the bottom, and NO technology,
 * dependency or module names; 48 dp targets at 320 dp / 200 %; and Home →
 * About → Licenses → Back → Back through the real app root. The licenses
 * themselves: `LicensesScreenUiTest`.
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
        language: AppLanguage = AppLanguage.ENGLISH,
        appVersion: AppVersion? = version,
        backup: BackupStatement = BackupStatement.AndroidBackupDisabled,
        policy: PublishedPrivacyPolicy = PublishedPrivacyPolicy.Android,
        privacyPolicyUrl: String? = privacyPolicyUrlOf(policy, language),
        onBack: () -> Unit = {},
        onOpenLicenses: () -> Unit = {},
        /** M5.OTA.PROD.3: an OTA-enabled build (the data in use and "check now" are shown). */
        ota: Boolean = false,
    ): @Composable () -> Unit = {
        AboutScreen(
            appVersion = appVersion,
            backupStatement = backup,
            privacyPolicyUrl = privacyPolicyUrl,
            onBack = onBack,
            onOpenLicenses = onOpenLicenses,
            otaStatus = if (ota) OtaStatus(productBuild = PRODUCT_BUNDLE.clientBuild, productReleaseId = null) else null,
            onCheckForUpdates = if (ota) ({}) else null,
        )
    }

    private val dataVersion = dataVersionOf(PRODUCT_BUNDLE.clientBuild)

    // ------------------------------------------------------------------- English

    @Test
    fun englishAboutShowsTheHeaderDataStatementAndCompactPrivacy() = onScreen(content = about()) {
        awaitText("About")
        awaitText("TalentForge")
        awaitText("Version 0.1.0")
        assertAbsent("Version 0.1.0 (1)")
        awaitText("TALENT DATA")
        awaitText("Forever · talent data $dataVersion")
        awaitText("Included with the app")
        awaitText("ABOUT THE PROJECT")
        awaitText(
            "A talent planner: spend points across the talent trees, keep your builds on this device and share them as codes.",
        )
        awaitText(
            "TalentForge is an independent, unofficial project. It is not affiliated with, endorsed by or " +
                "sponsored by the game's developer or publisher.",
        )
        awaitText("PRIVACY")
        awaitText("TalentForge works offline, without an account, ads or analytics", substring = true)
        awaitText("Show details")
        awaitText("Open-source licenses")
        // compact: the detailed statements wait behind the toggle
        assertAbsent("The app turns off Android backup", substring = true)
        // OTA is off: the data is the app's own; no game build line and no check action
        assertAbsent("Check for updates")
        assertAbsent("Game build", substring = true)
        // the published policy link of the UI language (M5.LEGAL.LINK)
        awaitText("Privacy policy")
    }

    @Test
    fun theMainScreenNamesNoTechnologyDependencyOrModule() {
        for (language in AppLanguage.entries) {
            onScreen(language = language, content = about(language)) {
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

    // M5.OTA.PROD.3: an OTA-enabled build never claims to work without the internet

    @Test
    fun anOtaEnabledBuildSaysWhatItDownloads() = onScreen(content = about(ota = true)) {
        awaitText("Check for updates")
        awaitText("Game build ${PRODUCT_BUNDLE.clientBuild}")
        awaitText("Included with the app")
        awaitText("Your builds stay on this device; only talent data updates are downloaded from the internet.", substring = true)
        assertAbsent("works offline", substring = true)

        clickText("Show details")
        awaitText("from ota.mythweld.com", substring = true)
        awaitText("may process your device's IP address and the time of the request", substring = true)
        awaitText("no account, device identifier or anything about your builds", substring = true)
        awaitText("The app uses the internet only to download talent data updates", substring = true)
        awaitText("Your builds — class, talents, game-data version", substring = true)
        assertAbsent("without the internet", substring = true)
    }

    @Test
    fun aRussianOtaEnabledBuildSaysWhatItDownloads() = onScreen(language = AppLanguage.RUSSIAN, content = about(AppLanguage.RUSSIAN, ota = true)) {
        awaitText("из интернета загружаются только обновления данных талантов", substring = true)
        assertAbsent("работает без интернета", substring = true)

        clickText("Подробнее")
        awaitText("с ota.mythweld.com", substring = true)
        awaitText("могут обрабатывать IP-адрес устройства и время запроса", substring = true)
        awaitText("Приложение использует интернет только для загрузки обновлений данных талантов", substring = true)
        assertAbsent("Приложение работает без интернета", substring = true)
    }

    @Test
    fun theOtaEnabledScreenNamesNoTechnologyDependencyOrModule() {
        for (language in AppLanguage.entries) {
            onScreen(language = language, content = about(language, ota = true)) {
                clickText(if (language == AppLanguage.RUSSIAN) "Подробнее" else "Show details")
                val shown = texts().joinToString("\n")
                for (term in TECHNOLOGY_TERMS) {
                    assertTrue(term !in shown.lowercase(), "'$term' is on the OTA-enabled About screen ($language): $shown")
                }
            }
        }
    }

    @Test
    fun theLicensesRowOpensTheLicenses() {
        var opened = 0
        onScreen(content = about(onOpenLicenses = { opened += 1 })) {
            awaitText("Open-source licenses")
            onAllNodes(clickLabelled("open the open-source licenses")).onFirst().performScrollTo().performClick()
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
    fun russianAboutIsFullyLocalized() = onScreen(language = AppLanguage.RUSSIAN, content = about(AppLanguage.RUSSIAN)) {
        awaitText("О приложении")
        awaitText("Версия 0.1.0")
        assertAbsent("Версия 0.1.0 (1)")
        awaitText("ДАННЫЕ ТАЛАНТОВ")
        awaitText("Forever · данные талантов $dataVersion")
        awaitText("Встроены в приложение")
        awaitText("О ПРОЕКТЕ")
        awaitText(
            "Планировщик талантов: распределяйте очки по веткам талантов, храните билды на этом устройстве " +
                "и делитесь ими в виде кодов.",
        )
        awaitText(
            "TalentForge — независимый неофициальный проект. Он не связан с разработчиком и издателем игры, " +
                "не одобрен и не поддерживается ими.",
        )
        awaitText("КОНФИДЕНЦИАЛЬНОСТЬ")
        awaitText("Политика конфиденциальности")
        awaitText("Лицензии открытого ПО")
        assertAbsent("Приложение отключает резервное копирование Android", substring = true)
        clickText("Подробнее")
        awaitText("Приложение отключает резервное копирование Android", substring = true)
        awaitText("Скрыть подробности")
        assertEquals(1, onAllNodes(clickLabelled("открыть лицензии открытого ПО")).fetchSemanticsNodes().size)
        for (english in listOf("About", "Show details", "Hide details", "Open-source licenses", "PRIVACY", "Privacy policy", "Included with the app")) {
            assertAbsent(english)
        }
    }

    // ------------------------------------------------------- M5.UI.ABOUT.2

    @Test
    fun theVersionNameIsShownWithoutTheInternalBuildNumber() {
        for (language in AppLanguage.entries) {
            val ru = language == AppLanguage.RUSSIAN
            onScreen(language = language, content = about(language, appVersion = AppVersion(name = "1.0.0", code = 2))) {
                awaitText(if (ru) "Версия 1.0.0" else "Version 1.0.0")
                assertAbsent("(2)", substring = true)
                assertTrue(texts().none { it.contains("1.0.0") && it.contains('2') }, "the build number is shown: ${texts()}")
            }
        }
    }

    @Test
    fun theSectionsComeInOrderAndTheLicensesLinkIsLast() {
        for ((language, ota) in AppLanguage.entries.flatMap { language -> listOf(language to false, language to true) }) {
            val ru = language == AppLanguage.RUSSIAN
            onScreen(language = language, content = about(language, ota = ota)) {
                val order = if (ru) {
                    listOf("TalentForge", "ДАННЫЕ ТАЛАНТОВ", "О ПРОЕКТЕ", "КОНФИДЕНЦИАЛЬНОСТЬ", "Лицензии открытого ПО")
                } else {
                    listOf("TalentForge", "TALENT DATA", "ABOUT THE PROJECT", "PRIVACY", "Open-source licenses")
                }
                order.forEach { awaitText(it) }
                val tops = order.map { text -> onAllNodesWithText(text).onFirst().fetchSemanticsNode().positionInRoot.y }
                assertEquals(tops.sorted(), tops, "$language OTA=$ota: $order at $tops")
                // the link is the last target on the screen and keeps 48 dp
                val link = onAllNodes(clickLabelled(if (ru) "открыть лицензии открытого ПО" else "open the open-source licenses"))
                    .fetchSemanticsNodes().single()
                assertUsableTarget(link, widthDp = 393)
                val lastTarget = onAllNodes(anyClickable).fetchSemanticsNodes().maxBy { it.positionInRoot.y }
                assertEquals(link.id, lastTarget.id, "$language OTA=$ota: the licenses link is not the last action")
            }
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
    fun thePublishedPolicyPageFollowsTheUiLanguage() {
        for (language in AppLanguage.entries) {
            val handler = RecordingUriHandler()
            onScreen(language = language, uriHandler = handler, content = about(language)) {
                // visible without opening the details, labelled in the UI language
                clickText(if (language == AppLanguage.RUSSIAN) "Политика конфиденциальности" else "Privacy policy")
                assertEquals(listOf(privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, language)), handler.opened)
            }
        }
    }

    @Test
    fun iosShowsThePrivacySummaryWithoutTheAndroidPolicyLink() {
        for (language in AppLanguage.entries) {
            val ru = language == AppLanguage.RUSSIAN
            onScreen(
                language = language,
                content = about(language, backup = BackupStatement.IosDeviceBackup, policy = PublishedPrivacyPolicy.NotPublished),
            ) {
                // the local summary stays: its first statement and every detail behind the toggle
                awaitText(if (ru) "КОНФИДЕНЦИАЛЬНОСТЬ" else "PRIVACY")
                awaitText(if (ru) "TalentForge работает без интернета" else "TalentForge works offline", substring = true)
                clickText(if (ru) "Подробнее" else "Show details")
                awaitText(if (ru) "Если на устройстве включено резервное копирование" else "If your device's backup (iCloud or a computer) is on", substring = true)
                // but no link: the published policy is the Android app's
                assertAbsent("Privacy policy")
                assertAbsent("Политика конфиденциальности")
                assertEquals(0, onAllNodes(clickLabelled(if (ru) "открыть политику конфиденциальности в браузере" else "open the privacy policy in the browser")).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun theAppOpensThePublishedPolicyPageOfItsUiLanguage() {
        for (language in AppLanguage.entries) {
            val handler = RecordingUriHandler()
            val ru = language == AppLanguage.RUSSIAN
            onScreen(
                language = language,
                uriHandler = handler,
                content = { App(storage = FakeBuildLibraryStorage(), clock = FixedClock(), ids = SequentialIds(), appVersion = version) },
            ) {
                awaitText(if (ru) "Новый билд" else "New build")
                onAllNodes(clickLabelled(if (ru) "открыть сведения о TalentForge" else "open About TalentForge")).onFirst().performClick()
                awaitText(if (ru) "О приложении" else "Version 0.1.0")
                clickText(if (ru) "Политика конфиденциальности" else "Privacy policy")
                // the desktop host is the Android UI's: the app root passes the Android policy
                assertEquals(listOf(privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, language)), handler.opened)
            }
        }
    }

    @Test
    fun largeTextOnANarrowPhoneKeepsEveryActionReachable() {
        for (language in AppLanguage.entries) {
            onScreen(language = language, widthDp = 320, heightDp = 640, fontScale = 2f, content = about(language)) {
                awaitText(if (language == AppLanguage.RUSSIAN) "Лицензии открытого ПО" else "Open-source licenses")
                val targets = onAllNodes(anyClickable).fetchSemanticsNodes()
                val labels = targets.map { it.config.getOrNull(SemanticsActions.OnClick)?.label }
                assertEquals(4, targets.size, "Back, the privacy toggle, the policy link and the licenses row: $labels")
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
        // where it fits, the name keeps the frozen ScreenTitle size (24 sp): it only ever shrinks to fit.
        // Read from the glyphs: an auto-sized layout reports its declared style whatever size it drew.
        var fullSizeGlyph = Rect.Zero
        val withReference: @Composable () -> Unit = {
            fullSizeGlyph = rememberTextMeasurer().measure("TalentForge", TalentForgeTypography.title, softWrap = false).getBoundingBox(0)
            about()()
        }
        onScreen(content = withReference) {
            val glyph = nameLayout().second.getBoundingBox(0)
            assertTrue(
                abs(glyph.width - fullSizeGlyph.width) < 0.01f && abs(glyph.height - fullSizeGlyph.height) < 0.01f,
                "the name shrank although it fits: $glyph vs $fullSizeGlyph at 24 sp",
            )
        }
        // and where the full-size word does not fit, it is drawn smaller
        onScreen(widthDp = 260, heightDp = 640, fontScale = 2f, content = withReference) {
            val glyph = nameLayout().second.getBoundingBox(0)
            assertTrue(glyph.width < fullSizeGlyph.width, "the name kept 24 sp although it does not fit 260 dp at 200 %")
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
        awaitText("Version 0.1.0")

        onAllNodes(clickLabelled("open the open-source licenses")).onFirst().performScrollTo().performClick()
        awaitText("AndroidX (Android Jetpack)")
        assertAbsent("Version 0.1.0")

        // a group and a licence text close first, one Back each
        onAllNodes(clickLabelled("open AndroidX (Android Jetpack)")).onFirst().performScrollTo().performClick()
        awaitText("LICENSE TEXTS")
        onAllNodes(clickLabelled("open the text of Apache License 2.0")).onFirst().performScrollTo().performClick()
        awaitText("TERMS AND CONDITIONS", substring = true)
        onAllNodesWithContentDescription("go back").onFirst().performClick()
        awaitText("LICENSE TEXTS")
        onAllNodesWithContentDescription("go back").onFirst().performClick()
        awaitText("COMPONENTS")

        onAllNodesWithContentDescription("go back").onFirst().performClick()
        awaitText("Version 0.1.0")
        assertAbsent("AndroidX (Android Jetpack)")

        onAllNodesWithContentDescription("go back").onFirst().performClick()
        awaitText("New build")
        assertAbsent("Version 0.1.0")
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
        awaitText("О ПРОЕКТЕ")
        onAllNodes(clickLabelled("открыть лицензии открытого ПО")).onFirst().performScrollTo().performClick()
        awaitText("Лицензии открытого ПО")
        awaitText("КОМПОНЕНТЫ")
        onAllNodesWithContentDescription("назад").onFirst().performClick()
        awaitText("О ПРОЕКТЕ")
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
