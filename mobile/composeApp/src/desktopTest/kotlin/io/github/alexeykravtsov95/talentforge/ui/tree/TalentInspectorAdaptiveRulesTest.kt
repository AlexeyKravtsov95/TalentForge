package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.unit.dp
import io.github.alexeykravtsov95.talentforge.ui.library.ALL_BUNDLES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M5.UX.2a pure rules: the 1A v2 sheet limits, the compact and header
 * arrangements, the reveal policy that turns MEASURED inspector heights into
 * camera moves, and the state tone — the last one on the real engine of every
 * bundled client build.
 */
class TalentInspectorAdaptiveRulesTest {

    // ------------------------------------------------------------------ sheet limits (1A v2 02)

    @Test
    fun sheetLimitsFollowTheOneAV2Formula() {
        // 1A v2 03 3.2: 320 × 568 with 120 dp of top chrome → "Лист 344 dp"
        val small = expandedSheetLimits(screenHeight = 568.dp, areaHeight = 448.dp)
        assertEquals(344f, small.max.value, 1e-3f, "H − top − 104 binds on the small phone")
        assertEquals(260f, small.min.value, 1e-3f)

        // 393 × 852: 66 % of H binds
        val large = expandedSheetLimits(screenHeight = 771.dp, areaHeight = 671.dp)
        assertEquals(771f * 0.66f, large.max.value, 1e-3f)
        assertEquals(260f, large.min.value, 1e-3f)
    }

    @Test
    fun sheetLimitsDegradeWithoutGoingNegativeOrInverted() {
        val tiny = expandedSheetLimits(screenHeight = 300.dp, areaHeight = 150.dp)
        assertEquals(46f, tiny.max.value, 1e-3f)
        assertEquals(tiny.max, tiny.min, "the floor never exceeds the ceiling")

        val none = expandedSheetLimits(screenHeight = 120.dp, areaHeight = 60.dp)
        assertEquals(0f, none.max.value, 1e-3f)
        assertEquals(0f, none.min.value, 1e-3f)
    }

    // ------------------------------------------------------------------ compact arrangement

    @Test
    fun compactStripKeepsOneRowWhereOneAV2DoesAndGrowsAtLargeText() {
        val one = TalentInspectorCompactLayout.SINGLE_ROW
        val two = TalentInspectorCompactLayout.TWO_LINE
        // 1A v2 03: 320 dp (3.1) and 320 dp at 130 % (3.3) stay one row, so does 360 at 130 % (3.4)
        assertEquals(one, talentInspectorCompactLayout(320.dp, 1f))
        assertEquals(one, talentInspectorCompactLayout(320.dp, 1.3f))
        assertEquals(one, talentInspectorCompactLayout(360.dp, 1.3f))
        assertEquals(one, talentInspectorCompactLayout(393.dp, 1f))
        // 1A v2 S8: at 200 % the row grows to two lines on every phone
        assertEquals(two, talentInspectorCompactLayout(320.dp, 2f))
        assertEquals(two, talentInspectorCompactLayout(393.dp, 2f))
        // narrower than any target phone: the name column would drop below 88 dp
        assertEquals(two, talentInspectorCompactLayout(270.dp, 1f))
        // a broken font scale never crashes and falls back to 100 %
        assertEquals(one, talentInspectorCompactLayout(360.dp, Float.NaN))
    }

    // ------------------------------------------------------------------ header arrangement (1A v2 S1)

    @Test
    fun headerMovesTheCounterIntoTheMetaLineExactlyWhereOneAV2Says() {
        val inline = TalentTreeHeaderLayout.POINTS_INLINE
        val meta = TalentTreeHeaderLayout.POINTS_IN_META
        assertEquals(meta, talentTreeHeaderLayout(320.dp, 1f), "W < 360")
        assertEquals(meta, talentTreeHeaderLayout(359.dp, 1f), "W < 360")
        assertEquals(inline, talentTreeHeaderLayout(360.dp, 1f))
        assertEquals(inline, talentTreeHeaderLayout(393.dp, 1.15f), "115 % is not above 115 %")
        assertEquals(meta, talentTreeHeaderLayout(393.dp, 1.3f), "> 115 % on W < 400")
        assertEquals(inline, talentTreeHeaderLayout(412.dp, 1.3f), "W ≥ 400 keeps it inline")
        assertEquals(meta, talentTreeHeaderLayout(412.dp, 2f), "the 300 dp per-scale guard")
    }

    // ------------------------------------------------------------------ reveal policy

    private fun m(nodeId: Int, expanded: Boolean, covered: Float) =
        TalentInspectorMeasurement(nodeId = nodeId, expanded = expanded, coveredDp = covered)

