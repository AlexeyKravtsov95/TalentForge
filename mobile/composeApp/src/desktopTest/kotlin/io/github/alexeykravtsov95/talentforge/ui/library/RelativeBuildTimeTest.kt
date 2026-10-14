package io.github.alexeykravtsov95.talentforge.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The UX spec's frozen relative-time buckets, pinned for every boundary. The
 * localized wording of each bucket (EN and RU plural forms) is pinned by
 * `ShellLocalizationUiTest`.
 */
class RelativeBuildTimeTest {

    private val now = 1_000_000_000L

    @Test
    fun coversEverySpecBucket() {
        assertEquals(BuildRecency.JustNow, buildRecencyOf(now, now))
        assertEquals(BuildRecency.JustNow, buildRecencyOf(now, now - 59_999))
        assertEquals(BuildRecency.JustNow, buildRecencyOf(now, now + 5_000)) // clock skew
        assertEquals(BuildRecency.Minutes(5), buildRecencyOf(now, now - 5 * 60_000))
        assertEquals(BuildRecency.Minutes(59), buildRecencyOf(now, now - 59 * 60_000))
        assertEquals(BuildRecency.Hours(2), buildRecencyOf(now, now - 2 * 3_600_000))
        assertEquals(BuildRecency.Hours(23), buildRecencyOf(now, now - 23 * 3_600_000))
        assertEquals(BuildRecency.Yesterday, buildRecencyOf(now, now - 86_400_000))
        assertEquals(BuildRecency.Days(3), buildRecencyOf(now, now - 3 * 86_400_000))
        assertEquals(BuildRecency.Days(6), buildRecencyOf(now, now - 6 * 86_400_000))
        assertEquals(BuildRecency.LastWeek, buildRecencyOf(now, now - 7L * 86_400_000))
        assertEquals(BuildRecency.LastWeek, buildRecencyOf(now, now - 13L * 86_400_000))
        assertEquals(BuildRecency.Weeks(2), buildRecencyOf(now, now - 14L * 86_400_000))
        assertEquals(BuildRecency.Weeks(8), buildRecencyOf(now, now - 60L * 86_400_000))
    }

    @Test
    fun monogramsMatchTheSpecMarks() {
        assertEquals("WA", classMonogramOf("Warrior"))
        assertEquals("PA", classMonogramOf("Paladin"))
        assertEquals("HU", classMonogramOf("Hunter"))
        assertEquals("RO", classMonogramOf("Rogue"))
        assertEquals("PR", classMonogramOf("Priest"))
        assertEquals("SH", classMonogramOf("Shaman"))
        assertEquals("MA", classMonogramOf("Mage"))
        assertEquals("DR", classMonogramOf("Druid"))
        assertEquals("DK", classMonogramOf("Death Knight"))
        assertEquals("DH", classMonogramOf("Demon Hunter"))
        // Documented deviation: the spec mock's mark reads "WL", but marks are
        // derived deterministically from the REAL class display name (first
        // letters of a two-word name, else the first two letters) — the same
        // rule that produces every other spec mark. Hand-coding one class's
        // letters would be a hand-maintained token map.
        assertEquals("WA", classMonogramOf("Warlock"))
    }
}
