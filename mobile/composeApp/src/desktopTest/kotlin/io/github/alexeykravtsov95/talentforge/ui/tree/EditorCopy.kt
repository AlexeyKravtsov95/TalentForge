package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage

/**
 * The expected talent-editor copy of M5.REL.2b, per language — the test
 * oracle, written out literally and formatted here independently (Russian
 * plural forms by the CLDR rule below), never read back from the resources
 * it verifies.
 */
internal sealed class EditorCopy(val language: AppLanguage) {

    // header and navigation
    /** The editor title of an unnamed Warrior build — the same `<Class> build` fallback Home shows (TF-RC1-DRAFT-TITLE). */
    abstract val unnamedWarriorTitle: String
    abstract val undoAction: String
    abstract val redoAction: String
    abstract val buildActionsOpen: String
    abstract val goBack: String

    // loading, failure and autosave
    abstract val openingBuild: String
    abstract val openFailed: String
    abstract val loadingTalentData: String
    abstract val talentDataFailed: String
    abstract val autosaveFailed: String
    abstract val retry: String
    abstract val unknownRecordDetail: String
    abstract fun libraryFailure(detail: String): String

    // build actions and reset
    abstract val buildActionsTitle: String
    abstract val buildActionsClose: String
    abstract val resetTalents: String
    abstract val undo: String
    abstract fun resetNotice(points: Int): String

    // tabs, Fit, Back chip
    abstract fun pointsSpent(points: Int): String
    abstract val fitTree: String
    abstract val fitTreeAction: String
    abstract val returnAction: String
    abstract fun backTo(talent: String): String

    // canvas nodes
    abstract fun fallbackName(nodeId: Int): String
    abstract val selectTalent: String
    abstract val clearSelection: String
    abstract fun nodeState(state: TalentNodeState): String

    // inspector chrome
    abstract val selectPrompt: String
    abstract val expand: String
    abstract val collapse: String
    abstract val expandAction: String
    abstract val collapseAction: String
    abstract val rankCaption: String
    abstract val max: String
    abstract val maxRankReached: String
    abstract val show: String
    abstract fun addRank(talent: String): String
    abstract fun removeRank(talent: String): String
    abstract fun maxOut(talent: String): String
    abstract fun showAction(talent: String): String
    abstract fun tierPoints(spent: Int, required: Int): String

    // inspector lines
    abstract val available: String
    abstract val inProgress: String
    abstract val maxed: String
    abstract val unavailable: String
    abstract fun noPointsLeft(spent: Int, budget: Int): String
    abstract fun neededFor(state: String, origin: String): String
    abstract fun requiresPrerequisite(talent: String, rank: Int): String
    abstract fun requiresPrerequisites(count: Int): String
    abstract fun spendMorePoints(deficit: Int): String
    abstract val spendMorePointsUnspecified: String
    abstract val noRankToRefund: String
    abstract val noTalentPoints: String
    abstract fun neededBy(talent: String): String
    abstract val neededByAllocated: String
    abstract val tierLocked: String
    abstract fun requiresFirst(talent: String): String
    abstract val requiresFirstUnnamed: String
    abstract val actionNotAvailable: String

    // description labels
    abstract val current: String
    abstract val nextRank: String
    abstract val descriptionUnavailable: String
    abstract val missingCharacter: String
    abstract val missingData: String

