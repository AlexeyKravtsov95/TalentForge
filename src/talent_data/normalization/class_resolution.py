"""Class -> TraitTree resolution.

Chain verified on real Forever rows (1.60.1.70009):

    ChrClasses.ID = 1 (WARRIOR)
      -> class bit = 1 << (ID - 1)                     (ClassMask 1)
    SkillRaceClassInfo.SkillID = 26 with ClassMask & bit
    SkillLineXTraitTree.SkillLineID = 26 -> TraitTreeID = 1117

``TraitTree`` itself has no class or name column in this build, and ``TraitTreeLoadout`` /
``TraitSubTree`` do not exist in Forever builds (wago.tools returns 404), so this skill-line
chain is the only class link available in the client data.
"""

from __future__ import annotations

from talent_data.db2.dataset import TraitDataSet
from talent_data.db2.tables import ChrClass


class ClassResolutionError(LookupError):
    pass


def resolve_class(dataset: TraitDataSet, class_key: str) -> ChrClass:
    """Find a class by token (``warrior``), localized name or numeric ID."""
    key = class_key.strip().lower()
    matches = [
        c for c in dataset.classes.values() if key in {c.token.lower(), c.name.lower(), str(c.id)}
    ]
    if len(matches) != 1:
        available = ", ".join(sorted(c.token.lower() for c in dataset.classes.values()))
        raise ClassResolutionError(
            f"class {class_key!r} matched {len(matches)} classes in build "
            f"{dataset.version}; available: {available}"
        )
    return matches[0]


def class_bit(chr_class: ChrClass) -> int:
    # ChrClasses IDs are 1-based; class masks use bit (ID - 1). This is the same convention
    # used by every ClassMask column in the client (verified: Warrior=1, Mage(8)=128,
    # Druid(11)=1024 in SkillRaceClassInfo for their talent skill lines).
    return 1 << (chr_class.id - 1)


def trees_for_class(dataset: TraitDataSet, chr_class: ChrClass) -> list[int]:
    bit = class_bit(chr_class)
    tree_ids: set[int] = set()
    for link in dataset.skill_line_trees:
        masks = dataset.skill_class_masks.get(link.skill_line_id, [])
        # ClassMask <= 0 means "not class-restricted" (0) or "all classes" (-1): such a skill
        # line does not identify a class-specific talent tree, so it is ignored.
        if any(mask > 0 and mask & bit for mask in masks):
            tree_ids.add(link.trait_tree_id)
    missing = sorted(tid for tid in tree_ids if tid not in dataset.trees)
    if missing:
        raise ClassResolutionError(
            f"SkillLineXTraitTree references unknown TraitTree ids {missing}"
        )
    if not tree_ids:
        raise ClassResolutionError(
            f"no talent tree linked to class {chr_class.token} in build {dataset.version}"
        )
    return sorted(tree_ids)
