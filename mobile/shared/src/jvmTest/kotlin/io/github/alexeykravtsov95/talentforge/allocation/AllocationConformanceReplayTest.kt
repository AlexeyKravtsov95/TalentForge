package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3B acceptance test: executes the committed baseline conformance suite
 * (data/conformance/forever/1.60.1.70009/allocation.v1.json — 53 scenarios /
 * 177 steps) against the real Kotlin engine and compares every step exactly
 * (ok, reasonCodes, structural issues, spentPoints, remainingPoints, nodeRank).
 *
 * Passing proves parity with the Python M2 reference engine. It does **not**
 * make the provisional EDGE-ACTIVE / EDGE-SUF rules client-proven
 * (docs/talent-allocation-semantics.md §13).
 */
class AllocationConformanceReplayTest {

    @Test
    fun replaysBaselineSuiteExactly() {
        val suite = TalentDataContract.decodeConformanceSuite(TestRepoAssets.conformance70009.readBytes())
        assertEquals(53, suite.scenarios.size, "baseline scenario count is frozen")
        assertEquals(177, suite.totalSteps, "baseline step count is frozen")

        val documents: Map<String, TalentDataDocument> = TestRepoAssets.classTokens.associate { token ->
            val document = TalentDataContract.decodeTalentDataDocument(
                TestRepoAssets.forever70009.resolve("$token.json").readBytes(),
            )
            token.uppercase() to document
        }
        for (ref in suite.datasets) {
            val document = documents.getValue(ref.classToken.value)
            assertEquals(
                ref.contentHash,
                document.contentHash,
                "${ref.file}: document content hash does not match the suite pin",
            )
        }

        val failures = mutableListOf<String>()
        var scenariosExecuted = 0
        var stepsExecuted = 0
        for (scenario in suite.scenarios) {
            scenariosExecuted++
            for (outcome in AllocationConformance.runScenario(scenario, documents)) {
                stepsExecuted++
                if (!outcome.matches) {
                    failures += describeMismatch(scenario.id, outcome)
                }
            }
        }
        assertEquals(53, scenariosExecuted, "every scenario must execute")
        assertEquals(177, stepsExecuted, "every step must execute")
        assertTrue(
            failures.isEmpty(),
            "conformance mismatches (${failures.size}):\n" + failures.joinToString("\n\n"),
        )
    }

    /**
     * Scenario mutations must never leak into the shared parsed documents: a
     * scenario with mutations followed by a plain scenario on the same class
     * sees the unmutated dataset.
     */
    @Test
    fun mutationsAreIsolatedToTheirScenario() {
        val suite = TalentDataContract.decodeConformanceSuite(TestRepoAssets.conformance70009.readBytes())
        val documents: Map<String, TalentDataDocument> = TestRepoAssets.classTokens.associate { token ->
            val document = TalentDataContract.decodeTalentDataDocument(
                TestRepoAssets.forever70009.resolve("$token.json").readBytes(),
            )
            token.uppercase() to document
        }
        val original = documents.getValue("WARRIOR")

        val mutatedScenario = suite.scenarios.single { it.id == "synthetic.mixed_required_and_sufficient_fail_closed" }
        AllocationConformance.runScenario(mutatedScenario, documents)

        val plainScenario = suite.scenarios.single { it.id == "warrior.basic_rank_purchase" }
        val outcomes = AllocationConformance.runScenario(plainScenario, documents)
        assertTrue(outcomes.all { it.matches }, "unmutated replay after a mutated scenario drifted")
        assertEquals(original, documents.getValue("WARRIOR"), "the shared document instance must be unchanged")
    }

    private fun describeMismatch(scenarioId: String, outcome: AllocationConformance.StepOutcome): String {
        val expected = outcome.expected
        val actual = outcome.actual
        return buildString {
            appendLine("scenario=$scenarioId step=${outcome.stepIndex} op=${outcome.op}")
            appendLine("  expected ok=${expected.ok} codes=${expected.reasonCodes} spent=${expected.spentPoints} remaining=${expected.remainingPoints} rank=${expected.nodeRank}")
            appendLine("  actual   ok=${actual.ok} codes=${actual.reasonCodes} spent=${actual.spentPoints} remaining=${actual.remainingPoints} rank=${actual.nodeRank}")
            for (index in 0 until maxOf(expected.issues.size, actual.issues.size)) {
                val e = expected.issues.getOrNull(index)
                val a = actual.issues.getOrNull(index)
                if (e != a) {
                    appendLine("  first differing issue at index $index:")
                    appendLine("    expected: $e")
                    appendLine("    actual:   $a")
                    break
                }
            }
        }
    }
}
