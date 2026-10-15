package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.ClassToken
import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.GameId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root of a `talent-localization.v1` document
 * (docs/schema/talent-localization.v1.schema.json, docs/talent-localization.md §3): the
 * client's own text of one locale for every talent entry of one class at one client build.
 *
 * A presentation overlay of `talent-data.v1` + the English `talent-tooltips.v1`, never an
 * input to allocation, validation or saved builds. Every property is required on the wire —
 * there are deliberately no Kotlin defaults, so a missing field (including a nullable one
 * such as [LocalizedName.text]) fails decoding. Unknown fields and enum values are rejected
 * by [io.github.alexeykravtsov95.talentforge.data.TalentDataContract]; the cross-field rules
 * the JSON Schema cannot express (text ⇔ `translated`, ranks ⇔ `rendered`, no template
 * markup …) and the identity/integrity checks are verified by
 * [BundledTalentLocalizationRepository], which applies a document as a whole or not at all.
 */
@Serializable
data class TalentLocalizationDocument(
    val schemaVersion: Int,
    val game: GameId,
    val clientBuild: String,
    val classToken: ClassToken,
    val locale: TalentLocale,
    /** `contentHash` of the exact `talent-data.v1` revision this overlay belongs to. */
    val talentDataContentHash: ContentHash,
    /** `contentHash` of the English `talent-tooltips.v1` the rendered ranks were checked against. */
    val tooltipsContentHash: ContentHash,
    /** One entry per talent entry of the class, ordered by `entryId`. */
    val entries: List<LocalizedTalentEntry>,
    /** sha256 of the canonical JSON of every field except `contentHash` and `provenance`. */
    val contentHash: ContentHash,
    val provenance: LocalizationProvenance,
)

/** Closed: a locale is supported only after its source and text rules were investigated. */
@Serializable
enum class TalentLocale(val code: String) {
    @SerialName("ruRU")
    RU_RU("ruRU"),
    ;

    companion object {
        /** The supported locale of a case-exact code (`ruRU`); `null` for anything else. */
        fun ofCode(code: String): TalentLocale? = entries.firstOrNull { it.code == code }
    }
}

/** One talent entry, keyed by [entryId]; node/definition/spell/maxRank repeat the dataset's. */
@Serializable
data class LocalizedTalentEntry(
    val entryId: Int,
    val nodeId: Int,
    val definitionId: Int,
    val spellId: Int,
    /** `VisibleSpellID` when set, else `SpellID` — the spell whose strings name/describe the entry. */
    val textSpellId: Int,
    val maxRank: Int,
    val name: LocalizedName,
    val description: LocalizedDescription,
)

/** [text] is present exactly when [status] is [LocalizedNameStatus.TRANSLATED] (and has Cyrillic). */
@Serializable
data class LocalizedName(
    val status: LocalizedNameStatus,
    val text: String?,
    val source: LocalizedNameSource,
)

@Serializable
enum class LocalizedNameStatus {
    @SerialName("translated")
    TRANSLATED,

    /** The source served the English string for this locale: the consumer shows English. */
    @SerialName("untranslated")
    UNTRANSLATED,

    /** The locale has no row / an empty string: the consumer shows English. */
    @SerialName("missing")
    MISSING,
}

@Serializable
enum class LocalizedNameSource {
    @SerialName("SpellName.Name_lang")
    SPELL_NAME,

    @SerialName("TraitDefinition.OverrideName_lang")
    TRAIT_DEFINITION_OVERRIDE_NAME,
}

/** [ranks] is exactly `1..maxRank` when [status] is `rendered`, empty for every other status. */
@Serializable
data class LocalizedDescription(
    val status: LocalizedDescriptionStatus,
    val source: LocalizedDescriptionSource,
    val ranks: List<LocalizedRank>,
)

/** The first description gate that failed (docs/talent-localization.md §4), or `rendered`. */
@Serializable
enum class LocalizedDescriptionStatus {
    @SerialName("missing")
    MISSING,

    @SerialName("untranslated")
    UNTRANSLATED,

    @SerialName("sourceNotResolved")
    SOURCE_NOT_RESOLVED,

    @SerialName("pluralRuleUnverified")
    PLURAL_RULE_UNVERIFIED,

    @SerialName("structureMismatch")
    STRUCTURE_MISMATCH,

    @SerialName("renderGap")
    RENDER_GAP,

    @SerialName("decimalFormatUnverified")
    DECIMAL_FORMAT_UNVERIFIED,

    @SerialName("rendered")
    RENDERED,
}

@Serializable
enum class LocalizedDescriptionSource {
    @SerialName("Spell.Description_lang")
    SPELL_DESCRIPTION,

    @SerialName("TraitDefinition.OverrideDescription_lang")
    TRAIT_DEFINITION_OVERRIDE_DESCRIPTION,
}

/** One complete localized rank (never mixed with English fragments). */
@Serializable
data class LocalizedRank(
    val rank: Int,
    val segments: List<LocalizedSegment>,
)

/** Plain presentation text — never template markup, never empty. */
@Serializable
data class LocalizedSegment(
    val kind: LocalizedSegmentKind,
    val text: String,
)

@Serializable
enum class LocalizedSegmentKind {
    /** Literal localized template text. */
    @SerialName("text")
    TEXT,

    /** One evaluated value — the same numbers as the English rank. */
    @SerialName("value")
    VALUE,
}

@Serializable
data class LocalizationProvenance(
    val source: String,
    val renderer: String,
    val tables: List<LocalizationSourceTable>,
)

@Serializable
data class LocalizationSourceTable(
    val table: String,
    val locale: LocalizationTableLocale,
    val url: String,
)

@Serializable
enum class LocalizationTableLocale {
    @SerialName("enUS")
    EN_US,

    @SerialName("ruRU")
    RU_RU,
}
