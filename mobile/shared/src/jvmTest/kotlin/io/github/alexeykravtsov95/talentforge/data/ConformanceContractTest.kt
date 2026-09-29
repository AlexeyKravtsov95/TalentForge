package io.github.alexeykravtsov95.talentforge.data

import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural conformance checks for the baseline allocation suite:
 * data/conformance/forever/1.60.1.70009/allocation.v1.json.
 *
 * M3A loads and structurally validates the contract only — executing scenarios
 * (spend/refund/validate semantics) is M3B work and deliberately absent.
 */
class ConformanceContractTest {

    @Test
    fun parsesBaselineSuiteWithFrozenShape() {
        val suite = TalentDataContract.decodeConformanceSuite(TestRepoAssets.conformance70009.readBytes())

        assertEquals(TalentDataContract.SUPPORTED_VERSION, suite.schemaVersion)
        assertEquals(GameId("forever"), suite.game)
        assertEquals("1.60.1.70009", suite.clientBuild)
        assertEquals(53, suite.scenarios.size, "baseline scenario count is frozen")
        assertEquals(177, suite.totalSteps, "baseline step count is frozen")
        assertEquals(9, suite.datasets.size)
    }

    @Test
    fun suiteReferencesOnlyKnownStepAndMutationOps() {
        val suite = TalentDataContract.decodeConformanceSuite(TestRepoAssets.conformance70009.readBytes())

        assertTrue(
            suite.scenarios.flatMap { scenario -> scenario.steps.map { it.op } }
                .all { op ->
                    op == io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceStepOp.SPEND ||
                        op == io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceStepOp.REFUND ||
                        op == io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceStepOp.VALIDATE
                },
        )
        assertTrue(
            suite.scenarios.flatMap { scenario -> scenario.mutations.map { it.op } }
                .all { it in io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceMutationOp.entries },
        )
    }

    @Test
    fun referencedDatasetIdentitiesResolveToBaselineDocuments() {
        val suite = TalentDataContract.decodeConformanceSuite(TestRepoAssets.conformance70009.readBytes())
        val baseline: Map<String, TalentDataDocument> = TestRepoAssets.classTokens.associate { token ->
            val document = TalentDataContract.decodeTalentDataDocument(
                TestRepoAssets.forever70009.resolve("$token.json").readBytes(),
            )
            token.uppercase() to document
        }

        assertEquals(baseline.keys, suite.datasets.map { it.classToken.value }.toSet())
        for (ref in suite.datasets) {
            val document = baseline.getValue(ref.classToken.value)
            assertEquals(ref.contentHash, document.contentHash, ref.classToken.value)
            assertEquals(suite.game, document.game, ref.classToken.value)
            assertEquals(suite.clientBuild, document.clientBuild, ref.classToken.value)
            assertTrue(
                TestRepoAssets.repoRoot.resolve(ref.file).isFile,
                "conformance dataset ref points at a missing file: ${ref.file}",
            )
        }
        for (scenario in suite.scenarios) {
            assertTrue(
                scenario.classToken.value in baseline,
                "scenario ${scenario.id} references unknown class ${scenario.classToken}",
            )
        }
    }
}
