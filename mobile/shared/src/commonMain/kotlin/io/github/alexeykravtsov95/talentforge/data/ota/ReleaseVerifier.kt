package io.github.alexeykravtsov95.talentforge.data.ota

import io.github.alexeykravtsov95.talentforge.data.TalentDataContract
import io.github.alexeykravtsov95.talentforge.data.UnsupportedContractVersionException
import io.github.alexeykravtsov95.talentforge.data.manifest.DatasetManifest
import io.github.alexeykravtsov95.talentforge.data.sha256Ref
import io.github.alexeykravtsov95.talentforge.data.talentdata.TalentDataDocument
import kotlinx.serialization.SerializationException

/**
 * Contract §6.3 (`verify_release_manifest`) and §6.4 (`verify_release_files`) — the release
 * half of the reference, in its order, stopping at the first failure.
 *
 * Content files are read by the app's own strict readers ([TalentDataContract]), each after
 * the strict-JSON scan of [JsonDuplicateNames], so a repeated member name in any JSON file of
 * a release is `CONTENT_INVALID` exactly as in the reference.
 */
object ReleaseVerifier {

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** §6.3: the manifest bytes against the verified catalog entry [entry] that pins them. */
    fun verifyManifest(
        manifestBytes: ByteArray,
        entry: CatalogRelease,
        game: String,
        supportedDataLevel: Int,
    ): OtaResult<OtaReleaseManifest> = otaResult {
        val ref = sha256Ref(manifestBytes)
        if (manifestBytes.size.toLong() != entry.manifestByteLength || ref != entry.manifestSha256) {
            reject(
                OtaErrorCode.MANIFEST_HASH_MISMATCH,
                "${manifestBytes.size} bytes $ref, catalog pins ${entry.manifestByteLength} bytes ${entry.manifestSha256}",
            )
        }
        val manifest = OtaDocuments.releaseManifest(manifestBytes)
        val identity = listOf(
            Triple("game", manifest.game, game),
            Triple("clientBuild", manifest.clientBuild, entry.clientBuild),
            Triple("releaseId", manifest.releaseId, entry.releaseId),
            Triple("minDataLevel", manifest.minDataLevel, entry.minDataLevel),
            Triple("totalByteLength", manifest.totalByteLength, entry.totalByteLength),
        )
        for ((name, found, expected) in identity) {
            if (found != expected) reject(OtaErrorCode.RELEASE_IDENTITY_MISMATCH, "$name $found != $expected")
        }
        if (manifest.minDataLevel > supportedDataLevel) {
            reject(
                OtaErrorCode.INCOMPATIBLE_DATA_LEVEL,
                "needs data level ${manifest.minDataLevel}, app supports $supportedDataLevel",
            )
        }
        checkLayout(manifest)
        manifest
    }

    /**
     * Re-reads the `release.json` of a release this client installed earlier (client design
     * §4.1): trusted only because its SHA-256 equals [expectedSha256] — the pin recorded at
     * install time or listed by the last accepted catalog — and still parsed strictly, with
     * its layout re-checked. `null` for anything else (a broken release is quarantined).
     */
    fun readInstalledManifest(bytes: ByteArray, expectedSha256: String, releaseId: String, game: String): OtaReleaseManifest? {
        if (sha256Ref(bytes) != expectedSha256) return null
        return try {
            OtaDocuments.releaseManifest(bytes).also {
                if (it.releaseId != releaseId || it.game != game) return null
                checkLayout(it)
            }
        } catch (_: OtaRejection) {
            null
        }
    }

