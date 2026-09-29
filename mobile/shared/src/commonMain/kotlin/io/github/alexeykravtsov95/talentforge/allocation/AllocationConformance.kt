package io.github.alexeykravtsov95.talentforge.allocation

import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceMutation
import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceMutationOp
import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceScenario
import io.github.alexeykravtsov95.talentforge.data.conformance.ConformanceStepOp
import io.github.alexeykravtsov95.talentforge.data.conformance.Expectation
import io.github.alexeykravtsov95.talentforge.data.conformance.ExpectedIssue
import io.github.alexeykravtsov95.talentforge.data.conformance.MutationValue
import io.github.alexeykravtsov95.talentforge.data.talentdata.Prerequisite
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNode
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentNodeType

/**
 * Executor for `allocation-conformance.v1` suites — the Kotlin counterpart of
 * the Python reference `talent_data.allocation.conformance` runner.
 *
 * Every step executes the *actual* engine operation and compares the observed
 * outcome against the scenario's [Expectation]; nothing is copied from the
 * expected values. `ok`, `reasonCodes`, the ordered structural `issues`,
 * `spentPoints`, `remainingPoints` and (spend/refund) `nodeRank` must all match.
 * Issue messages are never compared.
 *
 * Scenario mutations are applied to an isolated copy of the referenced dataset:
 * the parsed documents are immutable value types, so the canonical instance
 * passed in [documents] is never modified.
 */
object AllocationConformance {

    /**
     * Returns `document` with [mutations] applied to its (single) tree.
     *
     * The Python reference re-computes the canonical `contentHash` after
     * mutating so its full-document validator still passes. That hash algorithm
     * is not part of the consumer contract (docs/mobile-architecture.md), so the
     * mutated in-memory copy keeps the published hash of the unmutated document
     * and the Kotlin engine never verifies content hashes.
     */
    fun applyMutations(
        document: TalentDataDocument,
        mutations: List<ConformanceMutation>,
    ): TalentDataDocument {
        if (mutations.isEmpty()) return document
        require(document.trees.size == 1) { "mutations are defined for single-tree documents" }
        val tree = document.trees[0]
        val nodes = tree.nodes.associateByTo(mutableMapOf()) { it.id }
        for (mutation in mutations) {
            val node = nodes[mutation.nodeId]
                ?: throw IllegalArgumentException("mutation targets unknown node ${mutation.nodeId}")
            nodes[mutation.nodeId] = mutateNode(node, mutation)
        }
        val mutatedTree = tree.copy(nodes = tree.nodes.map { nodes.getValue(it.id) })
        return document.copy(trees = listOf(mutatedTree))
    }

    /** Engine for a scenario: the (possibly mutated) class document and its point limit. */
    fun engineFor(
        scenario: ConformanceScenario,
        documents: Map<String, TalentDataDocument>,
    ): TalentAllocationEngine {
        val document = documents.getValue(scenario.classToken.value)
        return TalentAllocationEngine(
            document = applyMutations(document, scenario.mutations),
            pointLimit = scenario.pointLimit,
        )
    }

    /** Replays a scenario against the engine and compares every step. */
    fun runScenario(
        scenario: ConformanceScenario,
        documents: Map<String, TalentDataDocument>,
    ): List<StepOutcome> {
        val engine = engineFor(scenario, documents)
        var state = BuildState.of(engine.tree.id, scenario.initial)
        val outcomes = mutableListOf<StepOutcome>()
        scenario.steps.forEachIndexed { index, step ->
            val (actual, nextState) = runStep(engine, state, step.op, step.nodeId, step.entryId)
            state = nextState
            outcomes += StepOutcome(index, step.op, actual, step.expect)
        }
        return outcomes
    }

