package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssue
import io.github.alexeykravtsov95.talentforge.allocation.AllocationIssueCode
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.editor_reset_notice
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.section_points_spent
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguageRule
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.pluralStringResource
import org.junit.Rule
import org.w3c.dom.Element

/**
 * M5.REL.2b: the talent editor's generated copy is structured — typed
 * descriptors with their issue code, numbers and node ids — and resolves to
 * English or Russian only at the composable boundary. Covers the code →
 * descriptor mapping, the language independence of the projections, the
 * rendering of every descriptor against the literal oracle ([EditorCopy]) in
 * both languages, the Russian plural forms (1, 2, 5, 11, 21 …), the EN/RU
 * resource parity and a source scan for user-facing literals.
 */
@OptIn(ExperimentalTestApi::class)
class TalentEditorTextTest {

    @get:Rule
    val uiLanguage = AppLanguageRule(AppLanguage.ENGLISH)

    private val facts = TalentTreeNodeFacts(
        names = mapOf(1 to "Improved Rend", 2 to "Deep Wounds", 3 to " ", 4 to null),
        maxRanks = mapOf(1 to 3, 2 to 3, 3 to 1, 4 to 2),
    )

    private fun issue(code: AllocationIssueCode, nodeId: Int? = null, related: List<Int> = emptyList()) =
        AllocationIssue(code = code, message = "engine diagnostic, never shown", nodeId = nodeId, relatedNodeIds = related)

    // ------------------------------------------------------------------ structure

    @Test
    fun everyIssueCodeBecomesATypedReasonThatKeepsTheCode() {
        for (code in AllocationIssueCode.entries) {
            val reason = blockedActionReason(listOf(issue(code, nodeId = 2, related = listOf(1))), facts)
            assertEquals(code, reason.code, "the descriptor keeps the engine's code ($code)")
            val expectedType = when (code) {
                AllocationIssueCode.NOT_ALLOCATED -> InspectorText.NoRankToRefund::class
                AllocationIssueCode.MAX_RANK_REACHED -> InspectorText.MaxRankReached::class
                AllocationIssueCode.NO_POINTS_REMAINING,
                AllocationIssueCode.POINT_BUDGET_EXCEEDED,
                -> InspectorText.NoTalentPoints::class

                AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK -> InspectorText.NeededBy::class
                AllocationIssueCode.TIER_GATE_NOT_MET -> InspectorText.TierLocked::class
                AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET,
                AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET,
                -> InspectorText.RequiresFirst::class

                else -> InspectorText.ActionNotAvailable::class
            }
            assertEquals(expectedType, reason::class, "$code")
        }
        assertEquals(
            InspectorText.NeededBy(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK, TalentName(2, "Deep Wounds")),
            blockedActionReason(listOf(issue(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK, nodeId = 2)), facts),
        )
        assertEquals(
            InspectorText.RequiresFirst(
                AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET,
                TalentName(1, "Improved Rend"),
            ),
            blockedActionReason(
                listOf(issue(AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET, related = listOf(1))),
                facts,
            ),
        )
    }

    @Test
    fun aReasonWithoutANamedNodeStaysHonest() {
        assertEquals(InspectorText.ActionNotAvailable(code = null), blockedActionReason(emptyList(), facts))
        assertNull(
            assertIs<InspectorText.NeededBy>(
                blockedActionReason(listOf(issue(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK)), facts),
            ).dependent,
        )
        assertNull(
            assertIs<InspectorText.RequiresFirst>(
                blockedActionReason(listOf(issue(AllocationIssueCode.SUFFICIENT_PREREQUISITE_NOT_MET)), facts),
            ).source,
        )
    }

    @Test
    fun aNamelessTalentKeepsItsIdForTheLocalizedFallback() {
        assertEquals(TalentName(1, "Improved Rend"), facts.talentName(1))
        assertEquals(TalentName(3, null), facts.talentName(3), "a blank dataset name is no name")
        assertEquals(TalentName(4, null), facts.talentName(4))
        assertEquals(TalentName(99, null), facts.talentName(99), "an unknown node is never given an invented name")
    }