    /** The expected rendering of a descriptor (names resolved through [nameOf]). */
    fun textOf(text: InspectorText): String = when (text) {
        InspectorText.Available -> available
        InspectorText.InProgress -> inProgress
        InspectorText.Maxed -> maxed
        InspectorText.Unavailable -> unavailable
        is InspectorText.NoPointsLeft -> noPointsLeft(text.spentPoints, text.pointBudget)
        is InspectorText.NeededFor -> neededFor(textOf(text.state), nameOf(text.origin))
        is InspectorText.RequiresPrerequisite -> requiresPrerequisite(nameOf(text.source), text.requiredRank)
        is InspectorText.RequiresPrerequisites -> requiresPrerequisites(text.count)
        is InspectorText.SpendMorePoints -> spendMorePoints(text.deficit)
        InspectorText.SpendMorePointsInTree -> spendMorePointsUnspecified
        is InspectorText.NoRankToRefund -> noRankToRefund
        is InspectorText.MaxRankReached -> maxRankReached
        is InspectorText.NoTalentPoints -> noTalentPoints
        is InspectorText.NeededBy -> text.dependent?.let { neededBy(nameOf(it)) } ?: neededByAllocated
        is InspectorText.TierLocked -> tierLocked
        is InspectorText.RequiresFirst -> text.source?.let { requiresFirst(nameOf(it)) } ?: requiresFirstUnnamed
        is InspectorText.ActionNotAvailable -> actionNotAvailable
    }

    fun nameOf(name: TalentName): String = name.published ?: fallbackName(name.nodeId)

    /** Every fixed (parameter-free) phrase — the set no other language may show. */
    val fixedPhrases: Set<String>
        get() = setOf(
            unnamedWarriorTitle, undoAction, redoAction, buildActionsOpen, openingBuild, openFailed, loadingTalentData,
            talentDataFailed, autosaveFailed, retry, buildActionsTitle, buildActionsClose, resetTalents, undo,
            fitTree, fitTreeAction, returnAction, selectTalent, clearSelection, selectPrompt, expand, collapse,
            expandAction, collapseAction, rankCaption, max, maxRankReached, show, available, inProgress, maxed,
            unavailable, spendMorePointsUnspecified, noRankToRefund, noTalentPoints, neededByAllocated,
            tierLocked, requiresFirstUnnamed, actionNotAvailable, current, nextRank, descriptionUnavailable,
            missingCharacter, missingData,
        ) + TalentNodeState.entries.map { nodeState(it) }

    object English : EditorCopy(AppLanguage.ENGLISH) {
        override val unnamedWarriorTitle = "Warrior build"
        override val undoAction = "Undo talent change"
        override val redoAction = "Redo talent change"
        override val buildActionsOpen = "open build actions"
        override val goBack = "go back"
        override val openingBuild = "Opening build…"
        override val openFailed = "This build can't be opened"
        override val loadingTalentData = "Loading talent data…"
        override val talentDataFailed = "Talent data failed to load"
        override val autosaveFailed = "Couldn't save your changes on this device"
        override val retry = "Retry"
        override val unknownRecordDetail = "this build no longer exists on this device"
        override fun libraryFailure(detail: String) = "the local build library could not be read ($detail)"
        override val buildActionsTitle = "Build actions"
        override val buildActionsClose = "close build actions"
        override val resetTalents = "Reset talents"
        override val undo = "Undo"
        override fun resetNotice(points: Int) =
            "Talents reset · $points ${if (points == 1) "point" else "points"} cleared"

        override fun pointsSpent(points: Int) = "$points ${if (points == 1) "point" else "points"} spent"
        override val fitTree = "Fit tree"
        override val fitTreeAction = "fit the tree"
        override val returnAction = "return to the talent"
        override fun backTo(talent: String) = "Back to $talent"
        override fun fallbackName(nodeId: Int) = "Talent $nodeId"
        override val selectTalent = "select talent"
        override val clearSelection = "clear selection"
        override fun nodeState(state: TalentNodeState) = when (state) {
            TalentNodeState.AVAILABLE -> "available"
            TalentNodeState.PARTIAL -> "in progress"
            TalentNodeState.MAXED -> "maxed"
            TalentNodeState.PREREQUISITE_LOCKED -> "locked by prerequisite"
            TalentNodeState.TIER_LOCKED -> "locked by tier"
            TalentNodeState.NO_POINTS -> "no points remaining"
            TalentNodeState.UNAVAILABLE -> "unavailable"
        }

