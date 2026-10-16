package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.runtime.Composable

/**
 * The narrow platform boundary of build sharing (M5.SHARE.2): plain-text
 * share sheet and clipboard, nothing else. The text handed in is always the
 * exact canonical `talentforge-share.v1` code — implementations pass it to
 * the platform verbatim (no label, no line breaks, no decoration).
 *
 * Every call is user-initiated, needs no permission and no network, and never
 * throws for a platform problem: failures come back typed so the UI can show
 * a recoverable, localized message. Tests inject a recording implementation.
 */
interface BuildCodeSharing {

    /** Puts [text] on the system clipboard as plain text. */
    suspend fun copyToClipboard(text: String): ClipboardWrite

    /**
     * Offers [text] through the platform's native share sheet. The outcome
     * never claims delivery the platform did not report: opening a chooser is
     * not "shared".
     */
    suspend fun shareText(text: String): TextShareOutcome

    /** Reads plain text from the system clipboard (the import screen's explicit Paste). */
    suspend fun readClipboardText(): ClipboardRead
}

/** Outcome of [BuildCodeSharing.copyToClipboard]. */
sealed interface ClipboardWrite {
    data object Copied : ClipboardWrite

    data class Failed(val reason: PlatformShareFailure) : ClipboardWrite
}

/** Outcome of [BuildCodeSharing.shareText]. Only [Failed] is an error. */
sealed interface TextShareOutcome {

    /**
     * The native chooser opened; the platform reports nothing about what the
     * user did next (Android `Intent.createChooser`). Not a success claim.
     */
    data object SheetOpened : TextShareOutcome

    /** The platform reports the user completed an activity in the sheet (iOS). */
    data object Completed : TextShareOutcome

    /** The user dismissed the sheet without choosing anything: neither error nor delivery. */
    data object Cancelled : TextShareOutcome

    /**
     * This host has no share sheet (desktop development builds): the code was
     * put on the clipboard instead, and the UI says so.
     */
    data object CopiedToClipboard : TextShareOutcome

    data class Failed(val reason: PlatformShareFailure) : TextShareOutcome
}

/** Outcome of [BuildCodeSharing.readClipboardText]. */
sealed interface ClipboardRead {
    data class Text(val text: String) : ClipboardRead

    /** The clipboard holds no plain text. */
    data object Empty : ClipboardRead

    data class Failed(val reason: PlatformShareFailure) : ClipboardRead
}

/** Typed platform failure reasons; the UI maps each to localized copy, never to a raw exception. */
enum class PlatformShareFailure {

    /** No app on the device can receive plain text (Android: no chooser target). */
    NO_SHARE_TARGET,

    /** The share sheet could not be presented (no foreground screen to present from). */
    PRESENTATION_UNAVAILABLE,

    /** The system clipboard refused the operation or is not available. */
    CLIPBOARD_UNAVAILABLE,
}

/**
 * The host platform's [BuildCodeSharing]: Android share chooser + system
 * clipboard, iOS `UIActivityViewController` + `UIPasteboard`, desktop AWT
 * clipboard (its Share copies, and says so).
 */
@Composable
expect fun rememberPlatformBuildCodeSharing(): BuildCodeSharing