    /** The only paths a file of [role] may have in a release of ([game], [clientBuild]) (§5.1). */
    fun isValidPath(role: FileRole, path: String, game: String, clientBuild: String): Boolean {
        val g = Regex.escape(game)
        val b = Regex.escape(clientBuild)
        val pattern = when (role) {
            FileRole.DATASET_MANIFEST -> "dataset/$g/$b/manifest\\.json"
            FileRole.TALENT_DATA -> "dataset/$g/$b/$NAME"
            FileRole.VISUALS -> "visuals/$g/$b/$NAME"
            FileRole.ICON_REGISTRY -> "visuals/$g/$b/icons\\.json"
            FileRole.ICON_IMAGE -> "visuals/$g/$b/icons/[1-9][0-9]{0,9}\\.png"
            FileRole.TOOLTIPS -> "tooltips/$g/$b/$NAME"
            FileRole.LOCALIZATION -> "localization/$g/$b/[a-z]{2}[A-Z]{2}/$NAME"
        }
        if (!Regex(pattern).matches(path)) return false
        val name = path.substringAfterLast('/')
        return when (role) {
            FileRole.TALENT_DATA -> name != "manifest.json"
            FileRole.VISUALS -> name != "icons.json"
            else -> true
        }
    }

    private const val NAME = "[a-z][a-z0-9_]{0,31}\\.json"

    private fun checkLayout(manifest: OtaReleaseManifest) {
        val code = OtaErrorCode.LAYOUT_INVALID
        val paths = manifest.files.map { it.path }
        if (paths.size != paths.toSet().size) reject(code, "duplicate path")
        if (paths != paths.sortedWith(UTF8_ORDER)) reject(code, "files are not ordered by path")
        for (file in manifest.files) {
            if (!isValidPath(file.role, file.path, manifest.game, manifest.clientBuild)) {
                reject(code, "${file.path} is not a valid ${file.role} path")
            }
        }
        if (manifest.files.sumOf { it.byteLength } != manifest.totalByteLength) {
            reject(code, "totalByteLength is not the sum of the file sizes")
        }
        for (role in listOf(FileRole.DATASET_MANIFEST, FileRole.ICON_REGISTRY)) {
            if (manifest.files.count { it.role == role } != 1) reject(code, "exactly one $role file is required")
        }
        for (role in listOf(FileRole.TALENT_DATA, FileRole.VISUALS, FileRole.TOOLTIPS, FileRole.LOCALIZATION)) {
            if (manifest.files.none { it.role == role }) reject(code, "no $role file")
        }
    }

    /** Byte order of the UTF-8 encoding (the reference sorts `p.encode("utf-8")`). */
    val UTF8_ORDER: Comparator<String> = Comparator { a, b ->
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }

    /**
     * §6.4: every file of a verified [manifest] — bytes against the manifest, then strict
     * content and the identity/completeness relations of the snapshot. [knownSameBuildManifest]
     * is the dataset manifest of the same (game, clientBuild) the device already trusts
     * (bundled, or an installed release), checked by #7.
     *
     * @return the release's own dataset manifest.
     */
    suspend fun verifyFiles(
        manifest: OtaReleaseManifest,
        read: suspend (ReleaseFile) -> ByteArray,
        knownSameBuildManifest: DatasetManifest?,
    ): OtaResult<DatasetManifest> {
        val blobs = HashMap<String, ByteArray>(manifest.files.size)
        for (file in manifest.files) {
            val data = read(file)
            if (data.size.toLong() != file.byteLength) {
                return OtaResult.Err(
                    OtaFailure.BlobSizeMismatch("${file.path}: ${data.size} bytes, manifest says ${file.byteLength}"),
                )
            }
            val ref = sha256Ref(data)
            if (ref != file.sha256) return OtaResult.Err(OtaFailure.BlobHashMismatch("${file.path}: $ref"))
            blobs[file.path] = data
        }
        return otaResult { SnapshotChecker(manifest, blobs).check(knownSameBuildManifest) }
    }

    /**
     * Same-build identity (§6.4 #7): two datasets of one client build describe the same talent
     * data — otherwise a saved build would silently be re-interpreted against different data.
     */
    fun sameBuildIdentity(new: DatasetManifest, known: DatasetManifest): Boolean {
        require(new.game == known.game && new.clientBuild == known.clientBuild) { "known manifest is of another build" }
        fun fingerprint(m: DatasetManifest) = m.classes.map { listOf(it.token.value, it.file, it.treeIds.sorted(), it.contentHash.value) }
        return fingerprint(new) == fingerprint(known)
    }