    @Test
    fun aNewPresentationRevealsOnceOnItsFirstMeasurement() {
        val tracker = TalentInspectorRevealTracker()
        assertTrue(tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0), "opening the strip")
        assertFalse(tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0), "the same height again")
        assertTrue(tracker.onMeasured(m(1, true, 300f), gestureEpoch = 0), "expanding (B3 08)")
        assertTrue(tracker.onMeasured(m(2, false, 76f), gestureEpoch = 0), "another talent")
    }

    @Test
    fun collapsingNeverRevealsAndShrinkingNeverMovesTheCamera() {
        val tracker = TalentInspectorRevealTracker()
        tracker.onMeasured(m(1, true, 420f), gestureEpoch = 0)
        assertFalse(tracker.onMeasured(m(1, true, 380f), gestureEpoch = 0), "the sheet shrank")
        assertFalse(tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0), "B3 08: collapsing never moves")
    }

    @Test
    fun growthRevealsOnlyWhileTheUserHasNotMovedTheCamera() {
        val tracker = TalentInspectorRevealTracker()
        tracker.onMeasured(m(1, true, 300f), gestureEpoch = 4)
        assertFalse(tracker.onMeasured(m(1, true, 300.4f), gestureEpoch = 4), "sub-dp noise is not growth")
        assertTrue(tracker.onMeasured(m(1, true, 340f), gestureEpoch = 4), "1A v2 S3: the sheet grew")
        // the user pans; the sheet grows again — never fight the gesture, for the rest of this presentation
        assertFalse(tracker.onMeasured(m(1, true, 380f), gestureEpoch = 5))
        assertFalse(tracker.onMeasured(m(1, true, 420f), gestureEpoch = 5))
    }

    @Test
    fun showAndTheBackChipPlaceTheirTargetThemselves() {
        val tracker = TalentInspectorRevealTracker()
        tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0)
        tracker.suppressNextOpen(2)
        assertFalse(tracker.onMeasured(m(2, false, 76f), gestureEpoch = 0), "Show centred it already")
        assertTrue(tracker.onMeasured(m(3, false, 76f), gestureEpoch = 0), "the suppression is one-shot")
    }

    @Test
    fun resetMakesTheNextPresentationAnOpeningAgain() {
        val tracker = TalentInspectorRevealTracker()
        tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0)
        tracker.reset() // deselected
        assertTrue(tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0))
    }

    @Test
    fun invalidMeasurementsNeverRevealAndRepeatsTerminate() {
        val tracker = TalentInspectorRevealTracker()
        assertFalse(tracker.onMeasured(m(1, false, 0f), gestureEpoch = 0))
        assertFalse(tracker.onMeasured(m(1, false, Float.NaN), gestureEpoch = 0))
        assertFalse(tracker.onMeasured(m(1, false, Float.POSITIVE_INFINITY), gestureEpoch = 0))
        assertTrue(tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0))
        // a measure → reveal → re-measure cycle reports the same height: it stops at once
        repeat(100) { assertFalse(tracker.onMeasured(m(1, false, 76f), gestureEpoch = 0)) }
    }

    // ------------------------------------------------------------------ state tone, all bundled builds

    @Test
    fun theStateToneFollowsTheEngineOnEveryBundledBuild() {
        val expected = mapOf(
            "available" to TalentInspectorStateTone.AVAILABLE,
            "prerequisite" to TalentInspectorStateTone.BLOCKED,
            "tier" to TalentInspectorStateTone.BLOCKED,
            "partial" to TalentInspectorStateTone.INVESTED,
            "maxed" to TalentInspectorStateTone.INVESTED,
            "no-points" to TalentInspectorStateTone.BLOCKED,
            "refund-blocked" to TalentInspectorStateTone.BLOCKED,
        )
        for (bundle in ALL_BUNDLES) {
            val state = readyStateOnBundle(bundle.clientBuild, "WARRIOR")
            val scenarios = compactScenarios(state)
            assertEquals(expected.keys, scenarios.keys, "every compact state exists on ${bundle.clientBuild}")
            for ((label, scenario) in scenarios) {
                val (content, actions) = inspectorInputsOf(state, scenario.build, scenario.nodeName)
                assertEquals(
                    expected.getValue(label),
                    talentInspectorStateTone(content, actions),
                    "$label (${scenario.nodeName}) on ${bundle.clientBuild}",
                )
                if (expected.getValue(label) == TalentInspectorStateTone.BLOCKED) {
                    val reason = content.prerequisiteLine ?: content.tierLine ?: content.stateLine
                    assertTrue(reason.isNotBlank(), "a blocked state always says why: $label")
                }
            }
        }
    }
}
