package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/**
 * M5.UI.ABOUT.1: the third-party licenses, moved off the About screen with
 * nothing dropped — driven by the REAL bundled Android and iOS notices and
 * licence texts: every component with its licences, module list or version,
 * linked-into module and required attribution, every full licence text behind
 * an explicit 48 dp toggle, the not-included and failure states, Back, EN/RU,
 * and every toggle reachable at 320 dp / 200 %.
 */
@OptIn(ExperimentalTestApi::class)
class LicensesScreenUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private fun onScreen(
        language: AppLanguage = AppLanguage.ENGLISH,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) = uiLanguage.runAboutUiTest(language, widthDp, heightDp, fontScale, content = content, block = block)

    private fun licenses(
        noticesPath: String? = ANDROID_OSS_NOTICES_PATH,
        onBack: () -> Unit = {},
    ): @Composable () -> Unit = { LicensesScreen(noticesPath = noticesPath, onBack = onBack) }

    @Test
    fun englishLicensesListTheRealAndroidNotices() = onScreen(content = licenses()) {
        awaitText("Third-party licenses")
        awaitText("TalentForge is built with these open-source components, used under their licenses.")
        for (component in listOf("AndroidX (Android Jetpack)", "Compose Multiplatform", "Kotlin Standard Library", "Guava ListenableFuture")) {
            awaitText(component)
        }
        awaitText("Apache License 2.0 · 1 module")
        awaitText("Apache License 2.0 · 88 modules")
        // M5.LEGAL.OSS.1: the stdlib card carries the ThreeTen licence next to the POM's Apache-2.0
        awaitText("Apache License 2.0, BSD 3-Clause License (ThreeTen) · 1 module")
    }

    @Test
    fun modulesAndTheLicenceTextOpenOnlyThroughTheirToggles() = onScreen(content = licenses()) {
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
        onScreen(content = licenses(onBack = { backs += 1 })) {
            awaitText("Third-party licenses")
            onAllNodesWithContentDescription("go back").onFirst().performClick()
            waitForIdle()
            assertEquals(1, backs)
        }
    }

    @Test
    fun russianLicensesAreLocalized() = onScreen(language = AppLanguage.RUSSIAN, content = licenses()) {
        awaitText("Лицензии сторонних компонентов")
        awaitText("TalentForge создан с использованием этих компонентов с открытым исходным кодом на условиях их лицензий.")
        awaitText("Apache License 2.0 · 1 модуль")
        awaitText("Apache License 2.0 · 3 модуля")
        awaitText("Apache License 2.0 · 88 модулей")
        awaitText("Apache License 2.0, BSD 3-Clause License (ThreeTen) · 1 модуль")
        clickText("Показать модули")
        awaitText("Скрыть модули")
        assertAbsent("Show modules")
        assertAbsent("Third-party licenses")
    }

    @Test
    fun withoutCompiledNoticesNothingIsInvented() = onScreen(content = licenses(noticesPath = null)) {
        awaitText("Open-source notices are not included in this build.")
        assertAbsent("AndroidX (Android Jetpack)")
    }

    @Test
    fun unreadableNoticesAreAnErrorWithRetryNotAnEmptyList() = onScreen(
        content = licenses(noticesPath = "files/legal/absent.json"),
    ) {
        awaitText("Couldn't load the open-source notices")
        awaitText("Retry")
        assertAbsent("AndroidX (Android Jetpack)")
    }

    @Test
    fun theIosNoticesListTheLinkedNativeCodeWithTheRequiredStatements() = onScreen(
        content = licenses(noticesPath = IOS_OSS_NOTICES_PATH),
    ) {
        awaitText("Skiko")
        awaitText("Apache License 2.0, Skiko NOTICE · 2 modules")
        awaitText("Skia")
        awaitText("BSD 3-Clause License (Skia) · Version m138 (JetBrains/skia 80d088a)")
        awaitText("Statically linked into org.jetbrains.skiko:skiko-iosarm64:0.9.37.4", substring = true)
        awaitText("This software is based in part on the work of the Independent JPEG Group.")
        awaitText("This product includes DNG technology under license by Adobe Systems Incorporated.")
        awaitText("Kotlin Standard Library: third-party code (ThreeTen, GWT, Guava)")
        assertAbsent("Open-source notices are not included in this build.")
        assertAbsent("JSpecify") // Android-only modules never appear in the iOS list
        // linked components have no module list; every licence and notice text has its own toggle
        assertEquals(7, onAllNodes(clickLabelled("Show modules")).fetchSemanticsNodes().size)
        assertEquals(16, onAllNodes(clickLabelled("Show license text")).fetchSemanticsNodes().size)
    }

    @Test
    fun largeTextOnANarrowPhoneKeepsEveryToggleReachable() {
        for (language in AppLanguage.entries) {
            val ru = language == AppLanguage.RUSSIAN
            onScreen(language = language, widthDp = 320, heightDp = 640, fontScale = 2f, content = licenses()) {
                awaitText("Guava ListenableFuture")
                val toggles = onAllNodes(clickLabelled(if (ru) "Показать модули" else "Show modules")).fetchSemanticsNodes() +
                    onAllNodes(clickLabelled(if (ru) "Показать текст лицензии" else "Show license text")).fetchSemanticsNodes()
                assertEquals(10, toggles.size, "8 component toggles and 2 licence toggles")
                toggles.forEach { assertUsableTarget(it, widthDp = 320) }
                val labels = onAllNodes(anyClickable).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsActions.OnClick)?.label }
                assertEquals(11, labels.size, "the toggles and Back: $labels")
            }
        }
    }
}
