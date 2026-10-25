package io.github.alexeykravtsov95.talentforge.ui.about

import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.navigation.BackSwipeStack
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.EdgeSwipeBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.LocalEdgeSwipeBackDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * M5.UI.ABOUT.2: the grouped open-source licenses, driven by the REAL bundled
 * Android and iOS notices and licence texts — the list of groups naming every
 * component, the required statements up front, a group's components and
 * licence rows, a licence's full original text with every component it applies
 * to, no module coordinates anywhere, Back (chevron and system) one level at a
 * time, the not-included and failure states, EN/RU, and every target reachable
 * at 320 dp / 200 %.
 */
@OptIn(ExperimentalTestApi::class)
class LicensesScreenUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val android = runBlocking { loadOpenSourceNotices(ANDROID_OSS_NOTICES_PATH) }
    private val ios = runBlocking { loadOpenSourceNotices(IOS_OSS_NOTICES_PATH) }

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

    private fun ComposeUiTest.open(clickLabel: String) {
        waitUntil(timeoutMillis = 20_000) { onAllNodes(clickLabelled(clickLabel)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(clickLabelled(clickLabel)).onFirst().performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.assertNoModuleCoordinates(notices: OpenSourceNotices) {
        val shown = texts().joinToString("\n")
        for (module in notices.components.filterIsInstance<NoticeComponent.Packaged>().flatMap { it.modules }) {
            assertTrue(module !in shown, "module coordinate '$module' is on screen: $shown")
        }
    }

    // --------------------------------------------------------------- the list

    @Test
    fun englishListShowsOneRowPerGroupWithoutModuleCoordinates() = onScreen(content = licenses()) {
        awaitText("Open-source licenses")
        awaitText(
            "TalentForge uses these open-source components under their licenses. " +
                "Open a group to see what it includes and the full license texts.",
        )
        awaitText("COMPONENTS")
        for (group in android.groups) {
            awaitText(group.head.name)
            assertEquals(1, onAllNodes(clickLabelled("open ${group.head.name}")).fetchSemanticsNodes().size, group.head.name)
        }
        // the stdlib carries the ThreeTen licence next to the POM's Apache-2.0 (M5.LEGAL.OSS.1)
        awaitText("Apache License 2.0, BSD 3-Clause License (ThreeTen)")
        awaitText("Apache License 2.0", substring = true)
        assertAbsent("REQUIRED NOTICES") // no Android licence requires a documentation statement
        assertAbsent("module", substring = true)
        assertNoModuleCoordinates(android)
    }

    @Test
    fun theIosListPutsTheRequiredStatementsFirstAndNamesEveryLinkedComponent() = onScreen(
        content = licenses(noticesPath = IOS_OSS_NOTICES_PATH),
    ) {
        awaitText("REQUIRED NOTICES")
        awaitText("This software is based in part on the work of the Independent JPEG Group.")
        awaitText("This product includes DNG technology under license by Adobe Systems Incorporated.")
        // above the groups: visible without opening anything
        val notice = onAllNodes(hasText("This product includes DNG technology", substring = true)).fetchSemanticsNodes().single()
        val groups = onAllNodes(hasText("COMPONENTS")).fetchSemanticsNodes().single()
        assertTrue(notice.positionInRoot.y < groups.positionInRoot.y, "the required statements come before the groups")

        val skiko = ios.groups.single { it.head.name == "Skiko" }
        awaitText("Includes: ${skiko.linked.joinToString(", ") { it.name }}", substring = true)
        awaitText("15 licenses", substring = true)
        for (group in ios.groups) {
            assertEquals(1, onAllNodes(clickLabelled("open ${group.head.name}")).fetchSemanticsNodes().size, group.head.name)
        }
        assertAbsent("JSpecify") // Android-only components never appear in the iOS list
        assertNoModuleCoordinates(ios)
    }

    // ------------------------------------------------------- group and licence

    @Test
    fun aGroupListsItsLicencesAndOpensEachLicenceText() = onScreen(content = licenses()) {
        open("open Kotlin Standard Library")
        awaitText("https://kotlinlang.org/")
        awaitText("Apache License 2.0, BSD 3-Clause License (ThreeTen)")
        awaitText("LICENSE TEXTS")
        assertEquals(1, onAllNodes(clickLabelled("open the text of Apache License 2.0")).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(clickLabelled("open the text of BSD 3-Clause License (ThreeTen)")).fetchSemanticsNodes().size)
        assertAbsent("Guava ListenableFuture")
        assertNoModuleCoordinates(android)

        open("open the text of BSD 3-Clause License (ThreeTen)")
        awaitText("Applies to: Kotlin Standard Library")
        // the copyright holders come with the licence text
        awaitText("Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos", substring = true)
    }

    @Test
    fun aSharedLicenceTextIsShownOnceAndNamesEveryComponentItAppliesTo() = onScreen(content = licenses()) {
        open("open AndroidX (Android Jetpack)")
        open("open the text of Apache License 2.0")
        awaitText("Applies to: ${android.componentsUnder("Apache-2.0").joinToString(", ") { it.name }}")
        awaitText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
        // the whole text, to its last line — not a summary
        awaitText("limitations under the License.", substring = true)
    }

    @Test
    fun theIosSkikoGroupKeepsEveryNativeComponentWithItsVersionStatementAndLicences() = onScreen(
        content = licenses(noticesPath = IOS_OSS_NOTICES_PATH),
    ) {
        open("open Skiko")
        awaitText("Apache License 2.0, Skiko NOTICE")
        awaitText("INCLUDED THIRD-PARTY CODE")
        val skiko = ios.groups.single { it.head.name == "Skiko" }
        for (component in skiko.linked) {
            awaitText(component.name)
            val licences = component.licenseIds.joinToString(", ") { ios.licenses.getValue(it).name }
            awaitText("$licences · Version ${component.version}")
            awaitText(component.url)
        }
        awaitText("This software is based in part on the work of the Independent JPEG Group.")
        awaitText("This product includes DNG technology under license by Adobe Systems Incorporated.")
        for (id in skiko.licenseIds) {
            val name = ios.licenses.getValue(id).name
            assertEquals(1, onAllNodes(clickLabelled("open the text of $name")).fetchSemanticsNodes().size, name)
        }
        assertAbsent("Statically linked into", substring = true)
        assertNoModuleCoordinates(ios)

        // the patent licences are full texts too, never dropped
        open("open the text of Adobe DNG Specification Patent License")
        awaitText("Applies to: Adobe DNG SDK")
        awaitText("Digital Negative (DNG) Specification patent license", substring = true)
        awaitText("This product includes DNG", substring = true)
    }

    @Test
    fun theSkikoNoticeIsReachableAsItsOwnText() = onScreen(content = licenses(noticesPath = IOS_OSS_NOTICES_PATH)) {
        open("open Skiko")
        open("open the text of Skiko NOTICE")
        awaitText("Applies to: Skiko")
        awaitText("This project contains code adapted from \"The Android Open Source Project\"", substring = true)
    }

    // ------------------------------------------------------------------- Back

    @Test
    fun theChevronGoesBackOneLevelAtATime() {
        var backs = 0
        onScreen(content = licenses(onBack = { backs += 1 })) {
            open("open Kotlin Standard Library")
            open("open the text of Apache License 2.0")
            awaitText("TERMS AND CONDITIONS", substring = true)

            onAllNodesWithContentDescription("go back").onFirst().performClick()
            awaitText("LICENSE TEXTS")
            assertAbsent("TERMS AND CONDITIONS", substring = true)

            onAllNodesWithContentDescription("go back").onFirst().performClick()
            awaitText("COMPONENTS")
            assertEquals(0, backs)

            onAllNodesWithContentDescription("go back").onFirst().performClick()
            waitForIdle()
            assertEquals(1, backs)
        }
    }

    @Test
    fun theSystemBackClosesTheLicenceThenTheGroupThenLeavesTheScreenToTheApp() {
        var backs = 0
        onScreen(content = licenses(onBack = { backs += 1 })) {
            open("open Compose Multiplatform")
            open("open the text of Apache License 2.0")
            awaitText("TERMS AND CONDITIONS", substring = true)

            assertEquals(true, runOnIdle { DesktopBackDispatcher.dispatch() }, "the licence page takes the Back")
            awaitText("LICENSE TEXTS")
            runOnIdle { DesktopBackDispatcher.dispatch() }
            awaitText("COMPONENTS")
            waitForIdle()
            // on the list the screen holds no Back of its own: the app's navigation receives it
            assertEquals(false, runOnIdle { DesktopBackDispatcher.dispatch() })
            assertEquals(0, backs)
        }
    }

    @Test
    fun theIosEdgeSwipeClosesTheInnerPagesLikeTheirChevronAndCanBeCancelled() {
        val swipe = EdgeSwipeBackDispatcher()
        var backs = 0
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalEdgeSwipeBackDispatcher provides swipe) {
                LicensesScreen(noticesPath = ANDROID_OSS_NOTICES_PATH, onBack = { backs += 1 })
            }
        }
        fun ComposeUiTest.swipe(complete: Boolean): Boolean {
            val taken = runOnIdle { swipe.start().also { swipe.progress(0.6f) } }
            waitForIdle()
            runOnIdle { if (complete) swipe.complete() else swipe.cancel() }
            waitForIdle()
            return taken
        }
        onScreen(content = content) {
            awaitText("COMPONENTS")
            assertFalse(swipe(complete = true), "on the list the screen holds no swipe: the app's page stack takes it")

            open("open Compose Multiplatform")
            open("open the text of Apache License 2.0")
            awaitText("TERMS AND CONDITIONS", substring = true)

            assertTrue(swipe(complete = false))
            awaitText("TERMS AND CONDITIONS", substring = true)
            assertTrue(swipe(complete = true), "the licence page takes the swipe")
            awaitText("LICENSE TEXTS")
            assertTrue(swipe(complete = true))
            awaitText("COMPONENTS")
            assertEquals(0, backs, "the screen itself is left by the app, never by its inner swipe")
        }
    }

    @Test
    fun aSecondQuickSwipeWhileTheFirstSettlesNeverSkipsALevel() {
        val swipe = EdgeSwipeBackDispatcher()
        var appBacks = 0
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalEdgeSwipeBackDispatcher provides swipe, LocalReducedMotion provides false) {
                // the app's own page stack around the screen, as App composes it (About beneath)
                BackSwipeStack(
                    depth = 1,
                    enabled = true,
                    onPrepareBack = { true },
                    onBack = {
                        appBacks += 1
                        false
                    },
                ) { level ->
                    if (level == 1) LicensesScreen(noticesPath = ANDROID_OSS_NOTICES_PATH, onBack = {}) else BasicText("About")
                }
            }
        }
        fun ComposeUiTest.quickSwipe() = runOnUiThread {
            swipe.start()
            swipe.progress(0.7f)
            swipe.complete()
        }
        onScreen(content = content) {
            open("open Compose Multiplatform")
            open("open the text of Apache License 2.0")
            awaitText("TERMS AND CONDITIONS", substring = true)

            mainClock.autoAdvance = false
            quickSwipe()
            // the licence page is sliding out, its handler recomposed — the second finger arrives now
            repeat(3) { mainClock.advanceTimeByFrame() }
            quickSwipe()
            mainClock.autoAdvance = true
            waitForIdle()

            awaitText("LICENSE TEXTS")
            assertEquals(0, appBacks, "the held second swipe never reached the app's stack")
            // once settled, the next swipe closes exactly the next level
            quickSwipe()
            waitForIdle()
            awaitText("COMPONENTS")
            assertEquals(0, appBacks)
        }
    }

    // --------------------------------------------------------------- Russian

    @Test
    fun russianLicensesAreLocalizedAndTheTextsStayOriginal() = onScreen(language = AppLanguage.RUSSIAN, content = licenses()) {
        awaitText("Лицензии открытого ПО")
        awaitText(
            "TalentForge использует эти компоненты с открытым исходным кодом на условиях их лицензий. " +
                "Откройте группу, чтобы увидеть её состав и полные тексты лицензий.",
        )
        awaitText("КОМПОНЕНТЫ")
        awaitText("Apache License 2.0, BSD 3-Clause License (ThreeTen)")
        open("открыть Kotlin Standard Library")
        awaitText("ТЕКСТЫ ЛИЦЕНЗИЙ")
        open("открыть текст: Apache License 2.0")
        awaitText("Применяется к: ${android.componentsUnder("Apache-2.0").joinToString(", ") { it.name }}")
        // the licence itself is never translated
        awaitText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
        for (english in listOf("Open-source licenses", "COMPONENTS", "LICENSE TEXTS")) assertAbsent(english)
    }

    @Test
    fun russianIosRequiredStatementsKeepTheirOriginalWording() = onScreen(
        language = AppLanguage.RUSSIAN,
        content = licenses(noticesPath = IOS_OSS_NOTICES_PATH),
    ) {
        awaitText("ОБЯЗАТЕЛЬНЫЕ УВЕДОМЛЕНИЯ")
        awaitText("This product includes DNG technology under license by Adobe Systems Incorporated.")
        awaitText("Включает: Skia", substring = true)
        awaitText("15 лицензий", substring = true)
    }

    // ----------------------------------------------------------------- states

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

    // ------------------------------------------------- large text, small phone

    @Test
    fun largeTextOnASmallPhoneKeepsEveryRowReachable() {
        for (language in AppLanguage.entries) {
            val ru = language == AppLanguage.RUSSIAN
            val open = if (ru) "открыть" else "open"
            for ((notices, path) in listOf(android to ANDROID_OSS_NOTICES_PATH, ios to IOS_OSS_NOTICES_PATH)) {
                onScreen(language = language, widthDp = 320, heightDp = 568, fontScale = 2f, content = licenses(path)) {
                    awaitText(notices.groups.last().head.name)
                    val rows = notices.groups.flatMap { onAllNodes(clickLabelled("$open ${it.head.name}")).fetchSemanticsNodes() }
                    assertEquals(notices.groups.size, rows.size)
                    rows.forEach { assertUsableTarget(it, widthDp = 320) }
                    val labels = onAllNodes(anyClickable).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsActions.OnClick)?.label }
                    assertEquals(notices.groups.size + 1, labels.size, "the group rows and Back: $labels")

                    // the biggest group: every licence row usable
                    val biggest = notices.groups.maxBy { it.licenseIds.size }
                    open("$open ${biggest.head.name}")
                    for (id in biggest.licenseIds) {
                        val name = notices.licenses.getValue(id).name
                        val label = if (ru) "открыть текст: $name" else "open the text of $name"
                        assertUsableTarget(onAllNodes(clickLabelled(label)).fetchSemanticsNodes().single(), widthDp = 320)
                    }
                }
            }
        }
    }

    @Test
    fun theLongestLicenceTextScrollsToItsLastLineAtLargeText() = onScreen(
        widthDp = 320,
        heightDp = 568,
        fontScale = 2f,
        content = licenses(noticesPath = IOS_OSS_NOTICES_PATH),
    ) {
        open("open Skiko")
        open("open the text of ICU License (Unicode License V3 and third-party notices)")
        val lastLine = runBlocking { readLegalResource(ios.licenses.getValue("ICU").textPath) }.trimEnd().lines().last().trim()
        awaitText(lastLine, substring = true)
        onAllNodes(hasText(lastLine, substring = true)).onFirst().performScrollTo()
        waitForIdle()
        assertEquals(1, onAllNodesWithContentDescription("go back").fetchSemanticsNodes().size, "Back stays on screen after scrolling")
    }
}
