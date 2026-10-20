package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_days_ago
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_weeks_ago
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.TalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.builds.BuildLibraryOutcome
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRecord
import io.github.alexeykravtsov95.talentforge.data.builds.LocalBuildRepository
import io.github.alexeykravtsov95.talentforge.ui.ShellLibraryFixtures.Build
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import io.github.alexeykravtsov95.talentforge.ui.library.BuildCardModel
import io.github.alexeykravtsov95.talentforge.ui.library.buildCardTitle
import io.github.alexeykravtsov95.talentforge.ui.library.localizedClassName
import io.github.alexeykravtsov95.talentforge.ui.library.relativeBuildTime
import io.github.alexeykravtsov95.talentforge.ui.motion.LocalReducedMotion
import io.github.alexeykravtsov95.talentforge.ui.newbuild.NewBuildScreen
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.EditorCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.pluralStringResource
import org.junit.Rule

/**
 * M5.REL.2a: the localized shell screens — Home, All Builds (+ its sheet and
 * dialogs) and Class Selection — against the REAL app root and real bundled
 * datasets, in English and Russian: every main state's copy, the
 * accessibility labels, the nine class names, the relative time with Russian
 * plural forms, user names shown verbatim, a language change that never
 * touches stored builds, and the 320 / 393 dp × 100 / 200 % matrix with no
 * mid-word line breaks and no clipped text.
 */
