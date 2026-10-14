package io.github.alexeykravtsov95.talentforge.ui.library

import androidx.compose.runtime.Composable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_days_ago
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_hours_ago
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_just_now
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_last_week
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_minutes_ago
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_weeks_ago
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.time_yesterday
import kotlin.math.max
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The UX spec's relative-time buckets for build recency ("2 h ago",
 * "Yesterday", "3 days ago", "Last week"). Pure and deterministic so tests
 * pin it; a future timestamp (clock skew) reads as [JustNow]. The wording is
 * localized separately ([relativeBuildTime]) — since M5.REL.2a the buckets
 * and the words are two concerns.
 */
sealed interface BuildRecency {
    data object JustNow : BuildRecency

    data class Minutes(val count: Int) : BuildRecency

    data class Hours(val count: Int) : BuildRecency

    data object Yesterday : BuildRecency

    data class Days(val count: Int) : BuildRecency

    data object LastWeek : BuildRecency

    data class Weeks(val count: Int) : BuildRecency
}

fun buildRecencyOf(nowMillis: Long, atMillis: Long): BuildRecency {
    val deltaMillis = nowMillis - atMillis
    if (deltaMillis < MINUTE_MS) return BuildRecency.JustNow
    if (deltaMillis < HOUR_MS) return BuildRecency.Minutes((deltaMillis / MINUTE_MS).toInt())
    if (deltaMillis < DAY_MS) return BuildRecency.Hours((deltaMillis / HOUR_MS).toInt())
    val days = deltaMillis / DAY_MS
    if (days < 2L) return BuildRecency.Yesterday
    if (days < 7L) return BuildRecency.Days(days.toInt())
    if (days < 14L) return BuildRecency.LastWeek
    val weeks = max(1L, days / 7L)
    return BuildRecency.Weeks(weeks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

/**
 * The localized relative time of a build (EN default, RU with the language's
 * plural forms: `3 дня назад`, `5 дней назад`, `21 неделю назад`).
 */
@Composable
fun relativeBuildTime(nowMillis: Long, atMillis: Long): String =
    when (val recency = buildRecencyOf(nowMillis, atMillis)) {
        BuildRecency.JustNow -> stringResource(Res.string.time_just_now)
        is BuildRecency.Minutes -> stringResource(Res.string.time_minutes_ago, recency.count)
        is BuildRecency.Hours -> stringResource(Res.string.time_hours_ago, recency.count)
        BuildRecency.Yesterday -> stringResource(Res.string.time_yesterday)
        is BuildRecency.Days -> pluralStringResource(Res.plurals.time_days_ago, recency.count, recency.count)
        BuildRecency.LastWeek -> stringResource(Res.string.time_last_week)
        is BuildRecency.Weeks -> pluralStringResource(Res.plurals.time_weeks_ago, recency.count, recency.count)
    }

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
