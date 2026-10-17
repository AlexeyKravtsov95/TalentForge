package io.github.alexeykravtsov95.talentforge.data.localization

import io.github.alexeykravtsov95.talentforge.data.ContentHash
import io.github.alexeykravtsov95.talentforge.data.DatasetAssetSource
import io.github.alexeykravtsov95.talentforge.data.TestRepoAssets
import io.github.alexeykravtsov95.talentforge.data.tooltips.TooltipRankStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * M5.REL.3b.2: the runtime overlay check against the 45 committed overlays and tampered
 * copies, each of which must be rejected **as a whole** with the typed
 * [LocalizationLoadError] that names its problem. Assets are repository files; no network.
 */
class TalentLocalizationRepositoryTest {

    private val warrior = LocalizationCase(PRODUCT_BUILD, "warrior")

    /** 1.60.1.70374's Warrior overlay, where Gore Drinker's ruRU name is still English (untranslated). */
    private val untranslatedWarrior = LocalizationCase("1.60.1.70374", "warrior")

    // ----------------------------------------------------------------- valid overlays

    @Test
    fun everyClassOfEveryBundledBuildLoadsWhole() = runTest {
        var overlays = 0
        for (build in LOCALIZED_BUILDS) {
            for (token in TestRepoAssets.classTokens) {
                val case = LocalizationCase(build, token)
                val loaded = case.loaded()
                val entryIds = case.document.trees.flatMap { it.nodes }.flatMap { it.entries }.map { it.id }.toSet()
                assertEquals(entryIds, loaded.entryIds, "$build $token: not exactly the dataset's entries")
                assertEquals(case.document.clientBuild, loaded.clientBuild)
                assertEquals(case.document.classInfo.token, loaded.classToken)
                assertEquals(TalentLocale.RU_RU, loaded.locale)
                overlays++
            }
        }
        assertEquals(45, overlays)
    }

    @Test
    fun aTranslatedNameAndEveryRenderedRankAreTheClientsRussian() = runTest {
        val entry = warrior.loaded().entryOf(Warrior.IMPROVED_HEROIC_STRIKE)!!
        assertEquals(LocalizedNameStatus.TRANSLATED, entry.name.status)
        assertEquals("Улучшенный удар героя", entry.name.text)
        assertEquals(LocalizedDescriptionStatus.RENDERED, entry.description.status)
        assertEquals(listOf(1, 2, 3), entry.description.ranks.map { it.rank })
        assertEquals(
            "Снижает затраты ярости на \"Удар героя\" на 1 ед.",
            entry.description.ranks.first().segments.joinToString("") { it.text },
        )
    }

    @Test
    fun entriesSharingASpellKeepTheirOwnIdentities() = runTest {
        // DUPLICATE_SPELL_IDENTITY: Hunter Lightning Reflexes, Priest Holy Specialization
        for ((token, entryIds) in listOf("hunter" to listOf(129646, 137851), "priest" to listOf(130594, 137847))) {
            val case = LocalizationCase(PRODUCT_BUILD, token)
            val loaded = case.loaded()
            val entries = entryIds.map { loaded.entryOf(it)!! }
            assertEquals(1, entries.map { it.spellId }.toSet().size, "$token: one shared spell")
            assertEquals(2, entries.map { it.nodeId }.toSet().size, "$token: two nodes")
            val nodeOf = case.document.trees.flatMap { it.nodes }
                .flatMap { node -> node.entries.map { it.id to node.id } }.toMap()
            entries.forEach { assertEquals(nodeOf.getValue(it.entryId), it.nodeId) }
        }
    }

    // ----------------------------------------------------------------- locale and assets

    @Test
    fun onlyTheCaseExactRussianLocaleIsAnOverlayAndNothingElseIsRead() = runTest {
        for (code in listOf("enUS", "ruru", "RURU", "ru", "deDE", "")) {
            val assets = RepoDataAssets()
            val error = assertIs<LocalizationOutcome.Failed>(warrior.load(assets, localeCode = code)).error
            assertEquals(LocalizationLoadError.UnsupportedLocale(error.path, code), error)
            assertTrue(assets.reads.isEmpty(), "an unsupported locale must not read any asset: ${assets.reads}")
        }
    }

    @Test
    fun theOverlayIsReadFromItsExactBuildLocaleAndClassPath() = runTest {
        val assets = RepoDataAssets()
        warrior.load(assets)
        assertEquals(listOf("localization/forever/$PRODUCT_BUILD/ruRU/warrior.json"), assets.reads)
    }

