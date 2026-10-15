package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * iOS: `UIActivityViewController` with the exact code as its single plain
 * text item, presented from the top-most controller above the Compose host
 * (iPad: anchored as a popover at the centre of that controller's view, no
 * arrow — a popover without a source would crash), and `UIPasteboard`.
 * The sheet's completion handler distinguishes a finished activity, a
 * cancellation and an error; none of them is reported as a delivery the
 * system did not confirm. No permission, no network.
 */
@Composable
actual fun rememberPlatformBuildCodeSharing(): BuildCodeSharing {
    val host = LocalUIViewController.current
    return remember(host) { IosBuildCodeSharing(host) }
}

internal class IosBuildCodeSharing(private val host: UIViewController) : BuildCodeSharing {

    override suspend fun copyToClipboard(text: String): ClipboardWrite {
        UIPasteboard.generalPasteboard.string = text
        return ClipboardWrite.Copied
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun shareText(text: String): TextShareOutcome {
        val presenter = topPresenter() ?: return TextShareOutcome.Failed(PlatformShareFailure.PRESENTATION_UNAVAILABLE)
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        sheet.popoverPresentationController?.let { popover ->
            val anchor = presenter.view
            popover.sourceView = anchor
            popover.sourceRect = anchor.bounds.useContents {
                CGRectMake(origin.x + size.width / 2.0, origin.y + size.height / 2.0, 0.0, 0.0)
            }
            popover.permittedArrowDirections = 0uL
        }
        return suspendCancellableCoroutine { continuation ->
            sheet.completionWithItemsHandler = { _, completed, _, error ->
                if (continuation.isActive) {
                    continuation.resume(
                        when {
                            error != null -> TextShareOutcome.Failed(PlatformShareFailure.PRESENTATION_UNAVAILABLE)
                            completed -> TextShareOutcome.Completed
                            else -> TextShareOutcome.Cancelled
                        },
                    )
                }
            }
            presenter.presentViewController(sheet, animated = true, completion = null)
        }
    }

    /**
     * `hasStrings` is checked first (it never triggers the system paste
     * prompt); a `null` string after that means the player declined the
     * prompt — a typed failure, not an empty clipboard.
     */
    override suspend fun readClipboardText(): ClipboardRead {
        val board = UIPasteboard.generalPasteboard
        if (!board.hasStrings) return ClipboardRead.Empty
        val text = board.string ?: return ClipboardRead.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        return if (text.isEmpty()) ClipboardRead.Empty else ClipboardRead.Text(text)
    }

    /**
     * The controller to present from: the Compose host, or whatever it is
     * already presenting on top. `null` while the host is not in a window
     * (backgrounded scene, mid-transition): presenting would silently fail.
     */
    private fun topPresenter(): UIViewController? {
        if (host.view.window == null) return null
        var top = host
        while (true) {
            top = top.presentedViewController ?: break
        }
        return top
    }
}
