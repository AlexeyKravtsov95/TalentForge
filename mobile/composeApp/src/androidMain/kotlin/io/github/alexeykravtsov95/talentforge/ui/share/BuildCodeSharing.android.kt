package io.github.alexeykravtsov95.talentforge.ui.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android: the system share chooser (`ACTION_SEND`, `text/plain`,
 * `EXTRA_TEXT` = the exact code, wrapped in `Intent.createChooser`) started
 * from the hosting Activity, and the system [ClipboardManager]. Bound to the
 * composition's context, so an Activity recreation (rotation) builds a fresh
 * adapter for the new Activity and never leaks the old one. No permission,
 * no network.
 */
@Composable
actual fun rememberPlatformBuildCodeSharing(): BuildCodeSharing {
    val context = LocalContext.current
    return remember(context) { AndroidBuildCodeSharing(context) }
}

internal class AndroidBuildCodeSharing(private val context: Context) : BuildCodeSharing {

    override suspend fun copyToClipboard(text: String): ClipboardWrite {
        val clipboard = clipboardManager() ?: return ClipboardWrite.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            ClipboardWrite.Copied
        } catch (e: SecurityException) {
            ClipboardWrite.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        }
    }

    /**
     * Starts the chooser and reports [TextShareOutcome.SheetOpened]: Android
     * tells the app nothing about what the player then picks or sends, so the
     * outcome deliberately claims no delivery.
     */
    override suspend fun shareText(text: String): TextShareOutcome {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, null)
        val activity = context.findActivity()
        if (activity == null) {
            // started from a non-Activity context the chooser needs its own task
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            (activity ?: context).startActivity(chooser)
            TextShareOutcome.SheetOpened
        } catch (e: ActivityNotFoundException) {
            TextShareOutcome.Failed(PlatformShareFailure.NO_SHARE_TARGET)
        } catch (e: SecurityException) {
            TextShareOutcome.Failed(PlatformShareFailure.PRESENTATION_UNAVAILABLE)
        }
    }

    /** Plain text only: the first item's text, never a coerced URI or intent. */
    override suspend fun readClipboardText(): ClipboardRead {
        val clipboard = clipboardManager() ?: return ClipboardRead.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        val clip = try {
            clipboard.primaryClip
        } catch (e: SecurityException) {
            return ClipboardRead.Failed(PlatformShareFailure.CLIPBOARD_UNAVAILABLE)
        }
        if (clip == null || clip.itemCount == 0) return ClipboardRead.Empty
        val text = clip.getItemAt(0).text?.toString()
        return if (text.isNullOrEmpty()) ClipboardRead.Empty else ClipboardRead.Text(text)
    }

    private fun clipboardManager(): ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    private companion object {
        const val MIME_TEXT = "text/plain"

        /** The clip's description label (system metadata, not part of the copied text). */
        const val CLIP_LABEL = "TalentForge build code"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
