package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.DatasetBundleId
import io.github.alexeykravtsov95.talentforge.data.GameId

/**
 * The one dataset bundle M5.1 ships: Forever at the baseline client build.
 * New Build resolves its class list from THIS bundle's manifest (never from
 * hardcoded filenames); the manifest stays the dataset list.
 */
val BASELINE_BUNDLE: DatasetBundleId = DatasetBundleId(
    game = GameId("forever"),
    clientBuild = "1.60.1.70009",
)

/** Display form of a game id: `forever` → `Forever`. */
fun gameDisplayName(game: GameId): String = game.value.replaceFirstChar { it.uppercase() }

/**
 * The human-facing data version of a client build: `1.60.1.70009` →
 * `1.60.1` (first three components — how the UX spec's "Talent data 1.60.1"
 * line is derived, presentation-only, no contract meaning).
 */
fun dataVersionOf(clientBuild: String): String = clientBuild.split('.').take(3).joinToString(".")
