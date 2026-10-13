package io.github.alexeykravtsov95.talentforge.ui.tree

import io.github.alexeykravtsov95.talentforge.allocation.BuildState
import io.github.alexeykravtsov95.talentforge.allocation.TalentAllocationEngine
import io.github.alexeykravtsov95.talentforge.data.BundledTalentDatasetRepository
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.DatasetId
import io.github.alexeykravtsov95.talentforge.data.DatasetOutcome
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentDescriptionsLoader
import io.github.alexeykravtsov95.talentforge.ui.assets.TalentIconsLoader
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import io.github.alexeykravtsov95.talentforge.ui.theme.classAccentFor
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * M5.UX.2a test support: Ready states on any bundled dataset (with its own
 * icons and descriptions) and the compact-inspector scenarios, all found from
 * the ENGINE's own projection — no talent fact is hard-coded except the two
 * description-length examples below.
 */

/** Warrior talent with one of the shortest verified descriptions (one line). */
internal const val SHORT_DESCRIPTION_TALENT: String = "Deflection"

/** Druid talent with the longest verified description of the bundled datasets. */
internal const val LONG_DESCRIPTION_TALENT: String = "Moonkin Form"

/** A prepared build plus the talent to select in it. */
internal data class InspectorScenario(val build: BuildState, val nodeName: String)

/** The Ready state of [classToken] on bundled [clientBuild], with that build's own icons and descriptions. */
internal fun readyStateOnBundle(clientBuild: String, classToken: String): TalentTreeUiState.Ready = runBlocking {
    val assets = ComposeResourceDatasetAssetSource()
    val document = assertIs<DatasetOutcome.Loaded<TalentDataDocument>>(
        BundledTalentDatasetRepository(assets)
            .document(DatasetId(PRODUCT_BUNDLE.game, clientBuild, ClassToken(classToken))),
    ).value
    val engine = TalentAllocationEngine(document)
    TalentTreeUiState.Ready(
        classDisplayName = document.classInfo.name,
        gameDisplayName = "Forever",
        classToken = document.classInfo.token,
        model = assertIs<TalentTreeUiBuildResult.Ready>(buildTalentTreeUiModel(document)).model,
        engine = engine,
        buildState = engine.emptyState(),
        buildTitle = "${document.classInfo.name} Build",
        classAccent = classAccentFor(document.classInfo.token).color,
        icons = TalentIconsLoader(assets).load(document, document.trees.single()).icons,
        descriptions = TalentDescriptionsLoader(assets).load(document, document.trees.single()).descriptions,
    )
}

/** The Ready state of [classToken] on the product dataset. */
internal fun productState(classToken: String): TalentTreeUiState.Ready =
    readyStateOnBundle(PRODUCT_BUNDLE.clientBuild, classToken)

/** The talent [name] of [state], after spending up to [spend] ranks into it through the ENGINE. */
internal fun scenarioOf(state: TalentTreeUiState.Ready, name: String, spend: Int = 0): InspectorScenario {
    val node = state.engine.tree.nodes.first { it.name == name }
    var session = TalentBuildEditingState.start(state.engine)
    repeat(spend) {
        val result = session.spend(state.engine, node.id)
        if (result.changed) session = result.editing
    }
    return InspectorScenario(session.current, name)
}

/**
 * The seven compact-inspector states of the first section of [state], each
 * located through the engine (`buildTalentTreePresentation` +
 * `buildTalentNodeActions`) over builds spent greedily through the engine:
 * available, prerequisite-locked (one unmet source, so Show is offered),
 * tier-locked, partially ranked, max rank, no points left and refund blocked
 * by a dependent. A state the tree cannot produce is simply absent.
 */
