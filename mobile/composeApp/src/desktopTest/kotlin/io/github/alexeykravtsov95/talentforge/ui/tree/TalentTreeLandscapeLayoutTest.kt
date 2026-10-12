package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M5.UX.2b.1 pure layout rules: which windows are landscape phones (the
 * section pages + side panel) rather than tablets, where the landscape tabs
 * go, and the camera clamp around the overlaid side panel.
 */
class TalentTreeLandscapeLayoutTest {

    // ------------------------------------------------------------------ classification

    @Test
    fun wideButShortAreasAreLandscapePhonesNotTablets() {
        // modern phones held sideways are 840+ dp wide (canvas region below the header)
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeLayoutClass(915f, 360f))
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeLayoutClass(932f, 378f))
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeLayoutClass(840f, 479f))
        // a tablet is wide AND tall enough
        assertEquals(TalentTreeLayoutClass.TABLET, talentTreeLayoutClass(840f, 480f))
        assertEquals(TalentTreeLayoutClass.TABLET, talentTreeLayoutClass(840f, 600f))
        assertEquals(TalentTreeLayoutClass.TABLET, talentTreeLayoutClass(1280f, 800f))
        // unchanged: phones and a narrow tall window
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeLayoutClass(760f, 360f))
        assertEquals(TalentTreeLayoutClass.PORTRAIT, talentTreeLayoutClass(360f, 760f))
        assertEquals(TalentTreeLayoutClass.PORTRAIT, talentTreeLayoutClass(800f, 1280f))
    }

    @Test
    fun theWorkspaceIsClassifiedOnceWithTheHeaderOutsideTheCanvasRegion() {
        val header = TalentTreeHeaderMetrics.HEADER_HEIGHT_DP.value
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeWorkspaceLayoutClass(915f, 412f))
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeWorkspaceLayoutClass(568f, 320f))
        assertEquals(TalentTreeLayoutClass.PORTRAIT, talentTreeWorkspaceLayoutClass(412f, 915f))
        assertEquals(TalentTreeLayoutClass.TABLET, talentTreeWorkspaceLayoutClass(1280f, 800f))
        // the tablet height bound applies to the region below the header
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeWorkspaceLayoutClass(840f, 479f + header))
        assertEquals(TalentTreeLayoutClass.TABLET, talentTreeWorkspaceLayoutClass(840f, 480f + header))
        // near-square: the canvas region decides
        assertEquals(TalentTreeLayoutClass.PORTRAIT, talentTreeWorkspaceLayoutClass(600f, 600f + header + 1f))
        assertEquals(TalentTreeLayoutClass.LANDSCAPE, talentTreeWorkspaceLayoutClass(600f, 600f + header))
    }

    // ------------------------------------------------------------------ tab placement

    @Test
    fun tabsJoinTheHeaderWhenEveryPartStillFits() {
        val typical = talentTreeLandscapeTabs(widthDp = 915f, fontScale = 1f, tabCount = 3)
        assertEquals(TalentTreeLandscapeTabsPlacement.IN_HEADER, typical.placement)
        assertEquals(3 * TalentTreeLandscapeHeaderMetrics.PREFERRED_TAB_DP, typical.tabsWidthDp, "preferred width")

        val compact = talentTreeLandscapeTabs(widthDp = 640f, fontScale = 1f, tabCount = 3)
        assertEquals(TalentTreeLandscapeTabsPlacement.IN_HEADER, compact.placement)
        assertEquals(640f - 200f - 72f - 8f - 120f, compact.tabsWidthDp, "what is left next to the controls")
        assertTrue(compact.tabsWidthDp >= 3 * TalentTreeSectionTabsMetrics.TAB_MIN_WIDTH_DP.value)

        // 200 % font: the tabs are number-only, so they keep their 100 % minimum
        assertEquals(
            TalentTreeLandscapeTabsPlacement.IN_HEADER,
            talentTreeLandscapeTabs(widthDp = 915f, fontScale = 2f, tabCount = 3).placement,
        )
    }

    @Test
    fun tabsKeepTheirOwnRowWhenTheHeaderIsTooNarrow() {
        val below = TalentTreeLandscapeTabsPlacement.BELOW_HEADER
        // 568 × 320 (smallest landscape phone), or a 640 dp phone at 130 % font
        assertEquals(below, talentTreeLandscapeTabs(widthDp = 568f, fontScale = 1f, tabCount = 3).placement)
        assertEquals(below, talentTreeLandscapeTabs(widthDp = 640f, fontScale = 1.3f, tabCount = 3).placement)
        // a cutout narrows the safe width: 640 − 2 × 32
        assertEquals(below, talentTreeLandscapeTabs(widthDp = 576f, fontScale = 1f, tabCount = 3).placement)
        // degenerate inputs never throw and never put tabs in the header
        assertEquals(below, talentTreeLandscapeTabs(widthDp = 915f, fontScale = 1f, tabCount = 0).placement)
        assertEquals(below, talentTreeLandscapeTabs(widthDp = Float.NaN, fontScale = 1f, tabCount = 3).placement)
        // a non-finite font scale is read as 100 %
        assertEquals(
            talentTreeLandscapeTabs(widthDp = 915f, fontScale = 1f, tabCount = 3),
            talentTreeLandscapeTabs(widthDp = 915f, fontScale = Float.NaN, tabCount = 3),
        )
    }

    // ------------------------------------------------------------------ the camera around the side panel

    private val content = TalentTreeBounds(minX = 0f, minY = 0f, maxX = 200f, maxY = 100f)
    private val canvas = Size(900f, 300f)

    @Test
    fun theClampExcludesTheRightSidePanel() {
        val underPanel = TalentTreeViewport(1f, Offset(650f, 50f)) // content 650..850, panel 600..900
        // without a reference the content is pulled fully into the visible 600 dp
        assertEquals(
            388f, // 600 − field 12 − content 200
            underPanel.clampedTo(content, canvas, coveredRightDp = 300f).offset.x,
        )
        // relative to where it already sat: never a jump, never deeper under the panel…
        assertEquals(650f, underPanel.clampedTo(content, canvas, reference = underPanel, coveredRightDp = 300f).offset.x)
        assertEquals(
            650f,
            TalentTreeViewport(1f, Offset(700f, 50f))
                .clampedTo(content, canvas, reference = underPanel, coveredRightDp = 300f).offset.x,
        )
        // …but always free to come out from under it
        assertEquals(
            300f,
            TalentTreeViewport(1f, Offset(300f, 50f))
                .clampedTo(content, canvas, reference = underPanel, coveredRightDp = 300f).offset.x,
        )
        // the vertical axis is untouched by a side panel
        assertEquals(50f, underPanel.clampedTo(content, canvas, coveredRightDp = 300f).offset.y)
    }

    @Test
    fun theClampExcludesTheLeftSidePanelInRtl() {
        val underPanel = TalentTreeViewport(1f, Offset(0f, 50f)) // content 0..200, panel 0..300
        assertEquals(
            312f, // the visible region starts at 300, plus the 12 dp field
            underPanel.clampedTo(content, canvas, coveredLeftDp = 300f).offset.x,
        )
        assertEquals(0f, underPanel.clampedTo(content, canvas, reference = underPanel, coveredLeftDp = 300f).offset.x)
        // no panel: the pre-M5.UX.2b.1 clamp exactly
        assertEquals(
            underPanel.clampedTo(content, canvas),
            underPanel.clampedTo(content, canvas, coveredLeftDp = 0f, coveredRightDp = 0f),
        )
    }

    @Test
    fun theCameraStateClampsGesturesAroundTheCoveredSide() {
        val state = TalentTreeViewportState(initial = null, fitPolicy = TalentTreeFitPolicy.SECTION)
        state.prepare(content, canvas, TalentTreeLayoutClass.LANDSCAPE)
        state.applyTransform(TalentTreeViewport(1f, Offset(100f, 50f)))
        state.coveredRightDp = 300f
        state.applyUserTransform(TalentTreeViewport(1f, Offset(800f, 50f)))
        assertEquals(388f, state.viewport!!.offset.x, "a drag never pushes the content under the open panel")
        state.coveredRightDp = 0f
        state.applyUserTransform(TalentTreeViewport(1f, Offset(800f, 50f)))
        assertEquals(688f, state.viewport!!.offset.x, "the panel closed: the whole width is visible again")
    }
}