        override val selectPrompt = "Select a talent to inspect it"
        override val expand = "Expand inspector"
        override val collapse = "Collapse inspector"
        override val expandAction = "expand inspector"
        override val collapseAction = "collapse inspector"
        override val rankCaption = "Rank"
        override val max = "Max"
        override val maxRankReached = "Max rank reached"
        override val show = "Show"
        override fun addRank(talent: String) = "Add rank to $talent"
        override fun removeRank(talent: String) = "Remove rank from $talent"
        override fun maxOut(talent: String) = "Max out $talent"
        override fun showAction(talent: String) = "show prerequisite $talent"
        override fun tierPoints(spent: Int, required: Int) = "Tier points $spent of $required"
        override val available = "Available"
        override val inProgress = "In progress"
        override val maxed = "Maxed"
        override val unavailable = "Unavailable"
        override fun noPointsLeft(spent: Int, budget: Int) = "No talent points left · $spent/$budget spent"
        override fun neededFor(state: String, origin: String) = "$state · needed for $origin"
        override fun requiresPrerequisite(talent: String, rank: Int) = "Requires $talent $rank/$rank"
        override fun requiresPrerequisites(count: Int) =
            "Requires $count prerequisite ${if (count == 1) "talent" else "talents"}"

        override fun spendMorePoints(deficit: Int) =
            "Spend $deficit more ${if (deficit == 1) "point" else "points"} in this tree"

        override val spendMorePointsUnspecified = "Spend more points in this tree"
        override val noRankToRefund = "No rank to refund"
        override val noTalentPoints = "No talent points left"
        override fun neededBy(talent: String) = "Needed by $talent"
        override val neededByAllocated = "Needed by an allocated talent"
        override val tierLocked = "Locked — spend more points in this tree"
        override fun requiresFirst(talent: String) = "Requires $talent first"
        override val requiresFirstUnnamed = "Requires a prerequisite talent"
        override val actionNotAvailable = "Action not available"
        override val current = "CURRENT"
        override val nextRank = "NEXT RANK"
        override val descriptionUnavailable = "Talent description unavailable for this client data."
        override val missingCharacter = "Values marked — depend on your character and are not shown."
        override val missingData = "Values marked — are not available in this client data."
    }

    object Russian : EditorCopy(AppLanguage.RUSSIAN) {
        override val unnamedWarriorTitle = "Билд воина"
        override val undoAction = "Отменить изменение талантов"
        override val redoAction = "Повторить изменение талантов"
        override val buildActionsOpen = "открыть действия с билдом"
        override val goBack = "назад"
        override val openingBuild = "Открытие билда…"
        override val openFailed = "Не удалось открыть билд"
        override val loadingTalentData = "Загрузка данных талантов…"
        override val talentDataFailed = "Не удалось загрузить данные талантов"
        override val autosaveFailed = "Не удалось сохранить изменения на этом устройстве"
        override val retry = "Повторить"
        override val unknownRecordDetail = "этого билда больше нет на устройстве"
        override fun libraryFailure(detail: String) = "не удалось прочитать локальную библиотеку билдов ($detail)"
        override val buildActionsTitle = "Действия с билдом"
        override val buildActionsClose = "закрыть действия с билдом"
        override val resetTalents = "Сбросить таланты"
        override val undo = "Отменить"
        override fun resetNotice(points: Int) = "Таланты сброшены · снято $points ${points.ru("очко", "очка", "очков")}"
        override fun pointsSpent(points: Int) = "потрачено $points ${points.ru("очко", "очка", "очков")}"
        override val fitTree = "Вписать дерево"
        override val fitTreeAction = "вписать дерево в экран"
        override val returnAction = "вернуться к таланту"
        override fun backTo(talent: String) = "Назад к «$talent»"
        override fun fallbackName(nodeId: Int) = "Талант $nodeId"
        override val selectTalent = "выбрать талант"
        override val clearSelection = "снять выделение"
        override fun nodeState(state: TalentNodeState) = when (state) {
            TalentNodeState.AVAILABLE -> "доступен"
            TalentNodeState.PARTIAL -> "частично изучен"
            TalentNodeState.MAXED -> "изучен полностью"
            TalentNodeState.PREREQUISITE_LOCKED -> "заблокирован требованием"
            TalentNodeState.TIER_LOCKED -> "заблокирован ярусом"
            TalentNodeState.NO_POINTS -> "нет свободных очков"
            TalentNodeState.UNAVAILABLE -> "недоступен"
        }

