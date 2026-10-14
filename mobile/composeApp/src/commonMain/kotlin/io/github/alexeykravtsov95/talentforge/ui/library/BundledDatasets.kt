package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId

/**
 * The product dataset (M5.DATA.3): the bundle New Build lists its classes from
 * and creates every new draft on — Forever `1.60.1.70374`, the newest Forever
 * build wago.tools reported on 2026-10-14 that is explicitly not a
 * background download. That makes it the product's selected version, not a
 * claim that it is the build the live realms run (wago.tools cannot say) or
 * that it is current (`scripts/check_product_freshness.py` reads this
 * declaration and answers that question).
 * New Build resolves its class list from THIS bundle's manifest (never from
 * hardcoded filenames); the manifest stays the dataset list.
 *
 * `1.60.1.70245` (M5.DATA) and `1.60.1.70338` (M5.DATA.2), the earlier product
 * datasets, stay bundled, unchanged: builds saved on them open on them like any
 * saved build (see `loadEditorSession`); neither is ever a fallback for, or
 * migrated to, this one — even where the talent data is identical (70338 and
 * 70374 are: same rows, different client build).
 */
val PRODUCT_BUNDLE: DatasetBundleId = DatasetBundleId(
    game = GameId("forever"),
    clientBuild = "1.60.1.70374",
)

/**
 * The verified reference baseline: Forever `1.60.1.70009`, the build the live
 * client evidence and the M2 conformance vectors are pinned to and every
 * build saved before M5.DATA was created on. It stays bundled, unchanged,
 * next to [PRODUCT_BUNDLE]: a saved build always opens on its OWN recorded
 * game / client build / class (see `loadEditorSession`) — never migrated to,
 * or substituted by, the product dataset.
 */
val REFERENCE_BUNDLE: DatasetBundleId = DatasetBundleId(
    game = GameId("forever"),
    clientBuild = "1.60.1.70009",
)

/** Display form of a game id: `forever` → `Forever`. */
fun gameDisplayName(game: GameId): String = game.value.replaceFirstChar { it.uppercase() }

/**
 * The human-facing data version of a client build: `1.60.1.70374` →
 * `1.60.1` (first three components — how the UX spec's "Talent data 1.60.1"
 * line is derived, presentation-only, no contract meaning).
 */
fun dataVersionOf(clientBuild: String): String = clientBuild.split('.').take(3).joinToString(".")
