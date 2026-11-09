package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.DraftCreation
import io.github.alexeykravtsov95.talentforge.ui.library.createClassDraft
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.EdgeSwipeBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.navigation.LocalEdgeSwipeBackDispatcher
import io.github.alexeykravtsov95.talentforge.ui.ota.A_BUILD
import io.github.alexeykravtsov95.talentforge.ui.ota.B_BUILD
import io.github.alexeykravtsov95.talentforge.ui.ota.OtaDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

@OptIn(ExperimentalTestApi::class)
class FirstUseHelpUiTest {
    @get:Rule val language = AppLanguageRule(AppLanguage.ENGLISH)

    @Test
    fun helpIsReadableAndReopenableAcrossThePhoneAndTextMatrixWithoutWritingBuilds() {
        for (locale in AppLanguage.entries) {
            language.language = locale
            val ru = locale == AppLanguage.RUSSIAN
            val title = if (ru) "Как пользоваться" else "How to use"
            val steps = if (ru) listOf(
                "1. Выберите один из 9 классов WoW Forever.",
                "2. Распределяйте очки в деревьях талантов и изучайте их описания.",
                "3. Сохраняйте билды, редактируйте их и делитесь кодами сборок.",
                "4. Планируйте билды офлайн на встроенных игровых данных. В этой версии приложения обновления данных через интернет отключены.",
            ) else listOf(
                "1. Choose one of the 9 WoW Forever classes.",
                "2. Spend points in talent trees and read talent descriptions.",
                "3. Save builds, edit them and share build codes.",
                "4. Plan builds offline using the included game data. Online data updates are disabled in this app version.",
            )
            for (otaEnabled in listOf(false, true)) {
                val device = if (otaEnabled) OtaDevice(bundledBuilds = setOf(A_BUILD, B_BUILD)) else null
                try {
                    val actualSteps = if (!otaEnabled) steps else steps.dropLast(1) + if (ru) {
                        "4. Планируйте билды офлайн. Игровые данные могут обновляться через интернет при наличии подключения."
                    } else "4. Plan builds offline. Game data can update over the internet when connected."
                    for (width in listOf(320, 360, 393, 412)) for (scale in listOf(1f, 1.3f, 1.75f, 2f)) {
                        for (populated in listOf(false, true)) {
                            val storage = FakeBuildLibraryStorage()
                            val clock = FixedClock()
                            val ids = SequentialIds()
                            if (populated) runBlocking {
                                assertIs<DraftCreation.Created>(createClassDraft(
                                    LocalBuildRepository(storage, clock, ids),
                                    BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource()), ClassToken("WARRIOR"),
                                ))
                            }
                            val before = storage.payload
                            val writes = storage.writeCount
                            runDesktopComposeUiTest(width = width, height = 760) {
                                val swipe = EdgeSwipeBackDispatcher()
                                setContent {
                                    CompositionLocalProvider(
                                        LocalDensity provides Density(1f, scale),
                                        LocalReducedMotion provides true,
                                        LocalEdgeSwipeBackDispatcher provides swipe,
                                    ) {
                                        Box(Modifier.size(width.dp, 760.dp)) { App(storage, clock, ids, ota = device?.runtime) }
                                    }
                                }
                                val home = if (populated) {
                                    if (ru) "ПРОДОЛЖИТЬ" else "CONTINUE"
                                } else if (ru) "Пока нет билдов" else "No builds yet"
                                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(home).fetchSemanticsNodes().isNotEmpty() }
                                val entry = onNodeWithText(title).performScrollTo()
                                val target = entry.fetchSemanticsNode().boundsInRoot
                                assertTrue(target.height >= 48f && target.width >= 44f)
                                entry.performClick()
                                for (step in actualSteps) {
                                    val node = onNodeWithText(step).performScrollTo().fetchSemanticsNode()
                                    val layouts = mutableListOf<TextLayoutResult>()
                                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                                    val layout = layouts.single()
                                    val where = "$locale $width dp @$scale OTA=$otaEnabled: $step"
                                    // Skia can round an 18.000002 px line into an 18 px layout;
                                    // measure the actual lines with a subpixel tolerance.
                                    for (line in 0 until layout.lineCount) {
                                        assertFalse(layout.isLineEllipsized(line), where)
                                        assertTrue(layout.getLineRight(line) <= layout.size.width + 1.5f, where)
                                    }
                                    assertEquals(step.length, layout.getLineEnd(layout.lineCount - 1), where)
                                    assertTrue(layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1.5f, where)
                                    assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width + 1f)
                                }
                                onNodeWithContentDescription(if (ru) "назад" else "go back").performClick()
                                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(home).fetchSemanticsNodes().isNotEmpty() }
                                onNodeWithText(title).performScrollTo().performClick()
                                runOnIdle { assertTrue(DesktopBackDispatcher.dispatch()) }
                                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(home).fetchSemanticsNodes().isNotEmpty() }
                                onNodeWithText(title).performScrollTo().performClick()
                                runOnIdle { assertTrue(swipe.start()); swipe.progress(0.4f); swipe.cancel() }
                                waitForIdle()
                                onNodeWithText(actualSteps.first()).assertExists()
                                runOnIdle { assertTrue(swipe.start()); swipe.progress(0.7f); swipe.complete() }
                                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(home).fetchSemanticsNodes().isNotEmpty() }
                                assertEquals(before, storage.payload)
                                assertEquals(writes, storage.writeCount)
                            }
                        }
                    }
                } finally {
                    device?.close()
                }
            }
        }
    }

    @Test
    fun helpDestinationSurvivesNavigationRestoration() {
        val stack = listOf(AppDestination.Home, AppDestination.Help)
        assertEquals(stack, decodeDestinations(stack.map(::encodeDestination)))
        assertEquals(null, decodeDestinations(listOf("help")))
    }
}
