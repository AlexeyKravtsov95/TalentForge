package io.github.alexeykravtsov95.talentforge.ui.library

import androidx.compose.runtime.Composable
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.Res
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_druid
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_hunter
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_mage
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_other_class
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_paladin
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_priest
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_rogue
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_shaman
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_warlock
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.build_title_warrior
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_druid
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_hunter
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_mage
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_paladin
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_priest
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_rogue
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_shaman
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_warlock
import io.github.alexeykravtsov95.talentforge.composeapp.generated.resources.class_name_warrior
import io.github.alexeykravtsov95.talentforge.data.ClassToken
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * M5.REL.2a: the localized class and build-title copy of the shell screens,
 * keyed by the stable [ClassToken] — presentation only. Datasets, saved
 * builds and their user-given names are never rewritten: the dataset's
 * class name stays the "is this class resolved" fact, a user name always
 * wins, and nothing here is persisted.
 */
private class ClassCopy(val name: StringResource, val unnamedBuildTitle: StringResource)

private val classCopy: Map<String, ClassCopy> = mapOf(
    "WARRIOR" to ClassCopy(Res.string.class_name_warrior, Res.string.build_title_warrior),
    "PALADIN" to ClassCopy(Res.string.class_name_paladin, Res.string.build_title_paladin),
    "HUNTER" to ClassCopy(Res.string.class_name_hunter, Res.string.build_title_hunter),
    "ROGUE" to ClassCopy(Res.string.class_name_rogue, Res.string.build_title_rogue),
    "PRIEST" to ClassCopy(Res.string.class_name_priest, Res.string.build_title_priest),
    "SHAMAN" to ClassCopy(Res.string.class_name_shaman, Res.string.build_title_shaman),
    "MAGE" to ClassCopy(Res.string.class_name_mage, Res.string.build_title_mage),
    "WARLOCK" to ClassCopy(Res.string.class_name_warlock, Res.string.build_title_warlock),
    "DRUID" to ClassCopy(Res.string.class_name_druid, Res.string.build_title_druid),
)

/** The class-name resource of [classToken]; `null` for a token outside the nine shipped classes. */
fun classNameResourceFor(classToken: ClassToken): StringResource? = classCopy[classToken.value]?.name

/**
 * The localized display name of a RESOLVED class: [datasetName] is the
 * class document's own name, `null` when the document did not load — then
 * this is `null` too (the caller shows the honest token, never an invented
 * name). A resolved token outside the shipped table keeps the dataset name.
 */
@Composable
fun localizedClassName(classToken: ClassToken, datasetName: String?): String? {
    if (datasetName == null) return null
    val resource = classNameResourceFor(classToken) ?: return datasetName
    return stringResource(resource)
}

/**
 * A build card's DISPLAY title: a user-provided name always wins, verbatim
 * (an explicitly named `Draft` included); an unnamed build shows the
 * localized `<Class> build` fallback (`Mage build` / `Билд мага`). Derived,
 * never persisted — `record.name` stays `null` in storage, and the rename
 * dialog still opens with an empty field. A token outside the shipped
 * table uses the generic form with the dataset name or, unresolved, the
 * token capitalized.
 */
@Composable
fun buildCardTitle(card: BuildCardModel): String {
    card.record.name?.let { return it }
    val token = card.record.build.classToken
    classCopy[token.value]?.let { return stringResource(it.unnamedBuildTitle) }
    val className = card.classDisplayName ?: token.value.lowercase().replaceFirstChar { it.uppercase() }
    return stringResource(Res.string.build_title_other_class, className)
}
