package io.github.alexeykravtsov95.talentforge.data.tooltips

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.GameId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root of a `talent-tooltips.v1` document (docs/schema/talent-tooltips.v1.schema.json):
 * the rendered description of every rank of every talent entry of one class at
 * one client build.
 *
 * A presentation sidecar of `talent-data.v1`, never a part of it. Every
 * property is required on the wire — there are deliberately no Kotlin
 * defaults, so a missing field (including `schemaVersion`) fails decoding.
 * Cross-field rules the JSON Schema cannot express (rank completeness, status
 * consistency, no template markup in text) are verified by
 * [BundledTalentTooltipsRepository].
 */
@Serializable
data class TalentTooltipsDocument(
    val schemaVersion: Int,
    val game: GameId,
    val clientBuild: String,
    val classToken: ClassToken,
    /** `contentHash` of the `talent-data.v1` document these descriptions were rendered for. */
    val talentDataContentHash: ContentHash,
    val entries: List<TalentTooltipEntry>,
    val contentHash: ContentHash,
    val provenance: TalentTooltipsProvenance,
)

/** One talent entry: exactly ranks `1..maxRank`, in order. */
@Serializable
data class TalentTooltipEntry(
    val entryId: Int,
    val definitionId: Int,
    val spellId: Int,
    val maxRank: Int,
    val ranks: List<TalentTooltipRank>,
)

/**
 * One rank's description. `resolved`: [segments] is complete and [gaps] empty;
 * `partial`: each `gap` segment is explained by the [gaps] entry of the same
 * order; `unresolved`: no segments, [gaps] says why.
 */
@Serializable
data class TalentTooltipRank(
    val rank: Int,
    val status: TooltipRankStatus,
    val segments: List<TooltipSegment>,
    val gaps: List<TooltipGap>,
)

@Serializable
enum class TooltipRankStatus {
    @SerialName("resolved")
    RESOLVED,

    @SerialName("partial")
    PARTIAL,

    @SerialName("unresolved")
    UNRESOLVED,
}

/** Plain presentation text — never template markup. A `gap` segment has empty text. */
@Serializable
data class TooltipSegment(
    val kind: TooltipSegmentKind,
    val text: String,
)

@Serializable
enum class TooltipSegmentKind {
    /** Literal template text (may contain `\n` line breaks). */
    @SerialName("text")
    TEXT,

    /** One evaluated value: a number, a duration or a plural word. */
    @SerialName("value")
    VALUE,

    /** A construct that could not be evaluated honestly (see [TalentTooltipRank.gaps]). */
    @SerialName("gap")
    GAP,
}

/** Why one construct of a template is not shown. Diagnostics — never displayed raw. */
@Serializable
data class TooltipGap(
    val category: TooltipTokenCategory,
    val classification: TooltipGapClassification,
    val fragment: String,
    val token: String,
    val reason: String,
)

@Serializable
enum class TooltipGapClassification {
    /** Needs character or client state a planner does not have. */
    @SerialName("contextDependent")
    CONTEXT_DEPENDENT,

    /** The meaning is not proven for this build. */
    @SerialName("unsupported")
    UNSUPPORTED,

    /** A referenced client row does not exist in this build. */
    @SerialName("missingData")
    MISSING_DATA,
}

/** Closed: the syntax categories of the template inventory. */
@Serializable
enum class TooltipTokenCategory {
    @SerialName("local-effect-value")
    LOCAL_EFFECT_VALUE,

    @SerialName("local-periodic-total")
    LOCAL_PERIODIC_TOTAL,

    @SerialName("local-radius")
    LOCAL_RADIUS,

    @SerialName("local-period")
    LOCAL_PERIOD,

    @SerialName("local-duration")
    LOCAL_DURATION,

    @SerialName("local-proc")
    LOCAL_PROC,

    @SerialName("cross-spell-effect-value")
    CROSS_SPELL_EFFECT_VALUE,

    @SerialName("cross-spell-radius")
    CROSS_SPELL_RADIUS,

    @SerialName("cross-spell-period")
    CROSS_SPELL_PERIOD,

    @SerialName("cross-spell-duration")
    CROSS_SPELL_DURATION,

    @SerialName("cross-spell-proc")
    CROSS_SPELL_PROC,

    @SerialName("scaled-value")
    SCALED_VALUE,

    @SerialName("arithmetic")
    ARITHMETIC,

    @SerialName("named-variable")
    NAMED_VARIABLE,

    @SerialName("description-variable")
    DESCRIPTION_VARIABLE,

    @SerialName("plural")
    PLURAL,

    @SerialName("conditional")
    CONDITIONAL,

    @SerialName("nested-tooltip")
    NESTED_TOOLTIP,

    @SerialName("color")
    COLOR,

    @SerialName("line-break")
    LINE_BREAK,

    @SerialName("unclassified")
    UNCLASSIFIED,
}

@Serializable
data class TalentTooltipsProvenance(
    val source: String,
    val tables: List<String>,
    val renderer: String,
)