internal fun compactScenarios(state: TalentTreeUiState.Ready): Map<String, InspectorScenario> {
    val engine = state.engine
    val sections = buildTalentTreeSections(engine.tree, state.model) as TalentTreeSectionsResult.Sectioned
    val order = sections.sections.first().readingOrderNodeIds
    val names = engine.tree.nodes.associate { it.id to it.name.orEmpty() }

    fun find(build: BuildState, test: (TalentNodePresentation, TalentNodeActions) -> Boolean): Int? {
        val presentation = buildTalentTreePresentation(engine, build)
        return order.firstOrNull { id ->
            val node = presentation.nodes[id] ?: return@firstOrNull false
            test(node, buildTalentNodeActions(engine, build, node))
        }
    }

    /** Every intermediate build of a greedy fill, phase after phase (each to exhaustion), in spend order. */
    fun greedy(vararg phases: List<Int>): List<BuildState> {
        var session = TalentBuildEditingState.start(engine)
        val builds = mutableListOf(session.current)
        for (nodeIds in phases) {
            var progressed = true
            while (progressed) {
                progressed = false
                for (id in nodeIds) {
                    val result = session.spend(engine, id)
                    if (result.changed) {
                        session = result.editing
                        builds += session.current
                        progressed = true
                    }
                }
            }
        }
        return builds
    }

    val tree1 = greedy(order)
    val empty = tree1.first()
    val out = linkedMapOf<String, InspectorScenario>()
    fun put(label: String, builds: List<BuildState>, test: (TalentNodePresentation, TalentNodeActions) -> Boolean) {
        for (build in builds) {
            val id = find(build, test) ?: continue
            out[label] = InspectorScenario(build, names.getValue(id))
            return
        }
    }
    put("available", listOf(empty)) { node, _ -> node.state == TalentNodeState.AVAILABLE }
    put("prerequisite", listOf(empty)) { node, _ ->
        node.state == TalentNodeState.PREREQUISITE_LOCKED && node.unmetPrerequisiteNodeIds.size == 1
    }
    put("tier", listOf(empty)) { node, _ -> node.state == TalentNodeState.TIER_LOCKED }
    put("partial", tree1) { node, actions -> node.state == TalentNodeState.PARTIAL && actions.canRefund }
    put("maxed", tree1) { node, actions -> node.state == TalentNodeState.MAXED && actions.canRefund }
    put("refund-blocked", tree1) { _, actions -> actions.rank > 0 && !actions.canRefund }
    // no points: the whole budget spent in the OTHER sections first (then, if any is
    // left, in tree 1), so it runs out while tree 1 still has open talents at rank 0
    val othersFirst = greedy(sections.sections.drop(1).flatMap { it.readingOrderNodeIds }, order).last()
    put("no-points", listOf(othersFirst)) { node, _ -> node.state == TalentNodeState.NO_POINTS }
    return out
}

/**
 * The talent [name] of [state] made spendable: ranks are spent greedily
 * through the ENGINE in its section's reading order (never into [name]
 * itself) until `engine.canSpend` allows it — e.g. the long-description Moonkin
 * Form with its real `+ Rank` / `Max` actions.
 */
internal fun unlockedScenario(state: TalentTreeUiState.Ready, name: String): InspectorScenario {
    val engine = state.engine
    val target = engine.tree.nodes.first { it.name == name }.id
    val sections = buildTalentTreeSections(engine.tree, state.model) as TalentTreeSectionsResult.Sectioned
    val order = sections.sections.first { target in it.readingOrderNodeIds }.readingOrderNodeIds - target
    var session = TalentBuildEditingState.start(engine)
    var progressed = true
    while (!engine.canSpend(session.current, target).allowed && progressed) {
        progressed = false
        for (id in order) {
            if (engine.canSpend(session.current, target).allowed) break
            val result = session.spend(engine, id)
            if (result.changed) {
                session = result.editing
                progressed = true
            }
        }
    }
    check(engine.canSpend(session.current, target).allowed) { "$name could not be unlocked" }
    return InspectorScenario(session.current, name)
}

/** The inspector inputs exactly as the screen derives them (content + engine-backed actions). */
internal fun inspectorInputsOf(
    state: TalentTreeUiState.Ready,
    build: BuildState,
    nodeName: String,
): Pair<TalentInspectorContent, TalentNodeActions> {
    val engine = state.engine
    val nodeId = engine.tree.nodes.first { it.name == nodeName }.id
    val tree = buildTalentTreePresentation(engine, build)
    val node = tree.nodes.getValue(nodeId)
    val facts = talentNodeFacts(engine)
    val actions = buildTalentNodeActions(engine, build, node)
    val content = buildTalentInspectorContent(
        presentation = node,
        tree = tree,
        facts = facts,
        blockedReason = actions.disabledActionExplanation(facts, tree.spentPoints, tree.pointBudget),
        descriptions = state.descriptions,
    )
    return content to actions
}
