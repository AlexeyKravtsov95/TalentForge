package io.github.alexeykravtsov95.talentforge.ui.share

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryError
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.share.BuildImportFailure
import io.github.alexeykravtsov95.talentforge.share.BuildImportPreparation
import io.github.alexeykravtsov95.talentforge.share.BuildImportPreview
import io.github.alexeykravtsov95.talentforge.share.BuildShareCode
import io.github.alexeykravtsov95.talentforge.share.prepareBuildImport
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the one import flow stands. */
sealed interface ImportPhase {

    /** The player is typing or pasting; nothing is being checked. */
    data object Editing : ImportPhase

    /** [code] is being validated by `prepareBuildImport`. */
    data class Checking(val code: String) : ImportPhase

    /** The last attempt produced [problem]; the input stays as it was. */
    data class Problem(val problem: ImportProblem) : ImportPhase

    /**
     * The confirmation surface: [preview] is the validated build of exactly
     * [code]. [saving] while `createDraft` runs; [saveError] after a failed
     * write (the preview stays and Import retries).
     */
    data class Confirming(
        val code: String,
        val preview: BuildImportPreview,
        val saving: Boolean = false,
        val saveError: BuildLibraryError? = null,
    ) : ImportPhase

    /** Storage confirmed the new record; the host opens it. */
    data class Imported(val localId: String) : ImportPhase
}

/** Everything the import screen can tell the player went wrong (typed; mapped to localized copy). */
sealed interface ImportProblem {

    /** Continue with nothing but whitespace. */
    data object EmptyInput : ImportProblem

    /** A paste/edit larger than [BuildShareCode.MAX_INPUT_LENGTH] was refused; the field kept its text. */
    data object InputTooLong : ImportProblem

    /** `prepareBuildImport` rejected the code. */
    data class Rejected(val failure: BuildImportFailure) : ImportProblem

    /** Paste found no text on the clipboard. */
    data object ClipboardEmpty : ImportProblem

    /** The clipboard could not be read. */
    data class ClipboardUnavailable(val reason: PlatformShareFailure) : ImportProblem
}

/**
 * The single import flow behind Home's and All Builds' **Import build**
 * (M5.SHARE.2). Main-thread confined (Compose click handlers); validation and
 * the storage write hop to [work].
 *
 * Guarantees:
 * - validation is `prepareBuildImport(input, datasets)` on the exact text the
 *   player entered — no case change, no whitespace removal, no second parser;
 * - nothing is written before [confirm]; Cancel and Back never write;
 * - every edit of the input invalidates a running check and any preview (a
 *   generation counter drops stale results), and [confirm] refuses a preview
 *   whose code is not the current input;
 * - one check and one save at a time: repeated taps while either runs do
 *   nothing, so a confirmation creates exactly one record;
 * - a failed write keeps the preview and its error; Import retries;
 * - success is reported only after storage returned the new record.
 */
class BuildImportController(
    private val input: MutableState<String>,
    private val datasets: TalentDatasetRepository,
    private val repository: LocalBuildRepository,
    private val scope: CoroutineScope,
    private val work: CoroutineContext,
) {

    var phase: ImportPhase by mutableStateOf(ImportPhase.Editing)
        private set

    private var generation = 0

    val text: String get() = input.value

    /** True while a check or a save runs: every action except typing waits. */
    val busy: Boolean
        get() = when (val current = phase) {
            is ImportPhase.Checking -> true
            is ImportPhase.Confirming -> current.saving
            is ImportPhase.Imported -> true
            else -> false
        }

    /** Continue is offered only for non-blank input while nothing runs. */
    val canContinue: Boolean get() = !busy && !isBlankCode(text)

    /**
     * The player edited the field. Refused (the field keeps its text) while a
     * save runs or when the new text exceeds the share-code input limit —
     * truncating it would silently change the payload.
     */
    fun onInputChange(newText: String) {
        if (isSaving()) return
        if (newText.length > BuildShareCode.MAX_INPUT_LENGTH) {
            generation += 1
            phase = ImportPhase.Problem(ImportProblem.InputTooLong)
            return
        }
        if (newText == input.value) return
        input.value = newText
        generation += 1
        phase = ImportPhase.Editing
    }

    fun clear() = onInputChange("")

    /** Continue: validate the current input. */
    fun submit() {
        if (busy) return
        val code = input.value
        if (isBlankCode(code)) {
            phase = ImportPhase.Problem(ImportProblem.EmptyInput)
            return
        }
        val token = ++generation
        phase = ImportPhase.Checking(code)
        scope.launch {
            val result = withContext(work) { prepareBuildImport(code, datasets) }
            // a newer edit (or a cleared field) wins over this stale result
            if (token != generation || input.value != code || phase != ImportPhase.Checking(code)) return@launch
            phase = when (result) {
                is BuildImportPreparation.Ready -> ImportPhase.Confirming(code = code, preview = result.preview)
                is BuildImportPreparation.Rejected -> ImportPhase.Problem(ImportProblem.Rejected(result.failure))
            }
        }
    }

    /** Cancel on the confirmation surface: back to the input, nothing written. */
    fun dismissPreview() {
        val current = phase as? ImportPhase.Confirming ?: return
        if (current.saving) return
        generation += 1
        phase = ImportPhase.Editing
    }

    /**
     * Import: persists the validated build as a new independent Draft and
     * reports its id through [onImported] once storage confirmed it.
     */
    fun confirm(onImported: (String) -> Unit) {
        val current = phase as? ImportPhase.Confirming ?: return
        if (current.saving) return
        if (current.code != input.value) {
            // never confirm a build for a code the player no longer has
            generation += 1
            phase = ImportPhase.Editing
            return
        }
        val saving = current.copy(saving = true, saveError = null)
        phase = saving
        scope.launch {
            val outcome = withContext(work) { repository.createDraft(saving.preview.build) }
            phase = when (outcome) {
                is BuildLibraryOutcome.Loaded -> ImportPhase.Imported(outcome.value.localId)
                is BuildLibraryOutcome.Failed -> saving.copy(saving = false, saveError = outcome.error)
            }
            (phase as? ImportPhase.Imported)?.let { onImported(it.localId) }
        }
    }

    /** Applies a clipboard read of the explicit Paste button: the text replaces the field. */
    fun onPasted(read: ClipboardRead) {
        if (busy) return
        when (read) {
            is ClipboardRead.Text -> onInputChange(read.text)
            ClipboardRead.Empty -> phase = ImportPhase.Problem(ImportProblem.ClipboardEmpty)
            is ClipboardRead.Failed -> phase = ImportPhase.Problem(ImportProblem.ClipboardUnavailable(read.reason))
        }
    }

    private fun isSaving(): Boolean {
        val current = phase
        return (current is ImportPhase.Confirming && current.saving) || current is ImportPhase.Imported
    }
}

/**
 * Blank in the share-code sense: only the characters the decoder itself
 * ignores around a code (space, tab, CR, LF). Used to disable Continue; the
 * text itself is never trimmed or rewritten.
 */
internal fun isBlankCode(text: String): Boolean = text.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
