package io.github.alexeykravtsov95.talentforge.ui.tree

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.section_points_spent
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import io.github.alexeykravtsov95.talentforge.ui.theme.talentForgeColors
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource

/**
 * M5.UI.TABLET.1: the tablet editor shows the whole tree on one canvas — all
 * of the class's specialization trees side by side — so it has no section
 * tabs; each tree is named by a header centred above its top row instead:
 * the specialization's display label and the points spent in it.
 *
 * The headers are an overlay of the canvas, not part of its world: they are
 * placed in screen space from the ONE whole-tree camera
 * ([TalentTreeViewportState]) at layout time, so they follow pan, zoom and
 * FIT without a second camera and without recomposing per frame, and their
 * text keeps its size (readable) at every zoom. The camera keeps
 * [talentTabletHeaderReserveDp] free above the trees, so the fit and the pan
 * clamp always leave room for them. They carry no pointer input: every touch
 * goes to the canvas below (select, pan, pinch), and nothing here touches the
 * build.
 */
object TalentTabletSectionHeaderMetrics {

    /** Implementation-safe: vertical padding inside a header. */
    const val VERTICAL_PADDING_DP: Float = 4f

    /**
     * Implementation-safe: screen gap between a header and the top of its tree's
     * talents — measured from their selection-ring room, so a selected top-row
     * talent never touches its header.
     */
    const val GAP_DP: Float = 6f

    /** Implementation-safe: room kept between neighbouring headers and from the canvas edges. */
    const val SIDE_GAP_DP: Float = 4f

    /** Implementation-safe: a header column narrower than this is not shown (its tree is all but off screen). */
    const val MIN_WIDTH_DP: Float = 48f

    /**
     * Implementation-safe: talents further apart than two grid steps along an
     * axis belong to different clusters. A section's header is anchored on its
     * largest cluster, so a documented off-grid talent (the dataset's
     * `OFF_GRID_NODE` warnings — e.g. Hunter's Lightning Reflexes, far right
     * of Survival) is still drawn and reachable but never drags the header off
     * its tree.
     */
    const val CORE_GAP_DP: Float = 2f * TalentTreeCanvasMetrics.GRID_STEP_DP

    /** UX/UI spec G Title-small role scaled for the canvas: 15 / 20 · 600. */
    val LABEL_STYLE: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