    private class SnapshotChecker(private val m: OtaReleaseManifest, private val blobs: Map<String, ByteArray>) {
        private val root = "${m.game}/${m.clientBuild}"

        private fun files(role: FileRole) = m.files.filter { it.role == role }

        private fun <T> parse(path: String, decode: (ByteArray) -> T): T {
            val bytes = blobs.getValue(path)
            val scan = JsonDuplicateNames.scan(bytes)
            if (scan is JsonScan.Rejected) {
                reject(OtaErrorCode.CONTENT_INVALID, "$path: not strict JSON: ${scan.violation} at byte ${scan.offset}")
            }
            return try {
                decode(bytes)
            } catch (e: UnsupportedContractVersionException) {
                reject(OtaErrorCode.CONTENT_INVALID, "$path: ${e.message}")
            } catch (e: SerializationException) {
                reject(OtaErrorCode.CONTENT_INVALID, "$path: ${e.message}")
            } catch (e: IllegalArgumentException) {
                reject(OtaErrorCode.CONTENT_INVALID, "$path: ${e.message}")
            }
        }

        private fun identity(path: String, what: String, found: Any?, expected: Any?) {
            if (found != expected) reject(OtaErrorCode.CONTENT_IDENTITY_MISMATCH, "$path: $what $found != $expected")
        }

        private fun sameFileNames(role: FileRole, prefix: String, expected: Set<String>) {
            val names = files(role).map { it.path.removePrefix(prefix) }.toSet()
            if (names != expected) {
                reject(OtaErrorCode.RELEASE_INCOMPLETE, "$role files ${names.sorted()} != talent data files ${expected.sorted()}")
            }
        }

