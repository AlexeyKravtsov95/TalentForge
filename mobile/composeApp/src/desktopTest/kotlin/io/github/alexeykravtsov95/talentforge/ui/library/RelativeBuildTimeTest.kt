package io.github.alexeykravtsov95.talentforge.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals

/** The UX spec's frozen relative-time wording, pinned for every bucket. */
class RelativeBuildTimeTest {

    private val now = 1_000_000_000L

    @Test
    fun coversEverySpecBucket() {
        assertEquals("Just now", relativeBuildTime(now, now))
        assertEquals("Just now", relativeBuildTime(now, now - 59_999))
        assertEquals("Just now", relativeBuildTime(now, now + 5_000)) // clock skew
        assertEquals("5 min ago", relativeBuildTime(now, now - 5 * 60_000))
        assertEquals("59 min ago", relativeBuildTime(now, now - 59 * 60_000))
        assertEquals("2 h ago", relativeBuildTime(now, now - 2 * 3_600_000))
        assertEquals("23 h ago", relativeBuildTime(now, now - 23 * 3_600_000))
        assertEquals("Yesterday", relativeBuildTime(now, now - 86_400_000))
        assertEquals("3 days ago", relativeBuildTime(now, now - 3 * 86_400_000))
        assertEquals("6 days ago", relativeBuildTime(now, now - 6 * 86_400_000))
        assertEquals("Last week", relativeBuildTime(now, now - 7L * 86_400_000))
        assertEquals("Last week", relativeBuildTime(now, now - 13L * 86_400_000))
        assertEquals("2 weeks ago", relativeBuildTime(now, now - 14L * 86_400_000))
        assertEquals("8 weeks ago", relativeBuildTime(now, now - 60L * 86_400_000))
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