    @Test
    fun missingMalformedAndStaleOverlaysAreDistinguishable() = runTest {
        val missing = assertIs<LocalizationOutcome.Failed>(warrior.loadBytes(null)).error
        assertEquals(LocalizationLoadError.AssetMissing(warrior.path), missing)

        val malformed = assertIs<LocalizationOutcome.Failed>(warrior.loadBytes("{\"schemaVersion\": 1,".encodeToByteArray())).error
        assertIs<LocalizationLoadError.InvalidJson>(malformed)

        val stale = warrior.errorOf(warrior.json().with("talentDataContentHash", "sha256:" + "0".repeat(64)).rehashed())
        assertIs<LocalizationLoadError.DatasetRevisionMismatch>(stale)

        val unreadable = assertIs<LocalizationOutcome.Failed>(
            warrior.load(RepoDataAssets(unreadable = setOf(warrior.path))),
        ).error
        assertIs<LocalizationLoadError.AssetUnreadable>(unreadable)
    }

    @Test
    fun anotherBuildsOverlayIsNeverSubstituted() = runTest {
        // the 70338 overlay (same strings, other build) served at the 70374 path
        val older = LocalizationCase("1.60.1.70338", "warrior").canonicalBytes
        val error = assertIs<LocalizationOutcome.Failed>(warrior.loadBytes(older)).error
        assertEquals(LocalizationLoadError.BuildMismatch(warrior.path, PRODUCT_BUILD, "1.60.1.70338"), error)
    }

    @Test
    fun anotherClassesOverlayIsNeverSubstituted() = runTest {
        val mage = LocalizationCase(PRODUCT_BUILD, "mage").canonicalBytes
        val error = assertIs<LocalizationOutcome.Failed>(warrior.loadBytes(mage)).error
        assertIs<LocalizationLoadError.ClassMismatch>(error)
    }

    @Test
    fun anotherGameIsRejected() = runTest {
        assertIs<LocalizationLoadError.GameMismatch>(warrior.errorOf(warrior.json().with("game", "classic").rehashed()))
    }

    // ----------------------------------------------------------------- strict decoding

    @Test
    fun strictDecodingRejectsEveryWireViolation() = runTest {
        val json = warrior.json()
        val first = json.entries().first().id()
        val cases = mapOf(
            "unknown top-level field" to json.with("note", "x"),
            "unknown entry field" to json.withEntry(first) { it.with("visibleSpellId", 1) },
            "unknown name status" to json.withEntry(first) { it.with("name", it.obj("name").with("status", "partial")) },
            "unknown description status" to json.withEntry(first) {
                it.with("description", it.obj("description").with("status", "approximate"))
            },
            "unknown segment kind" to json.withEntry(Warrior.IMPROVED_HEROIC_STRIKE) { entry ->
                val description = entry.obj("description")
                val ranks = description.getValue("ranks").jsonArray.map { it.jsonObject }
                val segments = ranks[0].getValue("segments").jsonArray.map { it.jsonObject }
                val edited = ranks[0].with("segments", JsonArray(listOf(segments[0].with("kind", "gap")) + segments.drop(1)))
                entry.with("description", description.with("ranks", JsonArray(listOf(edited) + ranks.drop(1))))
            },
            "unknown locale" to json.with("locale", "deDE"),
            "missing mandatory field" to json.withEntry(first) { it.without("textSpellId") },
            "missing nullable field" to json.withEntry(Warrior.GORE_DRINKER) { it.with("name", it.obj("name").without("text")) },
            "missing provenance" to json.without("provenance"),
            "quoted number" to json.withEntry(first) { it.with("maxRank", JsonPrimitive("${it.getValue("maxRank")}")) },
            "boolean for a number" to json.withEntry(first) { it.with("nodeId", JsonPrimitive(true)) },
            "fraction for an integer" to json.withEntry(first) { it.with("spellId", JsonPrimitive(1.5)) },
            "null for a required string" to json.with("game", JsonNull),
            "number for a string" to json.with("clientBuild", JsonPrimitive(1)),
            "empty game" to json.with("game", ""),
        )
        // decoding rejects these before any hash is looked at, so none needs a valid hash
        for ((label, tampered) in cases) {
            assertIs<LocalizationLoadError.InvalidJson>(warrior.errorOf(tampered), label)
        }
    }

    @Test
    fun anUnsupportedSchemaVersionIsTyped() = runTest {
        val error = warrior.errorOf(warrior.json().with("schemaVersion", 2).rehashed())
        assertIs<LocalizationLoadError.UnsupportedSchemaVersion>(error)
        assertEquals(2, error.actualVersion)
    }

