package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.runtime.Composable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_allocation
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_class
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_client_build
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_damaged
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_dataset
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_empty
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_not_a_code
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_paste_empty
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_paste_failed
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_too_long
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.import_error_unsupported_version
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_error_build_missing
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_error_clipboard
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_error_encode
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_error_library
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_error_no_target
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.share_error_presentation
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.share.BuildImportFailure
import io.github.alexeykravtsov95.talentforge.share.ShareCodeDecodeError
import io.github.alexeykravtsov95.talentforge.share.ShareCodeViolation
import org.jetbrains.compose.resources.stringResource

/**
 * The player-facing categories of an import problem (M5.SHARE.2). Every typed
 * failure maps to exactly one; the screen renders the category — never an
 * exception text, a file path or an engine diagnostic.
 */
sealed interface ImportMessage {
    data object EmptyInput : ImportMessage

    data object TooLong : ImportMessage

    /** Not a TalentForge code at all (wrong prefix, characters outside the code alphabet). */
    data object NotACode : ImportMessage

    /** A TalentForge code of a format or build-schema version this app does not read. */
    data object UnsupportedVersion : ImportMessage

    /** A TalentForge code damaged or cut off in transit (checksum, truncation, malformed body). */
    data object Damaged : ImportMessage

    /** Made with game data this app does not ship; [clientBuild] is named exactly. */
    data class ClientBuildUnavailable(val clientBuild: String) : ImportMessage

    data object ClassUnavailable : ImportMessage

    /** The talents are not a valid plan on the code's own game data (tree, nodes, ranks, rules). */
    data object InvalidAllocation : ImportMessage

    /** The exact talent data on this device failed to load, or the engine refused it. */
    data object DatasetProblem : ImportMessage

    data object ClipboardEmpty : ImportMessage

    data object ClipboardUnavailable : ImportMessage
}

/** The one mapping from typed import problems to message categories. */
fun importMessageOf(problem: ImportProblem): ImportMessage = when (problem) {
    ImportProblem.EmptyInput -> ImportMessage.EmptyInput
    ImportProblem.InputTooLong -> ImportMessage.TooLong
    ImportProblem.ClipboardEmpty -> ImportMessage.ClipboardEmpty
    is ImportProblem.ClipboardUnavailable -> ImportMessage.ClipboardUnavailable
    is ImportProblem.Rejected -> when (val failure = problem.failure) {
        is BuildImportFailure.InvalidCode -> decodeMessageOf(failure.error)
        is BuildImportFailure.ClientBuildUnavailable -> ImportMessage.ClientBuildUnavailable(failure.clientBuild)
        is BuildImportFailure.ClassUnavailable -> ImportMessage.ClassUnavailable
        is BuildImportFailure.TreeMismatch -> ImportMessage.InvalidAllocation
        is BuildImportFailure.InvalidAllocation -> ImportMessage.InvalidAllocation
        is BuildImportFailure.DatasetRejected -> ImportMessage.DatasetProblem
        is BuildImportFailure.EngineUnavailable -> ImportMessage.DatasetProblem
    }
}

private fun decodeMessageOf(error: ShareCodeDecodeError): ImportMessage = when (error) {
    ShareCodeDecodeError.Empty -> ImportMessage.EmptyInput
    is ShareCodeDecodeError.TooLong -> ImportMessage.TooLong
    ShareCodeDecodeError.NotAShareCode -> ImportMessage.NotACode
    is ShareCodeDecodeError.InvalidCharacter -> ImportMessage.NotACode
    is ShareCodeDecodeError.UnsupportedFormatVersion -> ImportMessage.UnsupportedVersion
    is ShareCodeViolation.UnsupportedBuildSchemaVersion -> ImportMessage.UnsupportedVersion
    ShareCodeDecodeError.InvalidEncoding,
    ShareCodeDecodeError.Truncated,
    is ShareCodeDecodeError.ChecksumMismatch,
    ShareCodeDecodeError.MalformedNumber,
    is ShareCodeDecodeError.TrailingData,
    is ShareCodeViolation.InvalidIdentity,
    is ShareCodeViolation.NumberOutOfRange,
    is ShareCodeViolation.DuplicateNode,
    is ShareCodeViolation.DuplicateEntry,
    is ShareCodeViolation.TooManyAllocations,
    -> ImportMessage.Damaged
}

/** Localized text of an [ImportMessage]. */
@Composable
fun ImportMessage.text(): String = when (this) {
    ImportMessage.EmptyInput -> stringResource(Res.string.import_error_empty)
    ImportMessage.TooLong -> stringResource(Res.string.import_error_too_long)
    ImportMessage.NotACode -> stringResource(Res.string.import_error_not_a_code)
    ImportMessage.UnsupportedVersion -> stringResource(Res.string.import_error_unsupported_version)
    ImportMessage.Damaged -> stringResource(Res.string.import_error_damaged)
    is ImportMessage.ClientBuildUnavailable -> stringResource(Res.string.import_error_client_build, clientBuild)
    ImportMessage.ClassUnavailable -> stringResource(Res.string.import_error_class)
    ImportMessage.InvalidAllocation -> stringResource(Res.string.import_error_allocation)
    ImportMessage.DatasetProblem -> stringResource(Res.string.import_error_dataset)
    ImportMessage.ClipboardEmpty -> stringResource(Res.string.import_error_paste_empty)
    ImportMessage.ClipboardUnavailable -> stringResource(Res.string.import_error_paste_failed)
}

/** Localized text of an export failure (the share sheet could not get a code). */
@Composable
fun BuildExportFailure.text(): String = when (this) {
    is BuildExportFailure.Encode -> stringResource(Res.string.share_error_encode)
    is BuildExportFailure.Library -> when (error) {
        is BuildLibraryError.UnknownRecord -> stringResource(Res.string.share_error_build_missing)
        else -> stringResource(Res.string.share_error_library)
    }
}

/** Localized text of a platform share/clipboard failure. */
@Composable
fun PlatformShareFailure.text(): String = when (this) {
    PlatformShareFailure.NO_SHARE_TARGET -> stringResource(Res.string.share_error_no_target)
    PlatformShareFailure.PRESENTATION_UNAVAILABLE -> stringResource(Res.string.share_error_presentation)
    PlatformShareFailure.CLIPBOARD_UNAVAILABLE -> stringResource(Res.string.share_error_clipboard)
}
