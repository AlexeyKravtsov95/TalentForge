"""Authored allocation conformance scenarios for the Forever baseline build.

This is *test data*: real node names/ids of the baseline datasets are allowed here (and
only here and in tests), production code never knows them.

Every step carries an **authored** expectation (``ok`` + reason codes) derived from the
documented rules, not from the engine. The generator replays each scenario with the
reference engine and refuses to write the suite if the engine disagrees with the author;
only then does it record the engine's full structural output (issues, points, ranks) as the
vector other implementations must reproduce.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from talent_data.allocation.conformance import (
    ConformanceSuite,
    DatasetRef,
    Mutation,
    Scenario,
    Step,
    apply_mutations,
    run_step,
)
from talent_data.allocation.engine import TalentAllocationEngine
from talent_data.allocation.models import BuildState, NodeAllocation
from talent_data.games import get_game
from talent_data.models.talents import TalentDataDocument, TalentNode
from talent_data.validation.validators import validate_document
from talent_data.versions import ClientVersion

ROOT = Path(__file__).resolve().parents[2]
GAME = "forever"
BASELINE = ClientVersion.parse("1.60.1.70009")

NodeRef = str | int  # node name (unique in the class tree) or node id


def dataset_file(class_token: str, build: ClientVersion = BASELINE) -> str:
    return f"data/normalized/{GAME}/{build}/{class_token.lower()}.json"


def load_documents(build: ClientVersion = BASELINE) -> dict[str, TalentDataDocument]:
    tokens = get_game(GAME).dataset_class_tokens
    return {
        token: TalentDataDocument.model_validate_json(
            (ROOT / dataset_file(token, build)).read_text(encoding="utf-8")
        )
        for token in tokens
    }


# --------------------------------------------------------------------------- authoring DSL


@dataclass(frozen=True)
class StepSpec:
    op: str
    node: NodeRef | None
    ok: bool
    codes: tuple[str, ...]
    entry_of: NodeRef | None = None  # spend with the entry of *another* node (wrong entry)
    raw_entry: int | None = None


def spend(node: NodeRef, *, ok: bool = True, codes: Sequence[str] = ()) -> StepSpec:
    return StepSpec("spend", node, ok, tuple(codes))


def spend_with_entry_of(
    node: NodeRef, other: NodeRef, *, ok: bool, codes: Sequence[str]
) -> StepSpec:
    return StepSpec("spend", node, ok, tuple(codes), entry_of=other)


def refund(node: NodeRef, *, ok: bool = True, codes: Sequence[str] = ()) -> StepSpec:
    return StepSpec("refund", node, ok, tuple(codes))


def validate(*, ok: bool = True, codes: Sequence[str] = ()) -> StepSpec:
    return StepSpec("validate", None, ok, tuple(codes))


@dataclass(frozen=True)
class MutationSpec:
    op: str
    node: NodeRef
    prerequisite: NodeRef | None = None
    kind: str | None = None
    value: int | str | None = None


@dataclass(frozen=True)
class Fill:
    """Deterministic setup helper: spend ``points`` in section ``section`` by purchasing,
    rank by rank, the first purchasable node in (row, column, id) order - ``prefer`` nodes
    first, never ``exclude`` nodes. Every purchase goes through the engine rules, so the
    resulting setup is valid by construction (and each scenario re-validates it)."""

    section: int
    points: int
    prefer: tuple[NodeRef, ...] = ()
    exclude: tuple[NodeRef, ...] = ()


@dataclass(frozen=True)
class ScenarioSpec:
    id: str
    cls: str
    description: str
    rules: tuple[str, ...]
    steps: tuple[StepSpec, ...]
    fills: tuple[Fill, ...] = ()  # applied first
    initial: tuple[tuple[NodeRef, int], ...] = ()  # then these exact ranks
    # Verbatim (possibly corrupt) allocations: (node, entry, rank). The entry is a node
    # reference (-> that node's entry id) or a raw TraitNodeEntry id.
    raw_initial: tuple[tuple[NodeRef, NodeRef, int], ...] = ()
    mutations: tuple[MutationSpec, ...] = ()
    point_limit: int | None = None
    golden: bool = False


@dataclass
class Author:
    documents: dict[str, TalentDataDocument]
    _cache: dict[str, dict[str, TalentNode]] = field(default_factory=dict)

    def node(self, cls: str, ref: NodeRef) -> TalentNode:
        tree = self.documents[cls].trees[0]
        if isinstance(ref, int):
            matches = [n for n in tree.nodes if n.id == ref]
        else:
            matches = [n for n in tree.nodes if n.name == ref]
        if len(matches) != 1:
            raise ValueError(f"{cls}: node {ref!r} matches {len(matches)} nodes")
        return matches[0]

    def node_id(self, cls: str, ref: NodeRef) -> int:
        return self.node(cls, ref).id

    # -------------------------------------------------------------- setup
    def fill(
        self, engine: TalentAllocationEngine, cls: str, state: BuildState, spec: Fill
    ) -> BuildState:
        excluded = {self.node_id(cls, r) for r in spec.exclude}
        preferred = [self.node(cls, r) for r in spec.prefer]
        section_nodes = sorted(
            (n for n in engine.tree.nodes if n.section_index == spec.section),
            key=lambda n: (n.position.row, n.position.column, n.id),
        )
        order = preferred + [n for n in section_nodes if n not in preferred]
        section_ids = {n.id for n in section_nodes}

        def section_points(s: BuildState) -> int:
            return sum(a.rank for a in s.allocations if a.node_id in section_ids)

        while section_points(state) < spec.points:
            for node in order:
                if node.id in excluded:
                    continue
                result = engine.spend(state, node.id)
                if result.allowed:
                    state = result.state
                    break
            else:
                raise ValueError(f"{cls}: cannot fill section {spec.section} to {spec.points}")
        return state

    # -------------------------------------------------------------- build
    def build(self, spec: ScenarioSpec) -> Scenario:
        mutations = [self._mutation(spec.cls, m) for m in spec.mutations]
        doc = apply_mutations(self.documents[spec.cls], mutations)
        engine = TalentAllocationEngine(doc, point_limit=spec.point_limit)
        state = BuildState.empty(engine.tree.id)
        for fill in spec.fills:
            state = self.fill(engine, spec.cls, state, fill)
        # Explicit ranks are applied after the fills (they may deliberately break validity).
        for ref, rank in spec.initial:
            node = self.node(spec.cls, ref)
            state = state.with_allocation(node.id, node.entries[0].id, rank)
        initial = list(state.allocations) + [
            NodeAllocation(
                node_id=self._ref_id(spec.cls, n),
                entry_id=e if isinstance(e, int) else self.node(spec.cls, e).entries[0].id,
                rank=r,
            )
            for n, e, r in spec.raw_initial
        ]
        state = BuildState(tree_id=engine.tree.id, allocations=tuple(initial))

        steps = []
        for index, step in enumerate(spec.steps):
            node_id = None if step.node is None else self._ref_id(spec.cls, step.node)
            entry_id = None
            if step.entry_of is not None:
                entry_id = self.node(spec.cls, step.entry_of).entries[0].id
            actual, state = run_step(engine, state, step.op, node_id, entry_id)
            authored = (step.ok, tuple(sorted(set(step.codes))))
            observed = (actual.ok, tuple(actual.reason_codes))
            if authored != observed:
                raise AssertionError(
                    f"{spec.id} step {index} ({step.op} {step.node!r}): authored "
                    f"{authored} but the engine answers {observed}; issues: "
                    f"{[i.model_dump(by_alias=True, exclude_none=True) for i in actual.issues]}"
                )
            steps.append(Step(op=step.op, node_id=node_id, entry_id=entry_id, expect=actual))
        return Scenario.model_validate(
            {
                "id": spec.id,
                "class": spec.cls,
                "description": spec.description,
                "rules": list(spec.rules),
                "synthetic": bool(spec.mutations),
                "pointLimit": spec.point_limit,
                "mutations": mutations,
                "initial": initial,
                "steps": steps,
            }
        )

    def _ref_id(self, cls: str, ref: NodeRef) -> int:
        # Unknown ids are legitimate step targets (UNKNOWN_NODE scenarios).
        if isinstance(ref, int) and not any(
            n.id == ref for n in self.documents[cls].trees[0].nodes
        ):
            return ref
        return self.node_id(cls, ref)

    def _mutation(self, cls: str, m: MutationSpec) -> Mutation:
        return Mutation.model_validate(
            {
                "op": m.op,
                "nodeId": self.node_id(cls, m.node),
                "prerequisiteNodeId": (
                    None if m.prerequisite is None else self.node_id(cls, m.prerequisite)
                ),
                "kind": m.kind,
                "value": m.value,
            }
        )


# --------------------------------------------------------------------------- golden builds


def golden_build(engine: TalentAllocationEngine, excluded: Iterable[int] = ()) -> BuildState:
    """Algorithmically constructed full build: repeatedly purchase one rank of the first
    purchasable node in (section, row, column, id) order until the budget is spent. Every
    transition passes the engine rules, so the result is valid by construction. Nodes the
    dataset validator flags OFF_GRID_NODE (mis-placed duplicates / nudged nodes) are never
    chosen, to keep golden builds free of data anomalies."""
    skip = set(excluded)
    order = sorted(
        (n for n in engine.tree.nodes if n.id not in skip),
        key=lambda n: (
            n.section_index if n.section_index is not None else 99,
            n.position.row,
            n.position.column,
            n.id,
        ),
    )
    state = engine.empty_state()
    while engine.remaining_points(state) > 0:
        for node in order:
            result = engine.spend(state, node.id)
            if result.allowed:
                state = result.state
                break
        else:
            break
    return state


def off_grid_nodes(doc: TalentDataDocument) -> set[int]:
    return {
        i.node_id
        for i in validate_document(doc).warnings
        if i.code == "OFF_GRID_NODE" and i.node_id is not None
    }


def class_scenarios(author: Author) -> list[Scenario]:
    """Smoke + golden scenarios for every class of the dataset (data-driven)."""
    out = []
    for token, doc in author.documents.items():
        engine = TalentAllocationEngine(doc)
        tree = engine.tree
        first_of_section = [
            min(
                (n for n in tree.nodes if n.section_index == s.index),
                key=lambda n: (n.position.row, n.position.column, n.id),
            )
            for s in tree.sections
        ]
        smoke = ScenarioSpec(
            id=f"{token.lower()}.smoke",
            cls=token,
            description=f"{token}: the engine builds on the real dataset; the first row-0 "
            "talent of every tab is purchasable from an empty tree",
            rules=("BUDGET-1", "RANK-1", "COST-1", "GATE-1"),
            steps=(
                validate(),
                *(spend(n.id) for n in first_of_section),
                validate(),
            ),
        )
        out.append(author.build(smoke))

        golden = golden_build(engine, off_grid_nodes(doc))
        spent = engine.spent_points(golden)
        if spent != engine.point_budget:
            raise AssertionError(f"{token}: golden build reached only {spent} points")
        leaf = next(
            a.node_id
            for a in reversed(golden.allocations)
            if engine.can_refund(golden, a.node_id).allowed
        )
        golden_spec = ScenarioSpec(
            id=f"{token.lower()}.golden_51_point_build",
            cls=token,
            description=f"{token}: an algorithmically constructed 51-point build (every "
            "purchase passed the engine rules) validates; no further point can be spent; "
            "a refundable rank can be removed",
            rules=("VALID-1", "BUDGET-1", "REFUND-1"),
            raw_initial=tuple((a.node_id, a.entry_id, a.rank) for a in golden.allocations),
            steps=(
                validate(),
                spend(first_of_section[0].id, ok=False, codes=_exhausted(engine, golden)),
                refund(leaf),
                validate(),
            ),
            golden=True,
        )
        out.append(author.build(golden_spec))
    return out


def _exhausted(engine: TalentAllocationEngine, state: BuildState) -> tuple[str, ...]:
    # The first row-0 talent may also be maxed in the golden build; both codes are
    # authored from the rules (budget spent; rank at max) - see the golden scenario.
    node = min(
        (n for n in engine.tree.nodes if n.section_index == 0),
        key=lambda n: (n.position.row, n.position.column, n.id),
    )
    codes = ["NO_POINTS_REMAINING"]
    if state.rank_of(node.id) >= node.max_rank:
        codes.append("MAX_RANK_REACHED")
    return tuple(codes)


# --------------------------------------------------------------------------- rule scenarios

W = "WARRIOR"
IHS = "Improved Heroic Strike"  # row 0, 3 ranks
DEFLECTION = "Deflection"  # row 0, 5 ranks
IMP_REND = "Improved Rend"  # row 0, 3 ranks -> Deep Wounds (sufficient)
IMP_CHARGE = "Improved Charge"  # row 1, gate: 5 points in Arms row 0
ITM = "Improved Tactical Mastery"  # row 1, 5 ranks
IMP_OVERPOWER = "Improved Overpower"  # row 1, 2 ranks, no prerequisite
DEEP_WOUNDS = "Deep Wounds"  # row 2, gate: 10 points in Arms rows 0-1
SWEEPING = "Sweeping Strikes"  # row 4 -> Mortal Strike
MORTAL_STRIKE = "Mortal Strike"  # row 6 capstone, gate: 30 points in the whole Arms tab
BOOMING_VOICE = "Booming Voice"  # Fury row 0

RULE_SCENARIOS: tuple[ScenarioSpec, ...] = (
    ScenarioSpec(
        id="warrior.basic_rank_purchase",
        cls=W,
        description="A row-0 talent is purchasable from an empty tree; one rank costs one point",
        rules=("RANK-1", "COST-1", "BUDGET-1"),
        steps=(spend(IHS), spend(IHS), validate()),
    ),
    ScenarioSpec(
        id="warrior.max_rank_rejection",
        cls=W,
        description="A rank beyond the entry's max rank is rejected; the state is unchanged",
        rules=("RANK-1",),
        initial=((IHS, 2),),
        steps=(spend(IHS), spend(IHS, ok=False, codes=["MAX_RANK_REACHED"]), validate()),
    ),
    ScenarioSpec(
        id="warrior.point_budget_exhaustion",
        cls=W,
        description="With a configured usable budget of 3 points the 4th point is rejected",
        rules=("BUDGET-1", "COST-1"),
        point_limit=3,
        steps=(
            spend(IHS),
            spend(IHS),
            spend(IHS),
            spend(DEFLECTION, ok=False, codes=["NO_POINTS_REMAINING"]),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="warrior.over_budget_import",
        cls=W,
        description="An imported build spending more than the usable budget is invalid",
        rules=("BUDGET-1", "VALID-1"),
        point_limit=4,
        initial=((IHS, 3), (DEFLECTION, 2)),
        steps=(validate(ok=False, codes=["POINT_BUDGET_EXCEEDED"]),),
    ),
    ScenarioSpec(
        id="warrior.tier_gate_locked",
        cls=W,
        description="Row 1 needs 5 points spent in Arms row 0; 0 and 4 points are not enough",
        rules=("GATE-1", "GATE-2"),
        steps=(
            spend(IMP_CHARGE, ok=False, codes=["TIER_GATE_NOT_MET"]),
            spend(IHS),
            spend(IHS),
            spend(IHS),
            spend(DEFLECTION),
            spend(IMP_CHARGE, ok=False, codes=["TIER_GATE_NOT_MET"]),
        ),
    ),
    ScenarioSpec(
        id="warrior.tier_gate_unlocked",
        cls=W,
        description="The 5th point in Arms row 0 unlocks row 1",
        rules=("GATE-1", "GATE-2"),
        initial=((IHS, 3), (DEFLECTION, 1)),
        steps=(spend(DEFLECTION), spend(IMP_CHARGE), validate()),
    ),
    ScenarioSpec(
        id="warrior.tier_gate_counts_only_its_group",
        cls=W,
        description="Points in another tab do not count toward an Arms gate",
        rules=("GATE-1",),
        initial=((BOOMING_VOICE, 5),),
        steps=(spend(IMP_CHARGE, ok=False, codes=["TIER_GATE_NOT_MET"]),),
    ),
    ScenarioSpec(
        id="warrior.sufficient_prerequisite_requires_max_rank",
        cls=W,
        description="Deep Wounds needs Improved Rend fully ranked: 2/3 is not enough, 3/3 is",
        rules=("EDGE-ACTIVE", "EDGE-SUF", "GATE-1"),
        initial=((IHS, 3), (DEFLECTION, 2), (IMP_REND, 2), (ITM, 3)),
        steps=(
            validate(),
            spend(DEEP_WOUNDS, ok=False, codes=["SUFFICIENT_PREREQUISITE_NOT_MET"]),
            spend(IMP_REND),
            spend(DEEP_WOUNDS),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="warrior.refund_leaf_talent",
        cls=W,
        description="A rank nothing depends on can be refunded",
        rules=("REFUND-1",),
        initial=((IHS, 3), (DEFLECTION, 2), (IMP_REND, 3), (ITM, 3), (DEEP_WOUNDS, 1)),
        steps=(refund(DEEP_WOUNDS), validate()),
    ),
    ScenarioSpec(
        id="warrior.refund_prerequisite_with_active_dependent",
        cls=W,
        description="Improved Rend cannot drop below 3/3 while Deep Wounds is purchased "
        "(no cascade); refunds that keep every gate satisfied are allowed until a gate breaks",
        rules=("REFUND-1", "EDGE-ACTIVE", "GATE-1"),
        initial=((IHS, 3), (DEFLECTION, 2), (IMP_REND, 3), (ITM, 3), (DEEP_WOUNDS, 1)),
        steps=(
            refund(IMP_REND, ok=False, codes=["DEPENDENT_NODE_WOULD_BREAK"]),
            refund(IHS),
            refund(IHS, ok=False, codes=["DEPENDENT_NODE_WOULD_BREAK"]),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="warrior.refund_breaking_tier_gate",
        cls=W,
        description="Refunding a row-0 point that a purchased row-1 talent's gate needs is "
        "rejected",
        rules=("REFUND-1", "GATE-1"),
        initial=((IHS, 3), (DEFLECTION, 2), (IMP_CHARGE, 1)),
        steps=(
            refund(DEFLECTION, ok=False, codes=["DEPENDENT_NODE_WOULD_BREAK"]),
            spend(DEFLECTION),
            refund(DEFLECTION),
        ),
    ),
    ScenarioSpec(
        id="warrior.multi_rank_refund",
        cls=W,
        description="A 3-rank talent refunds one rank at a time down to 0; then nothing is left",
        rules=("REFUND-1", "RANK-1"),
        initial=((IHS, 3),),
        steps=(
            refund(IHS),
            refund(IHS),
            refund(IHS),
            refund(IHS, ok=False, codes=["NOT_ALLOCATED"]),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="warrior.capstone_gate_counts_points_before_purchase",
        cls=W,
        description="Mortal Strike's gate counts the whole Arms tab including itself, but is "
        "evaluated before the purchase: 29 other Arms points are not enough, 30 are (the "
        "capstone is the 31st point); an import with 29 + capstone is invalid",
        rules=("GATE-1", "VALID-1"),
        fills=(Fill(section=0, points=29, prefer=(SWEEPING,), exclude=(MORTAL_STRIKE, IHS)),),
        steps=(
            spend(MORTAL_STRIKE, ok=False, codes=["TIER_GATE_NOT_MET"]),
            spend(BOOMING_VOICE),
            spend(MORTAL_STRIKE, ok=False, codes=["TIER_GATE_NOT_MET"]),
            refund(BOOMING_VOICE),
            spend(IHS),
            spend(MORTAL_STRIKE),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="warrior.capstone_import_without_enough_points",
        cls=W,
        description="Imported: 29 other Arms points + Mortal Strike - not obtainable by any "
        "purchase order",
        rules=("GATE-1", "VALID-1"),
        fills=(Fill(section=0, points=29, prefer=(SWEEPING,), exclude=(MORTAL_STRIKE,)),),
        initial=((MORTAL_STRIKE, 1),),
        steps=(validate(ok=False, codes=["TIER_GATE_NOT_MET"]),),
    ),
    ScenarioSpec(
        id="warrior.invalid_imported_build",
        cls=W,
        description="Imported Deep Wounds with nothing else: gate and prerequisite both fail; "
        "actions on an invalid build are refused until it is fixed",
        rules=("VALID-1", "STATE-1"),
        initial=((DEEP_WOUNDS, 1),),
        steps=(
            validate(ok=False, codes=["SUFFICIENT_PREREQUISITE_NOT_MET", "TIER_GATE_NOT_MET"]),
            spend(IHS, ok=False, codes=["INVALID_BUILD_STATE"]),
            refund(DEEP_WOUNDS, ok=False, codes=["INVALID_BUILD_STATE"]),
        ),
    ),
    ScenarioSpec(
        id="warrior.corrupt_import_ranks_and_duplicates",
        cls=W,
        description="Imported: a rank above max, a rank-0 allocation and a node listed twice",
        rules=("RANK-1", "VALID-1"),
        raw_initial=(
            (IHS, IHS, 4),
            (IMP_REND, IMP_REND, 0),
            (DEFLECTION, DEFLECTION, 1),
            (DEFLECTION, DEFLECTION, 2),
        ),
        steps=(validate(ok=False, codes=["DUPLICATE_ALLOCATION", "RANK_OUT_OF_BOUNDS"]),),
    ),
    ScenarioSpec(
        id="warrior.unknown_node",
        cls=W,
        description="A node id that is not in the tree: rejected on spend and on import",
        rules=("ENTRY-1", "VALID-1"),
        steps=(
            spend(999999, ok=False, codes=["UNKNOWN_NODE"]),
            refund(999999, ok=False, codes=["UNKNOWN_NODE"]),
        ),
    ),
    ScenarioSpec(
        id="warrior.unknown_node_import",
        cls=W,
        description="An imported allocation of a node that is not in the tree",
        rules=("VALID-1",),
        raw_initial=((999999, 1, 1),),
        steps=(validate(ok=False, codes=["UNKNOWN_NODE"]),),
    ),
    ScenarioSpec(
        id="warrior.wrong_entry",
        cls=W,
        description="Spending with the entry of another node is rejected",
        rules=("ENTRY-1",),
        steps=(
            spend_with_entry_of(IHS, DEFLECTION, ok=False, codes=["INVALID_ENTRY"]),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="warrior.wrong_entry_import",
        cls=W,
        description="An imported allocation naming another node's entry",
        rules=("ENTRY-1", "VALID-1"),
        raw_initial=((IHS, DEFLECTION, 1),),
        steps=(validate(ok=False, codes=["INVALID_ENTRY"]),),
    ),
    ScenarioSpec(
        id="druid.required_prerequisite",
        cls="DRUID",
        description="Nature's Splendor has the only RequiredForAvailability edge of Forever: "
        "Nature's Majesty must be fully ranked (1/2 is not enough)",
        rules=("EDGE-REQ", "EDGE-ACTIVE", "GATE-1"),
        initial=(("Improved Wrath", 5), ("Genesis", 3), ("Nature's Majesty", 1), ("Moonglow", 1)),
        steps=(
            validate(),
            spend("Nature's Splendor", ok=False, codes=["REQUIRED_PREREQUISITE_NOT_MET"]),
            spend("Nature's Majesty"),
            spend("Nature's Splendor"),
            validate(),
            refund("Nature's Majesty", ok=False, codes=["DEPENDENT_NODE_WOULD_BREAK"]),
        ),
    ),
    ScenarioSpec(
        id="hunter.multiple_sufficient_prerequisites_any",
        cls="HUNTER",
        description="Intimidation has two SufficientForAvailability edges (Bestial Swiftness, "
        "Bestial Wrath); one fully ranked source is enough",
        rules=("EDGE-SUF", "EDGE-ACTIVE", "GATE-1"),
        fills=(
            Fill(
                section=0,
                points=20,
                exclude=("Bestial Swiftness", "Intimidation", "Bestial Wrath"),
            ),
        ),
        steps=(
            spend("Intimidation", ok=False, codes=["SUFFICIENT_PREREQUISITE_NOT_MET"]),
            spend("Bestial Swiftness"),
            spend("Intimidation"),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="hunter.mutual_sufficient_cycle",
        cls="HUNTER",
        description="Bestial Wrath <-> Intimidation are mutual sufficient prerequisites: no "
        "deadlock (Bestial Swiftness roots the cycle), Bestial Wrath cannot be taken before "
        "Intimidation, and the root cannot be refunded - the cycle cannot support itself",
        rules=("EDGE-SUF", "VALID-1", "REFUND-1"),
        fills=(
            Fill(
                section=0,
                points=29,
                prefer=("Bestial Swiftness", "Intimidation"),
                exclude=("Bestial Wrath", "Endurance Training"),
            ),
        ),
        steps=(
            spend("Bestial Wrath", ok=False, codes=["TIER_GATE_NOT_MET"]),
            spend("Endurance Training"),
            spend("Bestial Wrath"),
            validate(),
            refund("Bestial Swiftness", ok=False, codes=["DEPENDENT_NODE_WOULD_BREAK"]),
            refund("Intimidation", ok=False, codes=["DEPENDENT_NODE_WOULD_BREAK"]),
            refund("Bestial Wrath"),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="hunter.mutual_sufficient_cycle_before_root",
        cls="HUNTER",
        description="With the tab gates met but no Bestial Swiftness, neither cycle member is "
        "purchasable (each only has the other as an active source)",
        rules=("EDGE-SUF",),
        fills=(
            Fill(
                section=0,
                points=30,
                exclude=("Bestial Swiftness", "Intimidation", "Bestial Wrath"),
            ),
        ),
        steps=(
            spend("Bestial Wrath", ok=False, codes=["SUFFICIENT_PREREQUISITE_NOT_MET"]),
            spend("Intimidation", ok=False, codes=["SUFFICIENT_PREREQUISITE_NOT_MET"]),
        ),
    ),
    ScenarioSpec(
        id="hunter.mutual_sufficient_cycle_import_without_root",
        cls="HUNTER",
        description="Imported Intimidation + Bestial Wrath without Bestial Swiftness: the two "
        "support each other statically but no purchase order reaches them -> invalid",
        rules=("EDGE-SUF", "VALID-1"),
        fills=(
            Fill(
                section=0,
                points=30,
                exclude=("Bestial Swiftness", "Intimidation", "Bestial Wrath"),
            ),
        ),
        initial=(("Intimidation", 1), ("Bestial Wrath", 1)),
        steps=(validate(ok=False, codes=["SUFFICIENT_PREREQUISITE_NOT_MET"]),),
    ),
    ScenarioSpec(
        id="rogue.unresolved_condition_fails_closed",
        cls="ROGUE",
        description="Venom carries TraitCond 50984 (node + group + spend in one row), whose "
        "semantics are unresolved: never purchasable, even with every proven rule met",
        rules=("COND-1",),
        fills=(Fill(section=0, points=30, prefer=("Mutilate",), exclude=("Venom",)),),
        steps=(
            spend("Venom", ok=False, codes=["UNRESOLVED_CONDITION"]),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="rogue.unresolved_condition_import",
        cls="ROGUE",
        description="An imported build containing Venom cannot be verified -> invalid",
        rules=("COND-1", "VALID-1"),
        fills=(Fill(section=0, points=30, prefer=("Mutilate",), exclude=("Venom",)),),
        initial=(("Venom", 1),),
        steps=(validate(ok=False, codes=["UNRESOLVED_CONDITION"]),),
    ),
    # ----------------------------------------------------------------- synthetic mutations
    ScenarioSpec(
        id="synthetic.multiple_required_prerequisites_all",
        cls=W,
        description="SYNTHETIC (Forever has no node with two Required edges): Improved "
        "Overpower gets Required edges from Improved Heroic Strike and Improved Rend; both "
        "must be fully ranked",
        rules=("EDGE-REQ", "EDGE-ACTIVE"),
        mutations=(
            MutationSpec("addPrerequisite", IMP_OVERPOWER, prerequisite=IHS, kind="required"),
            MutationSpec("addPrerequisite", IMP_OVERPOWER, prerequisite=IMP_REND, kind="required"),
        ),
        initial=((IHS, 3), (IMP_REND, 2)),
        steps=(
            spend(IMP_OVERPOWER, ok=False, codes=["REQUIRED_PREREQUISITE_NOT_MET"]),
            spend(IMP_REND),
            spend(IMP_OVERPOWER),
            validate(),
        ),
    ),
    ScenarioSpec(
        id="synthetic.mixed_required_and_sufficient_fail_closed",
        cls=W,
        description="SYNTHETIC: a node with both a Required and a Sufficient edge - the "
        "combination is not proven, so it is never purchasable",
        rules=("EDGE-MIX",),
        mutations=(
            MutationSpec("addPrerequisite", IMP_OVERPOWER, prerequisite=IHS, kind="required"),
            MutationSpec(
                "addPrerequisite", IMP_OVERPOWER, prerequisite=IMP_REND, kind="sufficient"
            ),
        ),
        initial=((IHS, 3), (IMP_REND, 3)),
        steps=(spend(IMP_OVERPOWER, ok=False, codes=["UNSUPPORTED_EDGE_COMBINATION"]),),
    ),
    ScenarioSpec(
        id="synthetic.selection_node_fail_closed",
        cls=W,
        description="SYNTHETIC (Forever has no selection nodes): selection semantics are not "
        "verifiable on real data, so a selection node is never purchasable",
        rules=("NODE-1",),
        mutations=(MutationSpec("setNodeType", IHS, value="selection"),),
        steps=(spend(IHS, ok=False, codes=["UNSUPPORTED_NODE_TYPE"]),),
    ),
    ScenarioSpec(
        id="synthetic.never_purchasable_flag",
        cls=W,
        description="SYNTHETIC: TraitNodeFlag.NeverPurchasable (2) blocks purchase",
        rules=("NODE-1",),
        mutations=(MutationSpec("setNodeFlags", IHS, value=2),),
        steps=(spend(IHS, ok=False, codes=["NODE_NEVER_PURCHASABLE"]),),
    ),
    ScenarioSpec(
        id="synthetic.unknown_node_flag_fail_closed",
        cls=W,
        description="SYNTHETIC: an unobserved TraitNode.Flags bit (ActiveAtFirstRank, 16) "
        "fails closed",
        rules=("NODE-1",),
        mutations=(MutationSpec("setNodeFlags", IHS, value=16),),
        steps=(spend(IHS, ok=False, codes=["UNSUPPORTED_NODE_FLAGS"]),),
    ),
    ScenarioSpec(
        id="synthetic.editor_grid_flag_is_neutral",
        cls=W,
        description="SYNTHETIC: TestGridPositioned (8), observed on real Forever nodes, does "
        "not change allocation",
        rules=("NODE-1",),
        mutations=(MutationSpec("setNodeFlags", IHS, value=8),),
        steps=(spend(IHS), validate()),
    ),
    ScenarioSpec(
        id="synthetic.unsupported_entry_type_fail_closed",
        cls=W,
        description="SYNTHETIC: an entry type other than SpendSquare (1) fails closed",
        rules=("NODE-1",),
        mutations=(MutationSpec("setEntryType", IHS, value=0),),
        steps=(spend(IHS, ok=False, codes=["UNSUPPORTED_ENTRY_TYPE"]),),
    ),
    ScenarioSpec(
        id="synthetic.unresolved_condition_blocks_dependents",
        cls=W,
        description="SYNTHETIC: an unresolved condition on a prerequisite makes it "
        "unpurchasable, so its dependents can never become available either",
        rules=("COND-1", "EDGE-SUF"),
        mutations=(MutationSpec("addUnresolvedCondition", IMP_REND, value=1),),
        initial=((IHS, 3), (DEFLECTION, 2), (ITM, 5)),
        steps=(
            spend(IMP_REND, ok=False, codes=["UNRESOLVED_CONDITION"]),
            spend(DEEP_WOUNDS, ok=False, codes=["SUFFICIENT_PREREQUISITE_NOT_MET"]),
        ),
    ),
)


def build_suite(documents: dict[str, TalentDataDocument] | None = None) -> ConformanceSuite:
    documents = documents or load_documents()
    author = Author(documents)
    scenarios = [author.build(spec) for spec in RULE_SCENARIOS] + class_scenarios(author)
    ids = [s.id for s in scenarios]
    if len(ids) != len(set(ids)):
        raise AssertionError("duplicate scenario ids")
    used = sorted({s.class_ for s in scenarios})
    return ConformanceSuite.model_validate(
        {
            "game": GAME,
            "clientBuild": str(BASELINE),
            "datasets": [
                DatasetRef.model_validate(
                    {
                        "class": token,
                        "file": dataset_file(token),
                        "contentHash": documents[token].content_hash,
                    }
                )
                for token in used
            ],
            "scenarios": scenarios,
        }
    )