    @Test
    fun schemaPatternsAndRangesAreEnforced() = runTest {
        val json = warrior.json()
        val first = json.entries().first().id()
        assertIs<LocalizationLoadError.InvalidValue>(warrior.errorOf(json.with("clientBuild", "1.60.1").rehashed()))
        assertIs<LocalizationLoadError.InvalidValue>(warrior.errorOf(json.with("classToken", "Warrior").rehashed()))
        assertIs<LocalizationLoadError.InvalidValue>(warrior.errorOf(json.with("tooltipsContentHash", "sha256:XYZ").rehashed()))
        assertIs<LocalizationLoadError.InvalidValue>(warrior.errorOf(json.with("contentHash", "md5:0")))
        assertIs<LocalizationLoadError.InvalidValue>(warrior.errorOf(json.withEntry(first) { it.with("textSpellId", 0) }.rehashed()))
    }

    // ----------------------------------------------------------------- linkage and integrity

    @Test
    fun aStaleDatasetHashIsRejected() = runTest {
        val error = warrior.errorOf(warrior.json().with("talentDataContentHash", "sha256:" + "0".repeat(64)).rehashed())
        assertEquals(
            LocalizationLoadError.DatasetRevisionMismatch(
                warrior.path,
                expected = warrior.document.contentHash,
                actual = ContentHash("sha256:" + "0".repeat(64)),
            ),
            error,
        )
    }

    @Test
    fun aStaleEnglishTooltipsHashIsRejected() = runTest {
        val error = warrior.errorOf(warrior.json().with("tooltipsContentHash", "sha256:" + "1".repeat(64)).rehashed())
        assertIs<LocalizationLoadError.TooltipsRevisionMismatch>(error)
        assertEquals(warrior.tooltips().contentHash, error.expected)
    }

    @Test
    fun tamperedContentWithoutANewHashIsRejected() = runTest {
        val json = warrior.json()
        val renamed = json.withEntry(Warrior.IMPROVED_HEROIC_STRIKE) { it.with("name", it.obj("name").with("text", "Подделка")) }
        val error = warrior.errorOf(renamed)
        assertIs<LocalizationLoadError.ContentHashMismatch>(error)
        assertEquals(ContentHash(json.getValue("contentHash").toString().trim('"')), error.actual)
    }

    @Test
    fun theSelfReportedHashIsRecomputedNeverTrusted() = runTest {
        val json = warrior.json()
        // another syntactically valid hash: the published one of the mage overlay
        val foreign = LocalizationCase(PRODUCT_BUILD, "mage").json().getValue("contentHash").toString().trim('"')
        val error = warrior.errorOf(json.with("contentHash", foreign))
        assertIs<LocalizationLoadError.ContentHashMismatch>(error)
        assertEquals(json.getValue("contentHash").toString().trim('"'), error.expected.value, "recomputed = Python's")
    }

    @Test
    fun englishInputsOfAnotherClassAreRejected() = runTest {
        val mage = LocalizationCase(PRODUCT_BUILD, "mage")
        val outcome = BundledTalentLocalizationRepository(RepoDataAssets())
            .localization(warrior.document, mage.tooltips(), warrior.visuals(), "ruRU")
        assertEquals(
            LocalizationLoadError.EnglishInputMismatch(warrior.path, EnglishInput.TOOLTIPS),
            assertIs<LocalizationOutcome.Failed>(outcome).error,
        )
        val visuals = BundledTalentLocalizationRepository(RepoDataAssets())
            .localization(warrior.document, warrior.tooltips(), mage.visuals(), "ruRU")
        assertEquals(
            LocalizationLoadError.EnglishInputMismatch(warrior.path, EnglishInput.VISUALS),
            assertIs<LocalizationOutcome.Failed>(visuals).error,
        )
    }

    // ----------------------------------------------------------------- the entry set

    @Test
    fun duplicateMissingUnknownAndUnorderedEntriesAreRejected() = runTest {
        val json = warrior.json()
        val entries = json.entries()
        assertEquals(
            LocalizationLoadError.DuplicateEntry(warrior.path, entries[0].id()),
            warrior.errorOf(json.withEntries { listOf(it[0]) + it }.rehashed()),
        )
        assertEquals(
            LocalizationLoadError.MissingEntry(warrior.path, entries[0].id()),
            warrior.errorOf(json.withEntries { it.drop(1) }.rehashed()),
        )
        assertEquals(
            LocalizationLoadError.UnknownEntry(warrior.path, 999_999_999),
            warrior.errorOf(json.withEntries { it + it.last().with("entryId", 999_999_999) }.rehashed()),
        )
        assertEquals(
            LocalizationLoadError.EntriesNotOrdered(warrior.path, entries[0].id()),
            warrior.errorOf(json.withEntries { listOf(it[1], it[0]) + it.drop(2) }.rehashed()),
        )
    }

