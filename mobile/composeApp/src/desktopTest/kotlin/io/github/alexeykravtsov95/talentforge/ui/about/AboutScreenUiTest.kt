package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.App
import io.github.alexeykravtsov95.talentforge.ui.FakeBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.FixedClock
import io.github.alexeykravtsov95.talentforge.ui.SequentialIds
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * M5.RC.2b: the About / legal screen in English and Russian, driven by the
 * REAL bundled Android notices and licence text: the version from the host
 * (never invented), the independent-project statement, the platform's backup
 * statement, the privacy-policy link only when one is configured, the notices
 * and licence text behind explicit 48 dp toggles, Back, and the Home → About →
 * Back route through the real app root.
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
        uriHandler: UriHandler = RecordingUriHandler(),
        content: @Composable () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = widthDp, height = heightDp) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, fontScale),
                        LocalReducedMotion provides true,
                        LocalUriHandler provides uriHandler,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }
                    }
                }
            }
            block()
        }
    }

    private fun about(
        appVersion: AppVersion? = version,
        noticesPath: String? = ANDROID_OSS_NOTICES_PATH,
        backup: BackupStatement = BackupStatement.AndroidBackupDisabled,
        privacyPolicyUrl: String? = null,
        onBack: () -> Unit = {},
    ): @Composable () -> Unit = {
        AboutScreen(
            appVersion = appVersion,
            noticesPath = noticesPath,
            backupStatement = backup,
            privacyPolicyUrl = privacyPolicyUrl,
            onBack = onBack,
        )
    }

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ComposeUiTest.awaitText(text: String, substring: Boolean = false) {
        try {
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("'$text' never appeared; on screen: ${texts()}", timeout)
        }
    }

    private fun ComposeUiTest.clickText(text: String) {
        awaitText(text)
        onAllNodesWithText(text).onFirst().performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.assertAbsent(text: String, substring: Boolean = false) {
        assertTrue(
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty(),
            "'$text' must not be shown; on screen: ${texts()}",
        )
    }

    private fun clickLabelled(label: String) = SemanticsMatcher("click label '$label'") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    // ------------------------------------------------------------------- English

    @Test
    fun englishAboutShowsVersionStatementPrivacyAndTheRealNotices() = onScreen(content = about()) {
        awaitText("About")
        awaitText("TalentForge")
        awaitText("Version 0.1.0 (1)")
        awaitText("INDEPENDENT PROJECT")
        awaitText(
            "TalentForge is an independent, unofficial project. It is not affiliated with, endorsed by or " +
                "sponsored by the game's developer or publisher.",
        )
        awaitText("PRIVACY")
        awaitText("The app turns off Android backup", substring = true)
        awaitText("The app works without the internet", substring = true)
        awaitText("OPEN-SOURCE LICENSES")
        for (component in listOf("AndroidX (Android Jetpack)", "Compose Multiplatform", "Kotlin Standard Library", "Guava ListenableFuture")) {
            awaitText(component)
        }
        awaitText("Apache License 2.0 · 1 module")
        awaitText("Apache License 2.0 · 88 modules")
        // no approved policy URL (LR-5): no link and no placeholder
        assertAbsent("Privacy policy")
    }

    @Test
    fun modulesAndTheLicenceTextOpenOnlyThroughTheirToggles() = onScreen(content = about()) {
        awaitText("AndroidX (Android Jetpack)")
        assertAbsent("androidx.activity:activity-compose:1.13.0", substring = true)
        assertAbsent("TERMS AND CONDITIONS", substring = true)

        clickText("Show modules") // the first card: AndroidX
        awaitText("androidx.activity:activity-compose:1.13.0", substring = true)
        awaitText("Hide modules")

        clickText("Show license text")
        awaitText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
        clickText("Hide license text")
        assertAbsent("TERMS AND CONDITIONS", substring = true)
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
        awaitText("НЕЗАВИСИМЫЙ ПРОЕКТ")
        awaitText(
            "TalentForge — независимый неофициальный проект. Он не связан с разработчиком и издателем игры, " +
                "не одобрен и не поддерживается ими.",
        )
        awaitText("КОНФИДЕНЦИАЛЬНОСТЬ")
        awaitText("Приложение отключает резервное копирование Android", substring = true)
        awaitText("ЛИЦЕНЗИИ ОТКРЫТОГО ПО")
        awaitText("Apache License 2.0 · 1 модуль")
        awaitText("Apache License 2.0 · 3 модуля")
        awaitText("Apache License 2.0 · 88 модулей")
        clickText("Показать модули")
        awaitText("Скрыть модули")
        assertAbsent("Show modules")
        assertAbsent("About")
    }

    // --------------------------------------------------------- platform variants

    @Test
    fun withoutCompiledNoticesOrAVersionNothingIsInvented() = onScreen(
        content = about(appVersion = null, noticesPath = null, backup = BackupStatement.IosDeviceBackup),
    ) {
        awaitText("Open-source notices are not included in this build.")
        awaitText("If your device's backup (iCloud or a computer) is on", substring = true)
        assertAbsent("Version ", substring = true)
        assertAbsent("The app turns off Android backup", substring = true)
        assertAbsent("AndroidX (Android Jetpack)")
    }

    @Test
    fun unreadableNoticesAreAnErrorWithRetryNotAnEmptyList() = onScreen(
        content = about(noticesPath = "files/legal/absent.json"),
    ) {
        awaitText("Couldn't load the open-source notices")
        awaitText("Retry")
        assertAbsent("AndroidX (Android Jetpack)")
    }

    @Test
    fun aConfiguredPolicyUrlBecomesAnExplicitLink() {
        val handler = RecordingUriHandler()
        val url = "https://policy.example.org/talentforge"
        onScreen(uriHandler = handler, content = about(privacyPolicyUrl = url)) {
            clickText("Privacy policy")
            assertEquals(listOf(url), handler.opened)
        }
    }

    @Test
    fun largeTextOnANarrowPhoneKeepsEveryToggleReachable() = onScreen(
        widthDp = 320,
        heightDp = 640,
        fontScale = 2f,
        content = about(),
    ) {
        awaitText("Guava ListenableFuture")
        val toggles = onAllNodes(clickLabelled("Show modules")).fetchSemanticsNodes() +
            onAllNodes(clickLabelled("Show license text")).fetchSemanticsNodes()
        assertEquals(9, toggles.size, "8 component toggles and 1 licence toggle")
        // layout size, not the scroll-clipped bounds: most toggles are below the fold (density 1 → px = dp)
        for (toggle in toggles) {
            assertTrue(toggle.size.height >= 48, "toggle target ${toggle.size.height} px < 48 dp")
            assertTrue(toggle.positionInRoot.x + toggle.size.width <= 320f, "toggle overflows the 320 dp width")
        }
    }

    // ------------------------------------------------------------- through App

    @Test
    fun theRussianHomeEntryIsNamedAndOpensAbout() = onScreen(
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
    }

    @Test
    fun homeOpensAboutAndBackReturnsHome() = onScreen(
        content = { App(storage = FakeBuildLibraryStorage(), clock = FixedClock(), ids = SequentialIds(), appVersion = version) },
    ) {
        awaitText("New build")
        // the Home entry: a 48 dp glyph named "About", one line with the wordmark
        val entry = onAllNodesWithContentDescription("About").fetchSemanticsNodes().single()
        assertTrue(entry.size.width >= 48 && entry.size.height >= 48, "About entry ${entry.size} < 48 dp")
        onAllNodes(clickLabelled("open About TalentForge")).onFirst().performClick()
        awaitText("Version 0.1.0 (1)")
        onAllNodesWithContentDescription("go back").onFirst().performClick()
        awaitText("New build")
        assertAbsent("Version 0.1.0 (1)")
    }

    private class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()

        override fun openUri(uri: String) {
            opened += uri
        }
    }
}
