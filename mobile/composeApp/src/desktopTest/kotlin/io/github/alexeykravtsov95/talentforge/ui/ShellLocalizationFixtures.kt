package io.github.alexeykravtsov95.talentforge.ui

import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.allocation.buildFileOf
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetLoadError
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * The expected UI copy of the M5.REL.2a shell screens, per language — the
 * test oracle, written out literally (never read back from the resources it
 * verifies).
 */
internal data class ShellCopy(
    val language: AppLanguage,
    val newBuild: String,
    val newBuildAction: String,
    val goBack: String,
    val retry: String,
    val cancel: String,
    val save: String,
    val libraryLoading: String,
    val libraryFailedTitle: String,
    val libraryFailedBody: String,
    /** TF-UX-01: the purpose line under the wordmark, in every Home state. */
    val homeSubtitle: String,
    val homeEmptyTitle: String,
    val homeEmptyBody: String,
    val homeDataVersion: String,
    val continueSection: String,
    val recentSection: String,
    val continueEditing: String,
    val allBuildsAction: String,
    val buildsTitle: String,
    val buildsEmptyTitle: String,
    val buildsEmptyBody: String,
    val duplicated: String,
    val duplicateFailed: String,
    /** Appended to a NAMED build's name by Duplicate (`builds_copy_name_suffix`). */
    val copySuffix: String,
    val sheetCloseAction: String,
    val rename: String,
    val duplicate: String,
    val delete: String,
    val dialogCloseAction: String,
    val renameTitle: String,
    val renameField: String,
    val renameFailed: String,
    val deleteBody: String,
    val deleteFailed: String,
    val chooseClass: String,
    val classDataVersion: String,
    val classesLoading: String,
    val classesFailedTitle: String,
    val classesFailedBody: String,
    val creatingBuild: String,
    val draftNotSaved: String,
    val classNames: Map<String, String>,
    val unnamedTitles: Map<String, String>,
    private val allBuildsLinkFormat: (Int) -> String,
    private val buildsTitleFormat: (Int) -> String,
    private val continueEditingActionFormat: (String) -> String,
    private val openActionFormat: (String) -> String,
    private val rowActionsFormat: (String) -> String,
    private val manageFormat: (String) -> String,
    private val deleteTitleFormat: (String) -> String,
    private val createActionFormat: (String) -> String,
) {
    fun allBuildsLink(count: Int) = allBuildsLinkFormat(count)
    fun buildsTitle(count: Int) = buildsTitleFormat(count)
    fun continueEditingAction(title: String) = continueEditingActionFormat(title)
    fun openAction(title: String) = openActionFormat(title)
    fun rowActions(title: String) = rowActionsFormat(title)
    fun manageAction(title: String) = manageFormat(title)
    fun deleteTitle(title: String) = deleteTitleFormat(title)
    fun createAction(className: String) = createActionFormat(className)
    fun className(token: String) = classNames.getValue(token)
    fun unnamedTitle(token: String) = unnamedTitles.getValue(token)

    companion object {
        val ENGLISH = ShellCopy(
            language = AppLanguage.ENGLISH,
            newBuild = "New build",
            newBuildAction = "start a new build",
            goBack = "go back",
            retry = "Retry",
            cancel = "Cancel",
            save = "Save",
            libraryLoading = "Loading your builds…",
            libraryFailedTitle = "Couldn't load your builds",
            libraryFailedBody = "The local build library could not be read",
            homeSubtitle = "Talent build planner for the game WoW\u00A0Forever",
            homeEmptyTitle = "No builds yet",
            homeEmptyBody = "Pick a class and spend points on its talent tree. " +
                "Builds are stored on this device; no account is needed.",
            homeDataVersion = "Forever talent data 1.60.1",
            continueSection = "CONTINUE",
            recentSection = "RECENT",
            continueEditing = "Continue editing",
            allBuildsAction = "open all builds",
            buildsTitle = "Builds",
            buildsEmptyTitle = "No saved builds yet.",
            buildsEmptyBody = "Create a build to start planning talents.",
            duplicated = "Build duplicated",
            duplicateFailed = "Couldn't duplicate the build",
            copySuffix = " (copy)",
            sheetCloseAction = "close build actions",
            rename = "Rename",
            duplicate = "Duplicate",
            delete = "Delete",
            dialogCloseAction = "close dialog",
            renameTitle = "Rename build",
            renameField = "build name",
            renameFailed = "Couldn't rename the build",
            deleteBody = "This permanently deletes the build from this device and cannot be undone.",
            deleteFailed = "Couldn't delete the build",
            chooseClass = "Choose a class",
            classDataVersion = "Forever · talent data 1.60.1",
            classesLoading = "Loading classes…",
            classesFailedTitle = "Couldn't load classes",
            classesFailedBody = "The local talent data could not be read.",
            creatingBuild = "Creating build",
            draftNotSaved = "The draft could not be saved on this device.",
            classNames = mapOf(
                "WARRIOR" to "Warrior", "PALADIN" to "Paladin", "HUNTER" to "Hunter",
                "ROGUE" to "Rogue", "PRIEST" to "Priest", "SHAMAN" to "Shaman",
                "MAGE" to "Mage", "WARLOCK" to "Warlock", "DRUID" to "Druid",
            ),
            unnamedTitles = mapOf(
                "WARRIOR" to "Warrior build", "PALADIN" to "Paladin build", "HUNTER" to "Hunter build",
                "ROGUE" to "Rogue build", "PRIEST" to "Priest build", "SHAMAN" to "Shaman build",
                "MAGE" to "Mage build", "WARLOCK" to "Warlock build", "DRUID" to "Druid build",
            ),
            allBuildsLinkFormat = { "All builds $it" },
            buildsTitleFormat = { "Builds $it" },
            continueEditingActionFormat = { "continue editing $it" },
            openActionFormat = { "open $it" },
            rowActionsFormat = { "build actions for $it" },
            manageFormat = { "manage $it" },
            deleteTitleFormat = { "Delete \"$it\"?" },
            createActionFormat = { "create a $it build" },
        )

        val RUSSIAN = ShellCopy(
            language = AppLanguage.RUSSIAN,
            newBuild = "Новый билд",
            newBuildAction = "создать новый билд",
            goBack = "назад",
            retry = "Повторить",
            cancel = "Отмена",
            save = "Сохранить",
            libraryLoading = "Загрузка билдов…",
            libraryFailedTitle = "Не удалось загрузить билды",
            libraryFailedBody = "Не удалось прочитать локальную библиотеку билдов",
            homeSubtitle = "Планировщик талантов для игры WoW\u00A0Forever",
            homeEmptyTitle = "Пока нет билдов",
            homeEmptyBody = "Выберите класс и распределите очки в его дереве талантов. " +
                "Билды хранятся на этом устройстве — аккаунт не нужен.",
            homeDataVersion = "Данные талантов Forever 1.60.1",
            continueSection = "ПРОДОЛЖИТЬ",
            recentSection = "НЕДАВНИЕ",
            continueEditing = "Продолжить",
            allBuildsAction = "открыть все билды",
            buildsTitle = "Билды",
            buildsEmptyTitle = "Сохранённых билдов пока нет.",
            buildsEmptyBody = "Создайте билд, чтобы начать планировать таланты.",
            duplicated = "Копия билда создана",
            duplicateFailed = "Не удалось создать копию билда",
            copySuffix = " (копия)",
            sheetCloseAction = "закрыть действия с билдом",
            rename = "Переименовать",
            duplicate = "Создать копию",
            delete = "Удалить",
            dialogCloseAction = "закрыть окно",
            renameTitle = "Новое название",
            renameField = "название билда",
            renameFailed = "Не удалось переименовать билд",
            deleteBody = "Билд будет навсегда удалён с этого устройства. Это действие нельзя отменить.",
            deleteFailed = "Не удалось удалить билд",
            chooseClass = "Выберите класс",
            classDataVersion = "Forever · данные талантов 1.60.1",
            classesLoading = "Загрузка классов…",
            classesFailedTitle = "Не удалось загрузить классы",
            classesFailedBody = "Не удалось прочитать локальные данные талантов.",
            creatingBuild = "Создание билда",
            draftNotSaved = "Не удалось сохранить черновик на этом устройстве.",
            classNames = mapOf(
                "WARRIOR" to "Воин", "PALADIN" to "Паладин", "HUNTER" to "Охотник",
                "ROGUE" to "Разбойник", "PRIEST" to "Жрец", "SHAMAN" to "Шаман",
                "MAGE" to "Маг", "WARLOCK" to "Чернокнижник", "DRUID" to "Друид",
            ),
            unnamedTitles = mapOf(
                "WARRIOR" to "Билд воина", "PALADIN" to "Билд паладина", "HUNTER" to "Билд охотника",
                "ROGUE" to "Билд разбойника", "PRIEST" to "Билд жреца", "SHAMAN" to "Билд шамана",
                "MAGE" to "Билд мага", "WARLOCK" to "Билд чернокнижника", "DRUID" to "Билд друида",
            ),
            allBuildsLinkFormat = { "Все билды\u00A0$it" }, // the count stays with its noun
            buildsTitleFormat = { "Билды\u00A0$it" },
            continueEditingActionFormat = { "продолжить редактирование: $it" },
            openActionFormat = { "открыть: $it" },
            rowActionsFormat = { "действия: $it" },
            manageFormat = { "управление: $it" },
            deleteTitleFormat = { "Удалить «$it»?" },
            createActionFormat = { "создать билд: $it" },
        )

        fun of(language: AppLanguage): ShellCopy = when (language) {
            AppLanguage.ENGLISH -> ENGLISH
            AppLanguage.RUSSIAN -> RUSSIAN
        }
    }
}