    @Test
    fun everyIdentityFieldMustBeTheDatasets() = runTest {
        val json = warrior.json()
        val entry = json.entries().first { it.id() == Warrior.IMPROVED_HEROIC_STRIKE }
        for (field in listOf("nodeId", "definitionId", "spellId", "textSpellId", "maxRank")) {
            val actual = entry.getValue(field).toString().toInt() + 1
            val error = warrior.errorOf(json.withEntry(Warrior.IMPROVED_HEROIC_STRIKE) { it.with(field, actual) }.rehashed())
            assertIs<LocalizationLoadError.EntryIdentityMismatch>(error, field)
            assertEquals(field, error.field)
            assertEquals(actual, error.actual)
        }
    }

    @Test
    fun aWrongTextSpellOfADuplicateSpellIdentityIsRejected() = runTest {
        val hunter = LocalizationCase(PRODUCT_BUILD, "hunter")
        val error = hunter.errorOf(hunter.json().withEntry(137851) { it.with("textSpellId", 1) }.rehashed())
        assertEquals(LocalizationLoadError.EntryIdentityMismatch(hunter.path, 137851, "textSpellId", 19168, 1), error)
    }

    // ----------------------------------------------------------------- names and ranks

    @Test
    fun nameStatusAndTextMustAgree() = runTest {
        val warrior = untranslatedWarrior
        val json = warrior.json()
        fun name(entryId: Int, change: (JsonObject) -> JsonObject) =
            json.withEntry(entryId) { it.with("name", change(it.obj("name"))) }.rehashed()
        val cases = listOf(
            name(Warrior.IMPROVED_HEROIC_STRIKE) { it.with("text", JsonNull) },
            name(Warrior.IMPROVED_HEROIC_STRIKE) { it.with("text", "") },
            name(Warrior.IMPROVED_HEROIC_STRIKE) { it.with("text", "Improved Heroic Strike") },
            name(Warrior.GORE_DRINKER) { it.with("text", "Пожиратель крови") },
            name(Warrior.GORE_DRINKER) { it.with("status", "missing").with("text", "Gore Drinker") },
        )
        for (tampered in cases) assertIs<LocalizationLoadError.InvalidName>(warrior.errorOf(tampered))
    }

    @Test
    fun renderedRanksMustBeExactlyOneToMaxRankAndOthersHaveNone() = runTest {
        val json = warrior.json()
        fun ranks(entryId: Int, change: (List<JsonObject>) -> List<JsonObject>) = json.withEntry(entryId) { entry ->
            val description = entry.obj("description")
            entry.with(
                "description",
                description.with("ranks", JsonArray(change(description.getValue("ranks").jsonArray.map { it.jsonObject }))),
            )
        }.rehashed()
        val incomplete = warrior.errorOf(ranks(Warrior.IMPROVED_HEROIC_STRIKE) { it.dropLast(1) })
        assertEquals(
            LocalizationLoadError.InvalidRanks(warrior.path, Warrior.IMPROVED_HEROIC_STRIKE, LocalizedDescriptionStatus.RENDERED, listOf(1, 2)),
            incomplete,
        )
        val duplicate = warrior.errorOf(ranks(Warrior.IMPROVED_HEROIC_STRIKE) { listOf(it[0], it[0], it[2]) })
        assertEquals(listOf(1, 1, 3), assertIs<LocalizationLoadError.InvalidRanks>(duplicate).ranks)
        val unordered = warrior.errorOf(ranks(Warrior.IMPROVED_HEROIC_STRIKE) { listOf(it[1], it[0], it[2]) })
        assertIs<LocalizationLoadError.InvalidRanks>(unordered)
        val onAFallback = warrior.errorOf(ranks(Warrior.BLOODTHIRST) { listOf(rankJson(1, "text" to "Кровь")) })
        assertEquals(LocalizedDescriptionStatus.SOURCE_NOT_RESOLVED, assertIs<LocalizationLoadError.InvalidRanks>(onAFallback).status)
    }

