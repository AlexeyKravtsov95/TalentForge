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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import kotlin.test.assertTrue

/** The harness of the About and Licenses UI tests: a phone-sized window at density 1 (px = dp). */
@OptIn(ExperimentalTestApi::class)
internal fun AppLanguageRule.runAboutUiTest(
    language: AppLanguage = AppLanguage.ENGLISH,
    widthDp: Int = 393,
    heightDp: Int = 852,
    fontScale: Float = 1f,
    uriHandler: UriHandler = RecordingUriHandler(),
    content: @Composable () -> Unit,
    block: ComposeUiTest.() -> Unit,
) {
    this.language = language
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

/** Every text in the (unmerged) semantics tree, including text scrolled below the fold. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.texts(): List<String> =
    onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitText(text: String, substring: Boolean = false) {
    try {
        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("'$text' never appeared; on screen: ${texts()}", timeout)
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickText(text: String) {
    awaitText(text)
    onAllNodesWithText(text).onFirst().performScrollTo().performClick()
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertAbsent(text: String, substring: Boolean = false) {
    assertTrue(
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty(),
        "'$text' must not be shown; on screen: ${texts()}",
    )
}

/** The clickable nodes whose click action is labelled [label]. */
internal fun clickLabelled(label: String) = SemanticsMatcher("click label '$label'") { node ->
    node.config.getOrNull(SemanticsActions.OnClick)?.label == label
}

/** Every clickable node on screen. */
internal val anyClickable = SemanticsMatcher("clickable") { node -> node.config.getOrNull(SemanticsActions.OnClick) != null }

/**
 * A touch target keeps 48 dp and fits [widthDp]: layout size, not the
 * scroll-clipped bounds — most targets are below the fold (density 1 → px = dp).
 */
internal fun assertUsableTarget(node: SemanticsNode, widthDp: Int) {
    val what = node.config.getOrNull(SemanticsActions.OnClick)?.label ?: "target"
    assertTrue(node.size.height >= 48, "'$what' height ${node.size.height} px < 48 dp")
    assertTrue(node.positionInRoot.x + node.size.width <= widthDp.toFloat(), "'$what' overflows the $widthDp dp width")
}

internal class RecordingUriHandler : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
        opened += uri
    }
}