    /** Executes one step with the engine; returns its observed outcome and the next state. */
    fun runStep(
        engine: TalentAllocationEngine,
        state: BuildState,
        op: ConformanceStepOp,
        nodeId: Int?,
        entryId: Int?,
    ): Pair<Expectation, BuildState> = when (op) {
        ConformanceStepOp.VALIDATE -> {
            val validation = engine.validate(state)
            Expectation(
                ok = validation.valid,
                reasonCodes = validation.reasonCodes,
                issues = validation.issues.map(::expectedIssue),
                spentPoints = validation.spentPoints,
                remainingPoints = validation.remainingPoints,
            ) to state
        }

        ConformanceStepOp.SPEND -> {
            val target = requireNotNull(nodeId) { "spend step needs a nodeId" }
            val result = engine.spend(state, target, entryId)
            expectationOfStep(engine, result, target) to result.state
        }

        ConformanceStepOp.REFUND -> {
            val target = requireNotNull(nodeId) { "refund step needs a nodeId" }
            val result = engine.refund(state, target)
            expectationOfStep(engine, result, target) to result.state
        }
    }

    /** Observed outcome of one executed step, with the scenario's expectation. */
    data class StepOutcome(
        val stepIndex: Int,
        val op: ConformanceStepOp,
        val actual: Expectation,
        val expected: Expectation,
    ) {
        val matches: Boolean get() = actual == expected
    }

    private fun expectationOfStep(
        engine: TalentAllocationEngine,
        result: AllocationResult,
        nodeId: Int,
    ): Expectation = Expectation(
        ok = result.allowed,
        reasonCodes = result.decision.reasonCodes,
        issues = result.decision.issues.map(::expectedIssue),
        spentPoints = engine.spentPoints(result.state),
        remainingPoints = engine.remainingPoints(result.state),
        nodeRank = result.state.rankOf(nodeId),
    )

    /** Structural, message-free form of an issue — the part the contract pins. */
    private fun expectedIssue(issue: AllocationIssue): ExpectedIssue = ExpectedIssue(
        code = issue.code.name,
        nodeId = issue.nodeId,
        entryId = issue.entryId,
        relatedNodeIds = issue.relatedNodeIds,
        conditionId = issue.conditionId,
        required = issue.required,
        actual = issue.actual,
        cause = issue.cause?.name,
    )

    private fun mutateNode(
        node: TalentNode,
        mutation: ConformanceMutation,
    ): TalentNode = when (mutation.op) {
        ConformanceMutationOp.ADD_PREREQUISITE -> {
            val prerequisiteNodeId = requireNotNull(mutation.prerequisiteNodeId) {
                "addPrerequisite needs prerequisiteNodeId"
            }
            val kind = requireNotNull(mutation.kind) { "addPrerequisite needs kind" }
            node.copy(
                prerequisites = (node.prerequisites + Prerequisite(prerequisiteNodeId, kind))
                    .sortedBy { it.nodeId },
            )
        }

        ConformanceMutationOp.SET_NODE_TYPE -> node.copy(
            type = when (val value = mutationText(mutation)) {
                "single" -> TalentNodeType.Single
                "tiered" -> TalentNodeType.Tiered
                "selection" -> TalentNodeType.Selection
                else -> TalentNodeType.Unknown(value)
            },
        )

        ConformanceMutationOp.SET_NODE_FLAGS -> node.copy(
            flags = requireMutationInt(mutation),
        )

        ConformanceMutationOp.SET_ENTRY_TYPE -> node.copy(
            entries = node.entries.map { it.copy(entryType = requireMutationInt(mutation)) },
        )

        ConformanceMutationOp.ADD_UNRESOLVED_CONDITION -> node.copy(
            unresolvedConditionIds = (node.unresolvedConditionIds + requireMutationInt(mutation))
                .distinct()
                .sorted(),
        )
    }

    private fun requireMutationInt(mutation: ConformanceMutation): Int =
        when (val value = mutation.value) {
            is MutationValue.IntegralValue -> value.value.toInt()
            else -> throw IllegalArgumentException(
                "${mutation.op.name} needs an integer value, got $value",
            )
        }

    /**
     * The reference stores whatever string the value carries (`str(value)`, so
     * an integer becomes its decimal form); only `setNodeType` consumes it.
     */
    private fun mutationText(mutation: ConformanceMutation): String =
        when (val value = mutation.value) {
            is MutationValue.TextValue -> value.value
            is MutationValue.IntegralValue -> value.value.toString()
            null -> throw IllegalArgumentException(
                "${mutation.op.name} needs a value, got none",
            )
        }
}