        override val selectPrompt = "Выберите талант, чтобы увидеть подробности"
        override val expand = "Развернуть панель таланта"
        override val collapse = "Свернуть панель таланта"
        override val expandAction = "развернуть панель таланта"
        override val collapseAction = "свернуть панель таланта"
        override val rankCaption = "Ранг"
        override val max = "Макс."
        override val maxRankReached = "Достигнут максимальный ранг"
        override val show = "Показать"
        override fun addRank(talent: String) = "Добавить ранг: $talent"
        override fun removeRank(talent: String) = "Убрать ранг: $talent"
        override fun maxOut(talent: String) = "Изучить полностью: $talent"
        override fun showAction(talent: String) = "показать требуемый талант: $talent"
        override fun tierPoints(spent: Int, required: Int) = "Очки яруса: $spent из $required"
        override val available = "Доступен"
        override val inProgress = "Частично изучен"
        override val maxed = "Изучен полностью"
        override val unavailable = "Недоступен"
        override fun noPointsLeft(spent: Int, budget: Int) = "Нет свободных очков · потрачено $spent/$budget"
        override fun neededFor(state: String, origin: String) = "$state · нужен для «$origin»"
        override fun requiresPrerequisite(talent: String, rank: Int) = "Требуется «$talent» $rank/$rank"
        override fun requiresPrerequisites(count: Int) =
            "Сначала изучите $count ${count.ru("требуемый талант", "требуемых таланта", "требуемых талантов")}"

        override fun spendMorePoints(deficit: Int) =
            "Вложите ещё $deficit ${deficit.ru("очко", "очка", "очков")} в эту ветку"

        override val spendMorePointsUnspecified = "Вложите больше очков в эту ветку"
        override val noRankToRefund = "Нет рангов для возврата"
        override val noTalentPoints = "Не осталось очков талантов"
        override fun neededBy(talent: String) = "Нужен для «$talent»"
        override val neededByAllocated = "Нужен для изученного таланта"
        override val tierLocked = "Заблокирован — вложите больше очков в эту ветку"
        override fun requiresFirst(talent: String) = "Сначала изучите «$talent»"
        override val requiresFirstUnnamed = "Сначала изучите требуемый талант"
        override val actionNotAvailable = "Действие недоступно"
        override val current = "СЕЙЧАС"
        override val nextRank = "СЛЕД. РАНГ"
        override val descriptionUnavailable = "Описание таланта недоступно в данных этого клиента."
        override val missingCharacter = "Значения, отмеченные «—», зависят от персонажа и не показываются."
        override val missingData = "Значения, отмеченные «—», отсутствуют в данных этого клиента."
    }

    companion object {
        fun of(language: AppLanguage): EditorCopy = when (language) {
            AppLanguage.ENGLISH -> English
            AppLanguage.RUSSIAN -> Russian
        }

        /**
         * The Russian (CLDR) plural form of a whole count: `one` for 1, 21,
         * 101 … (not 11), `few` for 2–4, 22–24 … (not 12–14), `many` otherwise.
         */
        fun Int.ru(one: String, few: String, many: String): String {
            val mod10 = this % 10
            val mod100 = this % 100
            return when {
                mod10 == 1 && mod100 != 11 -> one
                mod10 in 2..4 && mod100 !in 12..14 -> few
                else -> many
            }
        }
    }
}
