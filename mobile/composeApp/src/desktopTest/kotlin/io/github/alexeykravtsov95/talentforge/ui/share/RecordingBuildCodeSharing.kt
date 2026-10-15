package io.github.alexeykravtsov95.talentforge.ui.share

/**
 * Desktop-test double of the platform share boundary: records the EXACT text
 * every call dispatched and answers with configurable outcomes, so tests can
 * assert what would have reached the Android chooser / iOS activity sheet /
 * clipboard, byte for byte.
 */
class RecordingBuildCodeSharing(
    var shareOutcome: TextShareOutcome = TextShareOutcome.SheetOpened,
    var copyOutcome: ClipboardWrite = ClipboardWrite.Copied,
    var clipboard: ClipboardRead = ClipboardRead.Empty,
) : BuildCodeSharing {

    val shared = mutableListOf<String>()
    val copied = mutableListOf<String>()
    var clipboardReads = 0
        private set

    override suspend fun copyToClipboard(text: String): ClipboardWrite {
        copied += text
        return copyOutcome
    }

    override suspend fun shareText(text: String): TextShareOutcome {
        shared += text
        return shareOutcome
    }

    override suspend fun readClipboardText(): ClipboardRead {
        clipboardReads += 1
        return clipboard
    }
}