    @Test
    fun segmentsCarryNoTemplateMarkupAndAreNeverEmpty() = runTest {
        val json = warrior.json()
        fun firstRank(vararg segments: Pair<String, String>) = json.withEntry(Warrior.IMPROVED_HEROIC_STRIKE) { entry ->
            val description = entry.obj("description")
            val ranks = description.getValue("ranks").jsonArray.map { it.jsonObject }
            entry.with("description", description.with("ranks", JsonArray(listOf(rankJson(1, *segments)) + ranks.drop(1))))
        }.rehashed()
        val cases = listOf(
            firstRank("text" to "Снижает затраты на \$s1 ед.", "value" to "1"),
            firstRank("text" to "|cFFFFFFFFСнижает|r на ", "value" to "1"),
            firstRank("text" to "Снижает\r\nна ", "value" to "1"),
            firstRank("text" to "", "value" to "1"),
            firstRank(),
        )
        for (tampered in cases) {
            assertEquals(
                LocalizationLoadError.InvalidSegment(warrior.path, Warrior.IMPROVED_HEROIC_STRIKE, 1),
                warrior.errorOf(tampered),
            )
        }
    }

    // ----------------------------------------------------------------- numbers

    @Test
    fun aModifiedNumberIsRejected() = runTest {
        val tampered = warrior.json().withEntry(Warrior.IMPROVED_HEROIC_STRIKE) { entry ->
            val description = entry.obj("description")
            val ranks = description.getValue("ranks").jsonArray.map { it.jsonObject }
            val changed = rankJson(1, "text" to "Снижает затраты ярости на \"Удар героя\" на ", "value" to "9", "text" to " ед.")
            entry.with("description", description.with("ranks", JsonArray(listOf(changed) + ranks.drop(1))))
        }.rehashed()
        assertEquals(
            LocalizationLoadError.NumberMismatch(warrior.path, Warrior.IMPROVED_HEROIC_STRIKE, 1, listOf("9"), listOf("1")),
            warrior.errorOf(tampered),
        )
    }

    @Test
    fun anUnverifiedDecimalIsRejectedEvenWithTheEnglishNumbers() = runTest {
        // Improved Shield Wall: English "5.5"/"11.0" min — copy the English numbers into a
        // "rendered" Russian rank: same numbers, but the ruRU decimal separator is unproven
        val tooltips = warrior.tooltips()
        val tampered = warrior.json().withEntry(Warrior.IMPROVED_SHIELD_WALL) { entry ->
            val ranks = (1..2).map { rank ->
                val english = tooltips.rankOf(Warrior.IMPROVED_SHIELD_WALL, rank)!!
                assertEquals(TooltipRankStatus.RESOLVED, english.status)
                val value = english.segments.single { it.kind.name == "VALUE" }.text
                rankJson(rank, "text" to "Сокращает время восстановления на ", "value" to value, "text" to " мин.")
            }
            entry.with(
                "description",
                entry.obj("description").with("status", "rendered").with("ranks", JsonArray(ranks)),
            )
        }.rehashed()
        assertEquals(
            LocalizationLoadError.DecimalValue(warrior.path, Warrior.IMPROVED_SHIELD_WALL, 1),
            warrior.errorOf(tampered),
        )
    }

    @Test
    fun aRenderedRankOverAPartialEnglishRankIsRejected() = runTest {
        val tampered = warrior.json().withEntry(Warrior.BLOODTHIRST) { entry ->
            entry.with(
                "description",
                entry.obj("description").with("status", "rendered").with(
                    "ranks",
                    JsonArray(listOf(rankJson(1, "text" to "Мгновенная атака на ", "value" to "45%"))),
                ),
            )
        }.rehashed()
        assertEquals(
            LocalizationLoadError.EnglishRankNotResolved(warrior.path, Warrior.BLOODTHIRST, 1),
            warrior.errorOf(tampered),
        )
    }

    // ----------------------------------------------------------------- cancellation

    @Test
    fun cancellationPropagatesAndIsNeverATypedFailure() = runTest {
        val cancelling = object : DatasetAssetSource {
            override suspend fun read(path: String): ByteArray = throw CancellationException("editor closed")
        }
        assertFailsWith<CancellationException> { warrior.load(cancelling) }
    }

    @Test
    fun theCommittedOverlayIsUnchangedByLoading() = runTest {
        // the published contentHash is the Python producer's: recomputing it here agrees
        val json = warrior.json()
        assertNotEquals(0, json.entries().size)
        assertEquals(json.getValue("contentHash").toString().trim('"'), json.rehashed().getValue("contentHash").toString().trim('"'))
    }
}