/** Real persisted libraries and a failing dataset double for the shell UI tests. */
internal object ShellLibraryFixtures {

    /** One build to persist: [ranks] ranks of [talent] (a fresh draft when none), an optional user [name]. */
    data class Build(val token: String, val talent: String? = null, val ranks: Int = 0, val name: String? = null)

    private val datasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    fun documentOf(token: String): TalentDataDocument = runBlocking {
        assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
            datasets.document(DatasetId(PRODUCT_BUNDLE.game, PRODUCT_BUNDLE.clientBuild, ClassToken(token))),
        ).value
    }

    /**
     * Persists [builds] in order through the real repository, engine-spent on
     * the product bundle, advancing [clock] by two hours after each — the
     * last one is the most recently edited (Continue).
     */
    fun persist(storage: FakeBuildLibraryStorage, clock: FixedClock, builds: List<Build>) {
        val repository = LocalBuildRepository(storage, clock, SequentialIds())
        for (build in builds) {
            val document = documentOf(build.token)
            val engine = TalentAllocationEngine(document)
            var state = engine.emptyState()
            if (build.talent != null) {
                val nodeId = engine.tree.nodes.first { it.name == build.talent }.id
                repeat(build.ranks) { state = engine.spend(state, nodeId).state }
            }
            val file = buildFileOf(
                state = state,
                game = document.game,
                clientBuild = document.clientBuild,
                classToken = document.classInfo.token,
            )
            val created = assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.createDraft(file)).value
            if (build.name != null) {
                assertIs<BuildLibraryOutcome.Loaded<LocalBuildRecord>>(repository.renameBuild(created.localId, build.name))
            }
            clock.advanceBy(TWO_HOURS_MS)
        }
    }

    const val TWO_HOURS_MS = 7_200_000L

    /**
     * An unnamed record of class [token] for presentation-only checks: a real
     * Warrior draft whose class token is replaced (so a token outside the
     * shipped classes — "MONK" — can be expressed too).
     */
    fun recordOf(token: String): LocalBuildRecord {
        val storage = FakeBuildLibraryStorage()
        persist(storage, FixedClock(), listOf(Build("WARRIOR")))
        val record = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
            LocalBuildRepository(storage, FixedClock(), SequentialIds()).load(),
        ).value.single()
        return record.copy(build = record.build.copy(classToken = ClassToken(token)))
    }

    /** The real bundle, except that the manifest can be made to fail. */
    class FailingDatasets(
        private val delegate: TalentDatasetRepository,
        private val failManifest: Boolean = false,
    ) : TalentDatasetRepository {
        override suspend fun manifest(bundle: DatasetBundleId): DatasetOutcome<DatasetManifest> =
            if (failManifest) {
                DatasetOutcome.Failed(
                    DatasetLoadError.AssetUnreadable(
                        path = "dataset/${bundle.game.value}/${bundle.clientBuild}/manifest.json",
                        detail = "injected manifest failure",
                    ),
                )
            } else {
                delegate.manifest(bundle)
            }

        override suspend fun document(id: DatasetId): DatasetOutcome<TalentDataDocument> = delegate.document(id)
    }
}