@OptIn(ExperimentalTestApi::class)
class ShellLocalizationUiTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private var storage = FakeBuildLibraryStorage()
    private val clock = FixedClock(startMillis = 1_700_000_000_000L)
    private val realDatasets = BundledTalentDatasetRepository(ComposeResourceDatasetAssetSource())

    private fun onScreen(
        language: AppLanguage,
        widthDp: Int = 393,
        heightDp: Int = 852,
        fontScale: Float = 1f,
        content: @Composable () -> Unit = { App(storage = storage, clock = clock, ids = SequentialIds()) },
        block: ComposeUiTest.() -> Unit,
    ) {
        uiLanguage.language = language
        runDesktopComposeUiTest(width = widthDp, height = heightDp) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, fontScale),
                        LocalReducedMotion provides true,
                    ) {
                        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }
                    }
                }
            }
            block()
        }
    }

    private fun classSelection(datasets: TalentDatasetRepository): @Composable () -> Unit = {
        NewBuildScreen(
            repository = LocalBuildRepository(storage, clock, SequentialIds()),
            datasets = datasets,
            onBack = {},
            onDraftCreated = {},
        )
    }

    private fun ComposeUiTest.awaitText(text: String) {
        try {
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("'$text' never appeared; on screen: ${texts()}", timeout)
        }
    }

    private fun ComposeUiTest.click(text: String) {
        awaitText(text)
        onAllNodesWithText(text).onFirst().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openRowActions(title: String, copy: ShellCopy) {
        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithContentDescription(copy.rowActions(title)).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription(copy.rowActions(title)).onFirst().performClick()
        awaitText(copy.rename)
    }

    private fun ComposeUiTest.allNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.texts(): Set<String> =
        allNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }.toSet()

    /** Every accessibility click label and content description on screen. */
    private fun ComposeUiTest.a11yLabels(): Set<String> = allNodes().flatMap { node ->
        listOfNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.label) +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.toSet()

    private fun ComposeUiTest.assertShows(vararg expected: String) {
        val shown = texts()
        for (text in expected) assertTrue(text in shown, "'$text' is on screen (shown: $shown)")
    }

    private fun ComposeUiTest.assertA11y(vararg expected: String) {
        val labels = a11yLabels()
        for (label in expected) assertTrue(label in labels, "a11y label '$label' exists (labels: $labels)")
    }

    /** No English copy of these screens — text or accessibility label — on a Russian screen. */
    private fun ComposeUiTest.assertNoEnglishShellCopy() {
        val english = with(ShellCopy.ENGLISH) {
            setOf(
                newBuild, newBuildAction, goBack, retry, cancel, save, homeEmptyTitle, continueSection,
                recentSection, continueEditing, buildsTitle, rename, duplicate, delete, renameTitle,
                chooseClass, libraryFailedTitle, allBuildsAction, sheetCloseAction, dialogCloseAction,
            ) + classNames.values + unnamedTitles.values
        }
        val leaked = (texts() + a11yLabels()).filter { it in english }
        assertTrue(leaked.isEmpty(), "no English shell copy on a Russian screen: $leaked")
    }

    /**
     * The responsive guarantees of every text on screen: a wrapped text
     * breaks only between words (never "Удалит|ь"), a text that is not
     * deliberately ellipsized never overflows its box, and no text extends
     * past the window's width.
     */
    private fun ComposeUiTest.assertTextsFitAndWrapAtWords(where: String) {
        val rootWidth = onRoot().fetchSemanticsNode().size.width
        for (node in allNodes()) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val results = mutableListOf<TextLayoutResult>()
            action.action?.invoke(results)
            val layout = results.firstOrNull() ?: continue
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = false)
                val previous = text.getOrNull(end - 1)
                assertTrue(
                    previous == null || previous.isWhitespace() || previous in WORD_BREAK_PUNCTUATION,
                    "$where: '$text' breaks mid-word after '${text.substring(0, end)}'",
                )
            }
            if (layout.layoutInput.overflow != TextOverflow.Ellipsis && layout.lineCount > 0) {
                // The laid-out glyphs, not the paragraph's layout width, must fit the
                // space the text's parent gave it. Not the text's own box alone: on
                // macOS fonts (SF) Skia's intrinsic width of a single word can be a
                // few px under its laid-out glyphs, so a word sized to its intrinsic
                // width ("RECENT", 98.7 px in a 96 px box with the whole row free)
                // overhangs its box without being cut or touching anything.
                val constraints = layout.layoutInput.constraints
                val availableWidth = maxOf(layout.size.width, constraints.maxWidth)
                val availableHeight = maxOf(layout.size.height, constraints.maxHeight)
                val contentRight = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
                val contentBottom = layout.getLineBottom(layout.lineCount - 1)
                assertTrue(
                    contentRight <= availableWidth + OVERFLOW_TOLERANCE_PX &&
                        contentBottom <= availableHeight + OVERFLOW_TOLERANCE_PX,
                    "$where: '$text' overflows the space it was given " +
                        "($contentRight x $contentBottom in ${layout.size}, $constraints)",
                )
            }
            val bounds = node.boundsInRoot
            assertTrue(bounds.left >= -0.5f && bounds.right <= rootWidth + 0.5f, "$where: '$text' leaves the window")
        }
    }

    /** Four builds: an unnamed Warlock draft, a spent Rogue, a user-named Mage and the spent Warrior (Continue). */
    private fun populate() {
        storage = FakeBuildLibraryStorage()
        ShellLibraryFixtures.persist(
            storage,
            clock,
            listOf(
                Build("WARLOCK"),
                Build("ROGUE", talent = "Improved Sinister Strike", ranks = 2),
                Build("MAGE", name = "Arcane levelling"),
                Build("WARRIOR", talent = "Improved Rend", ranks = 3),
            ),
        )
    }

    // ------------------------------------------------------------------ Home

    @Test
    fun homeEmptyStateInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage()
            onScreen(language) {
                awaitText(copy.homeEmptyTitle)
                assertShows("TalentForge", copy.homeEmptyTitle, copy.homeEmptyBody, copy.homeDataVersion, copy.newBuild)
                assertA11y(copy.newBuildAction)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()
            }
        }
    }

    @Test
    fun homeWithBuildsInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            populate()
            onScreen(language) {
                awaitText(copy.continueSection)
                val warrior = copy.unnamedTitle("WARRIOR")
                assertShows(
                    copy.continueSection,
                    copy.recentSection,
                    warrior,
                    copy.continueEditing,
                    copy.allBuildsLink(4),
                    "Arcane levelling", // a user name, verbatim in every language
                    copy.unnamedTitle("ROGUE"),
                    copy.unnamedTitle("WARLOCK"),
                    copy.newBuild,
                )
                val twoHoursAgo = if (language == AppLanguage.RUSSIAN) "2 ч назад" else "2 h ago"
                assertShows("${copy.className("WARRIOR")} · Forever · $twoHoursAgo")
                assertA11y(
                    copy.continueEditingAction(warrior),
                    copy.allBuildsAction,
                    copy.openAction("Arcane levelling"),
                    copy.openAction(copy.unnamedTitle("ROGUE")),
                    copy.newBuildAction,
                )
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()
            }
        }
    }

    @Test
    fun homeErrorAndRetryInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage(failReads = true)
            onScreen(language) {
                awaitText(copy.libraryFailedTitle)
                assertShows(copy.libraryFailedTitle, copy.libraryFailedBody, copy.retry, copy.newBuild)
                assertA11y(copy.retry)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()
                storage.failReads = false
                click(copy.retry)
                awaitText(copy.homeEmptyTitle)
            }
        }
    }

    // ------------------------------------------------------------------ All Builds

    @Test
    fun allBuildsListSheetAndDialogsInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            val warrior = copy.unnamedTitle("WARRIOR")
            populate()
            onScreen(language) {
                click(copy.allBuildsLink(4))
                awaitText(copy.buildsTitle(4))
                assertShows(copy.buildsTitle(4), warrior, "Arcane levelling")
                assertA11y(
                    copy.goBack,
                    copy.newBuildAction,
                    copy.rowActions(warrior),
                    copy.manageAction(warrior),
                    copy.openAction(warrior),
                    copy.rowActions("Arcane levelling"),
                )

                openRowActions(warrior, copy)
                assertShows(warrior, copy.rename, copy.duplicate, copy.delete)
                assertA11y(copy.sheetCloseAction, copy.rename, copy.duplicate, copy.delete)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()

                click(copy.rename)
                awaitText(copy.renameTitle)
                assertShows(copy.renameTitle, copy.cancel, copy.save, "0 / 60")
                assertA11y(copy.renameField, copy.dialogCloseAction, copy.cancel)
                click(copy.cancel)

                openRowActions(warrior, copy)
                click(copy.delete)
                awaitText(copy.deleteTitle(warrior))
                assertShows(copy.deleteTitle(warrior), copy.deleteBody, copy.cancel, copy.delete)
                assertA11y(copy.dialogCloseAction, copy.cancel, copy.delete)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()
                click(copy.cancel)

                openRowActions(copy.unnamedTitle("ROGUE"), copy)
                click(copy.duplicate)
                awaitText(copy.duplicated)
                awaitText(copy.buildsTitle(5))
            }
        }
    }

    /**
     * TF-RC1-COPY-NAME: Duplicate never persists English copy. The copy of an
     * unnamed build stays unnamed (its title is the localized `<Class> build`,
     * derived at display time); the copy of a named build gets the suffix of
     * the app language.
     */
    @Test
    fun duplicateNamesFollowTheAppLanguageAndAnUnnamedCopyStaysUnnamed() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage()
            ShellLibraryFixtures.persist(storage, clock, listOf(Build("MAGE"), Build("WARRIOR", name = "Raid")))
            val mage = copy.unnamedTitle("MAGE")
            onScreen(language) {
                click(copy.allBuildsLink(2))
                awaitText(copy.buildsTitle(2))

                openRowActions(mage, copy)
                click(copy.duplicate)
                awaitText(copy.duplicated)
                awaitText(copy.buildsTitle(3))
                assertEquals(
                    2,
                    onAllNodesWithContentDescription(copy.rowActions(mage)).fetchSemanticsNodes().size,
                    "$language: the unnamed copy shows the same localized title as its source",
                )

                openRowActions("Raid", copy)
                click(copy.duplicate)
                awaitText(copy.buildsTitle(4))
                awaitText("Raid${copy.copySuffix}")
                val shown = texts()
                assertTrue(shown.none { "Draft" in it || "Черновик" in it }, "$language: no Draft title: $shown")
                if (language == AppLanguage.RUSSIAN) {
                    assertTrue(shown.none { "(copy)" in it }, "no English copy suffix on a Russian screen: $shown")
                    assertNoEnglishShellCopy()
                }
            }
            val names = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
                LocalBuildRepository(storage, clock, SequentialIds()).load(),
            ).value.map { it.name }
            assertEquals(2, names.count { it == null }, "$language: the unnamed copy is persisted unnamed")
            assertEquals(
                listOf("Raid", "Raid${copy.copySuffix}"),
                names.filterNotNull().sorted(),
                "$language: only the named copy carries a (localized) suffix",
            )
        }
    }

    /**
     * TF-RC1-DRAFT-TITLE: one unnamed build has ONE title — the localized
     * `<Class> build` — on Home, All Builds and the editor header.
     */
    @Test
    fun anUnnamedBuildHasTheSameTitleOnHomeAllBuildsAndTheEditor() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage()
            ShellLibraryFixtures.persist(storage, clock, listOf(Build("WARRIOR")))
            val warrior = copy.unnamedTitle("WARRIOR")
            onScreen(language) {
                awaitText(copy.continueEditing)
                assertShows(warrior) // Home's Continue card

                click(copy.allBuildsLink(1))
                awaitText(copy.buildsTitle(1))
                assertShows(warrior)
                assertA11y(copy.openAction(warrior))
                onAllNodes(SemanticsMatcher("open $warrior") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == copy.openAction(warrior)
                }).onFirst().performClick()

                val editorReady = EditorCopy.of(language).buildActionsOpen
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithContentDescription(editorReady).fetchSemanticsNodes().isNotEmpty()
                }
                awaitText(warrior) // the editor header
                val shown = texts()
                assertTrue(shown.none { it == "Draft" || it == "Черновик" }, "$language: no separate Draft title: $shown")
            }
            val stored = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
                LocalBuildRepository(storage, clock, SequentialIds()).load(),
            ).value.single()
            assertEquals(null, stored.name, "$language: the display title is never persisted")
        }
    }

    @Test
    fun allBuildsFailureNoticesAndEmptyStateInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage()
            ShellLibraryFixtures.persist(storage, clock, listOf(Build("PALADIN")))
            val paladin = copy.unnamedTitle("PALADIN")
            onScreen(language) {
                click(copy.allBuildsLink(1))
                awaitText(copy.buildsTitle(1))

                // a failing write: the duplicate notice and the delete error
                storage.failWrites = true
                openRowActions(paladin, copy)
                click(copy.duplicate)
                awaitText(copy.duplicateFailed)
                assertA11y(copy.retry)
                openRowActions(paladin, copy)
                click(copy.delete)
                awaitText(copy.deleteTitle(paladin))
                onAllNodesWithText(copy.delete).onFirst().performClick()
                awaitText(copy.deleteFailed)
                click(copy.cancel)

                // the real delete: the D.6 empty state
                storage.failWrites = false
                openRowActions(paladin, copy)
                click(copy.delete)
                awaitText(copy.deleteTitle(paladin))
                onAllNodesWithText(copy.delete).onFirst().performClick()
                awaitText(copy.buildsEmptyTitle)
                assertShows(copy.buildsEmptyTitle, copy.buildsEmptyBody, copy.newBuild, copy.buildsTitle(0))
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()
            }
        }
    }

    @Test
    fun renameFailureIsLocalized() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage()
            ShellLibraryFixtures.persist(storage, clock, listOf(Build("PRIEST", name = "Holy")))
            onScreen(language) {
                click(copy.allBuildsLink(1))
                openRowActions("Holy", copy)
                click(copy.rename)
                awaitText(copy.renameTitle)
                onAllNodesWithContentDescription(copy.renameField).onFirst().performTextReplacement("Holy 2")
                storage.failWrites = true
                click(copy.save)
                awaitText(copy.renameFailed)
                assertShows(copy.renameTitle, copy.cancel, copy.save)
            }
        }
    }

    // ------------------------------------------------------------------ Class Selection

    @Test
    fun classSelectionNamesTheNineClassesInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage()
            onScreen(language) {
                click(copy.newBuild)
                awaitText(copy.chooseClass)
                awaitText(copy.className("DRUID"))
                assertShows(copy.chooseClass, copy.classDataVersion, *copy.classNames.values.toTypedArray())
                assertA11y(copy.goBack, *copy.classNames.values.map { copy.createAction(it) }.toTypedArray())
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()
            }
        }
    }

    @Test
    fun classSelectionStatesAreLocalized() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            storage = FakeBuildLibraryStorage()
            onScreen(language, content = classSelection(ShellLibraryFixtures.FailingDatasets(realDatasets, true))) {
                awaitText(copy.classesFailedTitle)
                assertShows(copy.classesFailedTitle, copy.classesFailedBody, copy.retry)
                if (language == AppLanguage.RUSSIAN) assertNoEnglishShellCopy()
            }
            storage = FakeBuildLibraryStorage(failWrites = true)
            onScreen(language, content = classSelection(realDatasets)) {
                click(copy.className("MAGE"))
                awaitText(copy.draftNotSaved)
            }
            storage = FakeBuildLibraryStorage(writeDelayMillis = 2_500)
            onScreen(language, content = classSelection(realDatasets)) {
                click(copy.className("ROGUE"))
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithContentDescription(copy.creatingBuild).fetchSemanticsNodes().isNotEmpty()
                }
            }
        }
    }

    // ------------------------------------------------------------------ class names, titles, relative time

    @Test
    fun classNamesAndUnnamedTitlesComeFromTheClassToken() {
        val datasetNames = ShellCopy.ENGLISH.classNames.keys.associateWith {
            ShellLibraryFixtures.documentOf(it).classInfo.name
        }
        val cards = datasetNames.mapValues { (token, datasetName) -> unnamedCard(token, datasetName) }
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            val names = mutableMapOf<String, String?>()
            val titles = mutableMapOf<String, String>()
            onScreen(language, content = {
                for ((token, datasetName) in datasetNames) {
                    names[token] = localizedClassName(ClassToken(token), datasetName)
                    titles[token] = buildCardTitle(cards.getValue(token))
                }
                BasicText("ok")
            }) {
                awaitText("ok")
            }
            for ((token, datasetName) in datasetNames) {
                assertEquals(copy.className(token), names[token], "$language name of $token")
                assertEquals(copy.unnamedTitle(token), titles[token], "$language unnamed title of $token")
                if (language == AppLanguage.ENGLISH) {
                    assertEquals(datasetName, names[token], "the English resource IS the dataset's class name")
                }
            }
        }
    }

    @Test
    fun anUnresolvedOrUnknownClassStaysHonest() {
        val unknown = unnamedCard("MONK", datasetName = null)
        val warrior = unnamedCard("WARRIOR", "Warrior")
        val named = warrior.copy(record = warrior.record.copy(name = "Draft"))
        for (language in AppLanguage.entries) {
            var unresolved: String? = "?"
            var unknownTitle = ""
            var namedTitle = ""
            onScreen(language, content = {
                unresolved = localizedClassName(ClassToken("WARRIOR"), datasetName = null)
                unknownTitle = buildCardTitle(unknown)
                namedTitle = buildCardTitle(named)
                BasicText("ok")
            }) {
                awaitText("ok")
            }
            assertEquals(null, unresolved, "a class whose document did not load gets no invented name")
            assertEquals(if (language == AppLanguage.RUSSIAN) "Билд: Monk" else "Monk build", unknownTitle)
            assertEquals("Draft", namedTitle, "a user name is never replaced or translated")
        }
    }

    @Test
    fun russianPluralFormsFor1_2_5_11_21() {
        val russianDays = mapOf(
            1 to "1 день назад", 2 to "2 дня назад", 5 to "5 дней назад", 11 to "11 дней назад", 21 to "21 день назад",
        )
        val russianWeeks = mapOf(
            1 to "1 неделю назад", 2 to "2 недели назад", 5 to "5 недель назад",
            11 to "11 недель назад", 21 to "21 неделю назад",
        )
        val englishDays = mapOf(
            1 to "1 day ago", 2 to "2 days ago", 5 to "5 days ago", 11 to "11 days ago", 21 to "21 days ago",
        )
        val englishWeeks = mapOf(
            1 to "1 week ago", 2 to "2 weeks ago", 5 to "5 weeks ago", 11 to "11 weeks ago", 21 to "21 weeks ago",
        )
        for (language in AppLanguage.entries) {
            val days = mutableMapOf<Int, String>()
            val weeks = mutableMapOf<Int, String>()
            onScreen(language, content = {
                for (n in russianDays.keys) {
                    days[n] = pluralStringResource(Res.plurals.time_days_ago, n, n)
                    weeks[n] = pluralStringResource(Res.plurals.time_weeks_ago, n, n)
                }
                BasicText("ok")
            }) {
                awaitText("ok")
            }
            val russian = language == AppLanguage.RUSSIAN
            assertEquals(if (russian) russianDays else englishDays, days)
            assertEquals(if (russian) russianWeeks else englishWeeks, weeks)
        }
    }

    @Test
    fun relativeTimeBucketsAreLocalized() {
        val now = 1_000_000_000_000L
        val ago = listOf(0L, 5 * MINUTE, 2 * HOUR, DAY, 3 * DAY, 5 * DAY, 7 * DAY, 14 * DAY, 35 * DAY, 147 * DAY)
        val english = listOf(
            "Just now", "5 min ago", "2 h ago", "Yesterday", "3 days ago", "5 days ago",
            "Last week", "2 weeks ago", "5 weeks ago", "21 weeks ago",
        )
        val russian = listOf(
            "Только что", "5 мин назад", "2 ч назад", "Вчера", "3 дня назад", "5 дней назад",
            "На прошлой неделе", "2 недели назад", "5 недель назад", "21 неделю назад",
        )
        for (language in AppLanguage.entries) {
            val rendered = mutableListOf<String>()
            onScreen(language, content = {
                rendered.clear()
                for (delta in ago) rendered += relativeBuildTime(now, now - delta)
                BasicText("ok")
            }) {
                awaitText("ok")
            }
            assertEquals(if (language == AppLanguage.RUSSIAN) russian else english, rendered)
        }
    }

    // ------------------------------------------------------------------ data never changes

    @Test
    fun switchingTheLanguageNeverChangesStoredBuilds() {
        populate()
        val before = storage.payload
        val writesBefore = storage.writeCount
        val records = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
            LocalBuildRepository(FakeBuildLibraryStorage(before), clock, SequentialIds()).load(),
        ).value
        var language by mutableStateOf(AppLanguage.ENGLISH)
        runDesktopComposeUiTest(width = 393, height = 852) {
            setContent {
                TalentForgeTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides true) {
                        // a language change re-reads the platform locale in a new
                        // composition over the SAME storage
                        key(language) {
                            Box(Modifier.size(393.dp, 852.dp)) {
                                App(storage = storage, clock = clock, ids = SequentialIds())
                            }
                        }
                    }
                }
            }
            for (step in listOf(AppLanguage.ENGLISH, AppLanguage.RUSSIAN, AppLanguage.ENGLISH)) {
                runOnIdle {
                    uiLanguage.language = step
                    language = step
                }
                val copy = ShellCopy.of(step)
                awaitText(copy.continueSection)
                awaitText("Arcane levelling")
                click(copy.allBuildsLink(4))
                awaitText(copy.buildsTitle(4))
                awaitText(copy.unnamedTitle("WARRIOR"))
            }
        }
        assertEquals(before, storage.payload, "the stored library is byte-for-byte unchanged")
        assertEquals(writesBefore, storage.writeCount, "browsing in another language never writes")
        val after = assertIs<BuildLibraryOutcome.Loaded<List<LocalBuildRecord>>>(
            LocalBuildRepository(FakeBuildLibraryStorage(storage.payload), clock, SequentialIds()).load(),
        ).value
        assertEquals(records, after)
        assertEquals(setOf(null, "Arcane levelling"), after.map { it.name }.toSet(), "no localized title is persisted")
    }

    // ------------------------------------------------------------------ the responsive matrix

    @Test
    fun narrowAndLargeFontLayoutsKeepEveryTextWholeInBothLanguages() {
        for (language in AppLanguage.entries) {
            val copy = ShellCopy.of(language)
            val warrior = copy.unnamedTitle("WARRIOR")
            for ((width, height) in listOf(320 to 568, 393 to 852)) {
                for (fontScale in listOf(1f, 2f)) {
                    val where = "$language ${width}x$height @${(fontScale * 100).toInt()}%"
                    populate()
                    onScreen(language, width, height, fontScale) {
                        awaitText(copy.continueSection)
                        assertTextsFitAndWrapAtWords("$where Home")
                        click(copy.allBuildsLink(4))
                        awaitText(copy.buildsTitle(4))
                        assertTextsFitAndWrapAtWords("$where All Builds")
                        openRowActions(warrior, copy)
                        assertTextsFitAndWrapAtWords("$where row actions")
                        click(copy.rename)
                        awaitText(copy.renameTitle)
                        assertTextsFitAndWrapAtWords("$where rename")
                        click(copy.cancel)
                        openRowActions(warrior, copy)
                        click(copy.delete)
                        awaitText(copy.deleteTitle(warrior))
                        assertTextsFitAndWrapAtWords("$where delete")
                    }
                    onScreen(language, width, height, fontScale) {
                        click(copy.newBuild)
                        awaitText(copy.className("WARLOCK"))
                        assertTextsFitAndWrapAtWords("$where Class Selection")
                    }
                    storage = FakeBuildLibraryStorage()
                    onScreen(language, width, height, fontScale) {
                        awaitText(copy.homeEmptyTitle)
                        assertTextsFitAndWrapAtWords("$where empty Home")
                    }
                }
            }
        }
    }

    private fun unnamedCard(token: String, datasetName: String?): BuildCardModel =
        BuildCardModel(ShellLibraryFixtures.recordOf(token), classDisplayName = datasetName, spentPoints = null, pointBudget = null)

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L

        /** Sub-pixel rounding between the paragraph metrics and the text's integer box. */
        const val OVERFLOW_TOLERANCE_PX = 1.5f

        /** Where a line may legitimately end besides whitespace. */
        const val WORD_BREAK_PUNCTUATION = "-–—/·"
    }
}