    /** The spent points in the tab's mono style, on the label's line. */
    val POINTS_STYLE: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Test tag of the header of section [index] (the dataset's `TalentSection.index`). */
fun talentTabletSectionHeaderTag(index: Int): String = "talent-tablet-section-header-$index"

/**
 * Where a section's header anchors, in world dp: the horizontal extent of its
 * core talents' visual boxes ([minX]..[maxX]) and the top of their containers
 * — visual plus selection-ring room — ([topY]).
 */
data class TalentTabletHeaderAnchor(val minX: Float, val maxX: Float, val topY: Float)

/** One tablet header: the section, its display label, its live spent points and its anchor (null: none shown). */
data class TalentTabletSectionHeader(
    val index: Int,
    val label: String,
    val points: Int,
    val anchor: TalentTabletHeaderAnchor?,
)

/**
 * The headers of [sections]: each label resolved by [talentSectionDisplayLabel]
 * (the dataset's name, else the approved specialization name of ([classToken],
 * index) in [language], else "Tree N" / "Ветка N") and each count from the live
 * per-section [points] ([sectionSpentPoints]).
 */
fun talentTabletSectionHeaders(
    sections: TalentTreeSectionsResult.Sectioned,
    points: Map<Int, Int>,
    classToken: ClassToken,
    language: AppLanguage,
): List<TalentTabletSectionHeader> = sections.sections.map { section ->
    TalentTabletSectionHeader(
        index = section.index,
        label = talentSectionDisplayLabel(
            datasetName = section.datasetName,
            classToken = classToken,
            sectionIndex = section.index,
            ordinal = section.ordinal,
            language = language,
        ),
        points = points[section.index] ?: 0,
        anchor = talentTabletHeaderAnchor(section.model.nodes),
    )
}

/**
 * The anchor of a section of [nodes]: its core talents — those in the largest
 * cluster of x positions AND of y positions ([TalentTabletSectionHeaderMetrics.CORE_GAP_DP]).
 * `null` for a section without a talent (nothing to stand above).
 */
fun talentTabletHeaderAnchor(nodes: List<TalentTreeNodeUiModel>): TalentTabletHeaderAnchor? {
    val placed = nodes.filter { it.centerX.isFinite() && it.centerY.isFinite() }
    if (placed.isEmpty()) return null
    val columns = largestCluster(placed.map { it.centerX })
    val rows = largestCluster(placed.map { it.centerY })
    val core = placed.filter { it.centerX in columns && it.centerY in rows }.ifEmpty { placed }
    val half = TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f
    return TalentTabletHeaderAnchor(
        minX = core.minOf { it.centerX } - half,
        maxX = core.maxOf { it.centerX } + half,
        topY = core.minOf { it.centerY } - talentNodeContainerHalfSideDp(),
    )
}

/** The range of the largest run of [values] whose neighbours are at most CORE_GAP apart (first wins a tie). */
private fun largestCluster(values: List<Float>): ClosedFloatingPointRange<Float> {
    val sorted = values.sorted()
    var bestStart = 0
    var bestEnd = 0
    var start = 0
    for (i in 1..sorted.size) {
        if (i == sorted.size || sorted[i] - sorted[i - 1] > TalentTabletSectionHeaderMetrics.CORE_GAP_DP) {
            if (i - start > bestEnd - bestStart + 1) {
                bestStart = start
                bestEnd = i - 1
            }
            start = i
        }
    }
    return sorted[bestStart]..sorted[bestEnd]
}

/**
 * Where one header may be drawn, in canvas screen dp: within [left]..[right]
 * (its column, never a neighbour's, never outside the canvas), centred on
 * [centerX] as far as the column allows, with its bottom edge at [bottom].
 */
data class TalentTabletHeaderSlot(val left: Float, val right: Float, val centerX: Float, val bottom: Float)

/**
 * The header slots of [anchors] under [viewport] in a canvas [canvasWidthDp]
 * wide (same order; `null` = not shown). Each tree owns the column up to the
 * midpoint of the gap to its neighbours (its outer side mirrors its inner
 * side; a lone tree owns its own width), less
 * [TalentTabletSectionHeaderMetrics.SIDE_GAP_DP], clipped to the canvas — so
 * two headers never overlap and none leaves the canvas (the inspector is
 * beside it). A tree that is off screen, or whose column is narrower than
 * [TalentTabletSectionHeaderMetrics.MIN_WIDTH_DP], shows no header.
 */
fun talentTabletHeaderSlots(
    anchors: List<TalentTabletHeaderAnchor?>,
    viewport: TalentTreeViewport,
    canvasWidthDp: Float,
): List<TalentTabletHeaderSlot?> {
    val metrics = TalentTabletSectionHeaderMetrics
    val slots = arrayOfNulls<TalentTabletHeaderSlot>(anchors.size)
    if (!canvasWidthDp.isFinite() || canvasWidthDp <= 0f) return slots.toList()
    val spans = anchors.map { anchor ->
        anchor?.let {
            Span(
                left = it.minX * viewport.scale + viewport.offset.x,
                right = it.maxX * viewport.scale + viewport.offset.x,
                top = it.topY * viewport.scale + viewport.offset.y,
            )
        }
    }
    val order = spans.indices.filter { spans[it] != null }.sortedBy { spans[it]!!.center }
    for ((position, index) in order.withIndex()) {
        val span = spans[index]!!
        val previous = order.getOrNull(position - 1)?.let { spans[it] }
        val next = order.getOrNull(position + 1)?.let { spans[it] }
        val lowBoundary = previous?.let { (it.right + span.left) / 2f }
        val highBoundary = next?.let { (span.right + it.left) / 2f }
        val lowRoom = lowBoundary?.let { (span.left - it).coerceAtLeast(0f) }
        val highRoom = highBoundary?.let { (it - span.right).coerceAtLeast(0f) }
        val low = lowBoundary ?: (span.left - (highRoom ?: 0f))
        val high = highBoundary ?: (span.right + (lowRoom ?: 0f))
        val left = maxOf(low + metrics.SIDE_GAP_DP, metrics.SIDE_GAP_DP)
        val right = minOf(high - metrics.SIDE_GAP_DP, canvasWidthDp - metrics.SIDE_GAP_DP)
        val treeOnScreen = span.right > 0f && span.left < canvasWidthDp
        if (!treeOnScreen || right - left < metrics.MIN_WIDTH_DP) continue
        slots[index] = TalentTabletHeaderSlot(
            left = left,
            right = right,
            centerX = span.center,
            bottom = span.top - metrics.GAP_DP,
        )
    }
    return slots.toList()
}

private class Span(val left: Float, val right: Float, val top: Float) {
    val center: Float get() = (left + right) / 2f
}

/** The fixed height (dp) of one header at [density]'s font scale. */
fun talentTabletHeaderHeightDp(density: Density): Float = with(density) {
    maxOf(
        TalentTabletSectionHeaderMetrics.LABEL_STYLE.lineHeight.toDp(),
        TalentTabletSectionHeaderMetrics.POINTS_STYLE.lineHeight.toDp(),
    ).value
} + 2f * TalentTabletSectionHeaderMetrics.VERTICAL_PADDING_DP

/**
 * The screen room (dp) the tablet camera keeps above the trees
 * ([TalentTreeViewportState.contentTopReserveDp]): a header, its gap, and the
 * selection-ring room of the top row at the largest zoom.
 */
fun talentTabletHeaderReserveDp(density: Density): Float =
    talentTabletHeaderHeightDp(density) + TalentTabletSectionHeaderMetrics.GAP_DP +
        (talentNodeContainerHalfSideDp() - TalentTreeCanvasMetrics.NODE_VISUAL_DP / 2f) * TalentTreeCanvasMetrics.ZOOM_MAX

/**
 * The tablet specialization headers over the whole-tree canvas (same bounds
 * as the canvas): placed from [viewportState]'s live camera at layout time.
 * Display only — headings for accessibility, no click and no pointer input.
 */
@Composable
fun TalentTabletSectionHeaders(
    headers: List<TalentTabletSectionHeader>,
    viewportState: TalentTreeViewportState,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val heightDp = talentTabletHeaderHeightDp(LocalDensity.current)
    Layout(
        content = {
            for (header in headers) {
                key(header.index) { SectionHeader(header, accent) }
            }
        },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // read at measure time: a pan / zoom frame re-places the headers, never recomposes them
        val viewport = viewportState.viewport
        val slots = if (viewport != null) {
            talentTabletHeaderSlots(headers.map { it.anchor }, viewport, width / density)
        } else {
            emptyList()
        }
        val headerHeight = (heightDp * density).roundToInt()
        val placed = measurables.mapIndexedNotNull { index, measurable ->
            val slot = slots.getOrNull(index) ?: return@mapIndexedNotNull null
            val slotWidth = ((slot.right - slot.left) * density).toInt().coerceAtLeast(0)
            measurable.measure(Constraints(maxWidth = slotWidth, minHeight = headerHeight, maxHeight = headerHeight)) to slot
        }
        layout(width, height) {
            for ((placeable, slot) in placed) {
                val left = slot.left * density
                val maxLeft = slot.right * density - placeable.width
                val x = (slot.centerX * density - placeable.width / 2f).coerceIn(left, maxOf(left, maxLeft))
                val y = slot.bottom * density - placeable.height
                placeable.place(x.roundToInt(), y.roundToInt())
            }
        }
    }
}

@Composable
private fun SectionHeader(header: TalentTabletSectionHeader, accent: Color) {
    val colors = talentForgeColors()
    val pointsSpent = pluralStringResource(Res.plurals.section_points_spent, header.points, header.points)
    Row(
        modifier = Modifier
            .testTag(talentTabletSectionHeaderTag(header.index))
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = pointsSpent
            }
            .padding(vertical = TalentTabletSectionHeaderMetrics.VERTICAL_PADDING_DP.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = header.label,
            style = TalentTabletSectionHeaderMetrics.LABEL_STYLE.copy(color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        BasicText(
            text = header.points.toString(),
            // UX/UI spec G: the class accent marks invested points only
            style = TalentTabletSectionHeaderMetrics.POINTS_STYLE.copy(
                color = if (header.points > 0) accent else colors.textMuted,
            ),
            maxLines = 1,
        )
    }
}
