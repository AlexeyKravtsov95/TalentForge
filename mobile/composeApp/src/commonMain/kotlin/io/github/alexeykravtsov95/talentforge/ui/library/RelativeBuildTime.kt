package io.github.alexeykravtsov95.talentforge.ui.library

import kotlin.math.max

/**
 * The UX spec's relative-time wording for build recency ("2 h ago",
 * "Yesterday", "3 days ago", "Last week"). Pure and deterministic so tests
 * pin it; a future timestamp (clock skew) reads as "Just now".
 */
fun relativeBuildTime(nowMillis: Long, atMillis: Long): String {
    val deltaMillis = nowMillis - atMillis
    if (deltaMillis < MINUTE_MS) return "Just now"
    if (deltaMillis < HOUR_MS) return "${deltaMillis / MINUTE_MS} min ago"
    if (deltaMillis < DAY_MS) return "${deltaMillis / HOUR_MS} h ago"
    val days = deltaMillis / DAY_MS
    if (days < 2L) return "Yesterday"
    if (days < 7L) return "$days days ago"
    if (days < 14L) return "Last week"
    val weeks = max(1L, days / 7L)
    return "$weeks weeks ago"
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