        fun check(known: DatasetManifest?): DatasetManifest {
            val g = m.game
            val b = m.clientBuild
            // dataset manifest + talent data
            val manifestFile = files(FileRole.DATASET_MANIFEST).single()
            val dataset = parse(manifestFile.path, TalentDataContract::decodeDatasetManifest)
            identity(manifestFile.path, "game", dataset.game.value, g)
            identity(manifestFile.path, "clientBuild", dataset.clientBuild, b)
            for (entry in dataset.classes) {
                if (entry.validation.errors != 0) {
                    reject(OtaErrorCode.CONTENT_INVALID, "${manifestFile.path}: ${entry.token} has validation errors")
                }
            }
            val datasetPrefix = "dataset/$root/"
            val listed = dataset.classes.map { it.file }.toSet()
            if (listed.size != dataset.classes.size) reject(OtaErrorCode.CONTENT_INVALID, "dataset manifest repeats a file")
            sameFileNames(FileRole.TALENT_DATA, datasetPrefix, listed)

            val documents = LinkedHashMap<String, TalentDataDocument>()
            for (entry in dataset.classes) {
                val path = datasetPrefix + entry.file
                val doc = parse(path, TalentDataContract::decodeTalentDataDocument)
                identity(path, "game", doc.game.value, g)
                identity(path, "clientBuild", doc.clientBuild, b)
                identity(path, "class", doc.classInfo.token, entry.token)
                identity(path, "contentHash", doc.contentHash, entry.contentHash)
                identity(path, "treeIds", doc.trees.map { it.id }.sorted(), entry.treeIds.sorted())
                documents[entry.file] = doc
            }
            if (known != null && !sameBuildIdentity(dataset, known)) {
                reject(
                    OtaErrorCode.BUILD_IDENTITY_CONFLICT,
                    "$b: talent data differs from the dataset already trusted for this client build",
                )
            }

            // icon registry + images
            val registryFile = files(FileRole.ICON_REGISTRY).single()
            val visualsPrefix = "visuals/$root/"
            val registry = parse(registryFile.path, TalentDataContract::decodeTalentIconRegistry)
            identity(registryFile.path, "game", registry.game.value, g)
            identity(registryFile.path, "clientBuild", registry.clientBuild, b)
            val images = files(FileRole.ICON_IMAGE).associateBy { it.path }
            val registered = registry.icons.associateBy { visualsPrefix + it.file }
            if (images.keys != registered.keys) {
                reject(OtaErrorCode.RELEASE_INCOMPLETE, "icon images and the icon registry list different files")
            }
            for ((path, icon) in registered) {
                val image = images.getValue(path)
                identity(path, "registry sha256", icon.sha256.value, image.sha256)
                identity(path, "registry byteLength", icon.byteLength.toLong(), image.byteLength)
                if (!blobs.getValue(path).startsWith(PNG_SIGNATURE)) reject(OtaErrorCode.CONTENT_INVALID, "$path: not a PNG file")
            }
            val iconIds = registry.icons.map { it.fileDataId }.toSet()

            // visuals + tooltips sidecars
            sameFileNames(FileRole.VISUALS, visualsPrefix, documents.keys)
            val tooltipsPrefix = "tooltips/$root/"
            sameFileNames(FileRole.TOOLTIPS, tooltipsPrefix, documents.keys)
            val tooltipHashes = HashMap<String, String>()
            for ((name, doc) in documents) {
                val visualsPath = visualsPrefix + name
                val visuals = parse(visualsPath, TalentDataContract::decodeTalentVisualsDocument)
                sidecarIdentity(visualsPath, visuals.game.value, visuals.clientBuild, visuals.classToken.value, visuals.talentDataContentHash.value, doc)
                for (visual in visuals.entries) {
                    if (visual.iconFileDataId !in iconIds) {
                        reject(OtaErrorCode.RELEASE_INCOMPLETE, "$visualsPath: icon ${visual.iconFileDataId} unregistered")
                    }
                }
                val tooltipsPath = tooltipsPrefix + name
                val tooltips = parse(tooltipsPath, TalentDataContract::decodeTalentTooltipsDocument)
                sidecarIdentity(tooltipsPath, tooltips.game.value, tooltips.clientBuild, tooltips.classToken.value, tooltips.talentDataContentHash.value, doc)
                tooltipHashes[name] = tooltips.contentHash.value
            }

            // localization overlays: each locale complete
            val locales = LinkedHashMap<String, MutableSet<String>>()
            val localizationPrefix = "localization/$root/"
            for (file in files(FileRole.LOCALIZATION)) {
                val (locale, name) = file.path.removePrefix(localizationPrefix).split('/')
                locales.getOrPut(locale) { LinkedHashSet() }.add(name)
                val doc = documents[name] ?: reject(OtaErrorCode.RELEASE_INCOMPLETE, "${file.path}: no talent data file $name")
                val overlay = parse(file.path, TalentDataContract::decodeTalentLocalizationDocument)
                sidecarIdentity(file.path, overlay.game.value, overlay.clientBuild, overlay.classToken.value, overlay.talentDataContentHash.value, doc)
                identity(file.path, "locale", overlay.locale.code, locale)
                identity(file.path, "tooltipsContentHash", overlay.tooltipsContentHash.value, tooltipHashes.getValue(name))
            }
            for ((locale, names) in locales) {
                if (names != documents.keys) {
                    reject(OtaErrorCode.RELEASE_INCOMPLETE, "locale $locale lacks ${(documents.keys - names).sorted()}")
                }
            }
            return dataset
        }

        private fun sidecarIdentity(
            path: String,
            game: String,
            clientBuild: String,
            classToken: String,
            talentDataContentHash: String,
            doc: TalentDataDocument,
        ) {
            identity(path, "game", game, m.game)
            identity(path, "clientBuild", clientBuild, m.clientBuild)
            identity(path, "classToken", classToken, doc.classInfo.token.value)
            identity(path, "talentDataContentHash", talentDataContentHash, doc.contentHash.value)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
