package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.runtime.Composable
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

/** Desktop (development host): the AWT system clipboard; Share copies, and the sheet says so. */
@Composable
actual fun rememberPlatformBuildCodeSharing(): BuildCodeSharing = DesktopBuildCodeSharing

internal object DesktopBuildCodeSharing : BuildCodeSharing {

    override suspend fun copyToClipboard(text: String): ClipboardWrite {
        val clipboard = systemClipboard() ?: return ClipboardWrite.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        return try {
            clipboard.setContents(StringSelection(text), null)
            ClipboardWrite.Copied
        } catch (e: IllegalStateException) {
            // the clipboard is momentarily owned by another process
            ClipboardWrite.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        }
    }

    /** No share sheet exists on this host: a functional fallback, never an inert button. */
    override suspend fun shareText(text: String): TextShareOutcome = when (val written = copyToClipboard(text)) {
        ClipboardWrite.Copied -> TextShareOutcome.CopiedToClipboard
        is ClipboardWrite.Failed -> TextShareOutcome.Failed(written.reason)
    }

    override suspend fun readClipboardText(): ClipboardRead {
        val clipboard = systemClipboard() ?: return ClipboardRead.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        return try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                ClipboardRead.Empty
            } else {
                val text = clipboard.getData(DataFlavor.stringFlavor) as? String
                if (text.isNullOrEmpty()) ClipboardRead.Empty else ClipboardRead.Text(text)
            }
        } catch (e: IllegalStateException) {
            ClipboardRead.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        } catch (e: UnsupportedFlavorException) {
            ClipboardRead.Empty
        } catch (e: IOException) {
            ClipboardRead.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        }
    }

    /** `null` in a headless JVM (CI), where no system clipboard exists. */
    private fun systemClipboard(): Clipboard? = try {
        Toolkit.getDefaultToolkit().systemClipboard
    } catch (e: HeadlessException) {
        null
    }
}