    /** The projection reads no locale: the same build yields the same content in every language. */
    @Test
    fun theInspectorContentIsTheSameUnderEveryPlatformLocale() {
        val warrior = productState("WARRIOR")
        val scenarios = compactScenarios(warrior)
        val byLanguage = AppLanguage.entries.associateWith { language ->
            uiLanguage.language = language
            scenarios.mapValues { (_, scenario) -> inspectorInputsOf(warrior, scenario.build, scenario.nodeName) }
        }
        assertEquals(byLanguage.getValue(AppLanguage.ENGLISH), byLanguage.getValue(AppLanguage.RUSSIAN))
    }

    // ------------------------------------------------------------------ rendering

    /** Every descriptor (and the fallback name) renders exactly the oracle's text, in both languages. */
    @Test
    fun everyDescriptorRendersInBothLanguages() {
        val named = TalentName(1, "Improved Rend")
        val nameless = TalentName(105950, null)
        val descriptors: List<InspectorText> = listOf(
            InspectorText.Available,
            InspectorText.InProgress,
            InspectorText.Maxed,
            InspectorText.Unavailable,
            InspectorText.NoPointsLeft(51, 51),
            InspectorText.NeededFor(InspectorText.Available, named),
            InspectorText.NeededFor(InspectorText.InProgress, nameless),
            InspectorText.RequiresPrerequisite(named, 3),
            InspectorText.RequiresPrerequisite(nameless, 1),
            InspectorText.RequiresPrerequisites(2),
            InspectorText.SpendMorePoints(1),
            InspectorText.SpendMorePoints(10),
            InspectorText.SpendMorePointsInTree,
            InspectorText.NoRankToRefund(AllocationIssueCode.NOT_ALLOCATED),
            InspectorText.MaxRankReached(AllocationIssueCode.MAX_RANK_REACHED),
            InspectorText.NoTalentPoints(AllocationIssueCode.NO_POINTS_REMAINING),
            InspectorText.NeededBy(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK, named),
            InspectorText.NeededBy(AllocationIssueCode.DEPENDENT_NODE_WOULD_BREAK, null),
            InspectorText.TierLocked(AllocationIssueCode.TIER_GATE_NOT_MET),
            InspectorText.RequiresFirst(AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET, named),
            InspectorText.RequiresFirst(AllocationIssueCode.REQUIRED_PREREQUISITE_NOT_MET, null),
            InspectorText.ActionNotAvailable(AllocationIssueCode.UNKNOWN_NODE),
            InspectorText.ActionNotAvailable(null),
        )
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            val rendered = mutableListOf<String>()
            val names = mutableListOf<String>()
            val notes = mutableListOf<String>()
            render(language) {
                rendered.clear()
                names.clear()
                notes.clear()
                descriptors.forEach { rendered += it.text() }
                names += named.text()
                names += nameless.text()
                MissingValuesNote.entries.forEach { notes += it.text() }
            }
            assertEquals(descriptors.map { copy.textOf(it) }, rendered, "descriptors in $language")
            assertEquals(listOf("Improved Rend", copy.fallbackName(105950)), names, "names in $language")
            assertEquals(listOf(copy.missingCharacter, copy.missingData), notes, "notes in $language")
        }
        // the dataset's name is never translated (M5.REL.3)
        assertEquals(
            "Требуется «Improved Rend» 3/3",
            EditorCopy.Russian.textOf(InspectorText.RequiresPrerequisite(named, 3)),
        )
    }

    @Test
    fun theCanvasStateLabelsAreLocalizedPerState() {
        for (language in AppLanguage.entries) {
            val copy = EditorCopy.of(language)
            var accessibility: TalentNodeAccessibilityCopy? = null
            render(language) { accessibility = talentNodeAccessibilityCopy() }
            val resolved = assertNotNull(accessibility)
            assertEquals(copy.selectTalent, resolved.selectAction)
            for (state in TalentNodeState.entries) {
                val node = nodePresentation(nodeId = 1, maxRank = 5, rank = 2, issues = emptyList()).copy(state = state)
                assertEquals("2/5, ${copy.nodeState(state)}", resolved.stateDescriptionOf(node), "$state in $language")
            }
        }
    }

    /** CLDR Russian: 1 / 21 → one, 2–4 / 22–24 → few, 5–20 / 11–14 / 25 … → many. */
    @Test
    fun russianPluralFormsFollowTheNumber() {
        val counts = listOf(0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111)
        val pointsSpent = mutableMapOf<Int, String>()
        val resetNotice = mutableMapOf<Int, String>()
        val spendMore = mutableMapOf<Int, String>()
        val prerequisites = mutableMapOf<Int, String>()
        render(AppLanguage.RUSSIAN) {
            for (n in counts) {
                pointsSpent[n] = pluralStringResource(Res.plurals.section_points_spent, n, n)
                resetNotice[n] = pluralStringResource(Res.plurals.editor_reset_notice, n, n)
                spendMore[n] = InspectorText.SpendMorePoints(n).text()
                prerequisites[n] = InspectorText.RequiresPrerequisites(n).text()
            }
        }
        // the five required numbers, spelled out literally
        assertEquals("потрачено 1 очко", pointsSpent[1])
        assertEquals("потрачено 2 очка", pointsSpent[2])
        assertEquals("потрачено 5 очков", pointsSpent[5])
        assertEquals("потрачено 11 очков", pointsSpent[11])
        assertEquals("потрачено 21 очко", pointsSpent[21])
        assertEquals("Таланты сброшены · снято 1 очко", resetNotice[1])
        assertEquals("Таланты сброшены · снято 2 очка", resetNotice[2])
        assertEquals("Таланты сброшены · снято 5 очков", resetNotice[5])
        assertEquals("Таланты сброшены · снято 11 очков", resetNotice[11])
        assertEquals("Таланты сброшены · снято 21 очко", resetNotice[21])
        assertEquals("Вложите ещё 1 очко в эту ветку", spendMore[1])
        assertEquals("Вложите ещё 2 очка в эту ветку", spendMore[2])
        assertEquals("Вложите ещё 5 очков в эту ветку", spendMore[5])
        assertEquals("Вложите ещё 11 очков в эту ветку", spendMore[11])
        assertEquals("Вложите ещё 21 очко в эту ветку", spendMore[21])
        assertEquals("Сначала изучите 2 требуемых таланта", prerequisites[2])
        assertEquals("Сначала изучите 5 требуемых талантов", prerequisites[5])
        assertEquals("Сначала изучите 11 требуемых талантов", prerequisites[11])
        assertEquals("Сначала изучите 21 требуемый талант", prerequisites[21])
        // and every other count through the oracle's CLDR rule
        for (n in counts) {
            assertEquals(EditorCopy.Russian.pointsSpent(n), pointsSpent[n], "points spent $n")
            assertEquals(EditorCopy.Russian.resetNotice(n), resetNotice[n], "reset notice $n")
            assertEquals(EditorCopy.Russian.spendMorePoints(n), spendMore[n], "spend more $n")
            assertEquals(EditorCopy.Russian.requiresPrerequisites(n), prerequisites[n], "prerequisites $n")
        }
    }

    @Test
    fun englishPluralFormsFollowTheNumber() {
        val counts = listOf(0, 1, 2, 5, 11, 21)
        val pointsSpent = mutableMapOf<Int, String>()
        val resetNotice = mutableMapOf<Int, String>()
        render(AppLanguage.ENGLISH) {
            for (n in counts) {
                pointsSpent[n] = pluralStringResource(Res.plurals.section_points_spent, n, n)
                resetNotice[n] = pluralStringResource(Res.plurals.editor_reset_notice, n, n)
            }
        }
        assertEquals("1 point spent", pointsSpent[1])
        assertEquals("21 points spent", pointsSpent[21])
        assertEquals("Talents reset · 1 point cleared", resetNotice[1])
        assertEquals("Talents reset · 5 points cleared", resetNotice[5])
        for (n in counts) {
            assertEquals(EditorCopy.English.pointsSpent(n), pointsSpent[n], "points spent $n")
            assertEquals(EditorCopy.English.resetNotice(n), resetNotice[n], "reset notice $n")
        }
    }

    // ------------------------------------------------------------------ resources and sources

    /** Same keys, same placeholders, complete plural forms: a Russian string never loses a number or a name. */
    @Test
    fun englishAndRussianResourcesMatchKeyForKey() {
        val english = resourceEntries("values")
        val russian = resourceEntries("values-ru")
        assertEquals(english.keys, russian.keys, "values-ru has exactly the keys of values")
        val placeholder = Regex("""%(\d+)\$[ds]""")
        for ((key, items) in english) {
            val ru = russian.getValue(key)
            val expected = items.values.flatMap { text -> placeholder.findAll(text).map { it.value } }.toSortedSet()
            for ((quantity, text) in ru) {
                val actual = placeholder.findAll(text).map { it.value }.toSortedSet()
                assertEquals(expected, actual, "placeholders of $key[$quantity] in Russian")
            }
            if (items.keys != setOf("")) {
                assertEquals(setOf("one", "other"), items.keys, "English plural forms of $key")
                assertEquals(setOf("one", "few", "many", "other"), ru.keys, "Russian plural forms of $key")
            }
        }
    }

    /**
     * No user-facing literal in the editor's production sources: every
     * `text =`, label, content/state description, pane message and notice
     * reads a resource (the brand name and glyphs excepted).
     */
    @Test
    fun theEditorSourcesHoldNoUserFacingLiterals() {
        val treeSources = repoRoot()
            .resolve("mobile/composeApp/src/commonMain/kotlin/io/github/alexeykravtsov95/talentforge/ui/tree")
        val literal = Regex(
            """\b(text|label|onClickLabel|contentDescription|stateDescription|primary|actionText|caption|title)""" +
                """\s*=\s*"([^"]*)"""",
        )
        // the brand, and Compose animation debug labels (never shown or spoken)
        val allowed = setOf("TalentForge", "nodeSelectionScale", "sectionTabIndicator")
        val template = Regex("""\$\{[^}]*\}|\$\w+""")
        val offenders = treeSources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val match = literal.find(line) ?: return@mapIndexedNotNull null
                val value = match.groupValues[2]
                // interpolated values (`${gate.spentPoints}/…`, ` · $meta`) are not words
                val words = value.replace(template, "").any { it.isLetter() } &&
                    value !in allowed &&
                    !line.trimStart().startsWith("*")
                if (words) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertTrue(offenders.isEmpty(), "user-facing literals in the editor: $offenders")
    }

    // ------------------------------------------------------------------ helpers

    private fun render(language: AppLanguage, block: @Composable () -> Unit) {
        uiLanguage.language = language
        runComposeUiTest {
            setContent {
                block()
                BasicText("ok")
            }
            waitForIdle()
        }
    }

    /** key → (plural quantity, or "" for a plain string) → text, of one resource directory. */
    private fun resourceEntries(directory: String): Map<String, Map<String, String>> {
        val file = repoRoot().resolve("mobile/composeApp/src/commonMain/composeResources/$directory/strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val entries = LinkedHashMap<String, Map<String, String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            entries[name] = when (element.tagName) {
                "string" -> mapOf("" to element.textContent)
                "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).associate { index ->
                        val item = items.item(index) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }

                else -> error("unexpected resource element ${element.tagName}")
            }
        }
        return entries
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (dir.resolve("pyproject.toml").isFile && dir.resolve("data/normalized").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }
}
