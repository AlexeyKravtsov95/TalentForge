package io.github.alexeykravtsov95.talentforge.ui.ota

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.AndroidBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.library.BUNDLED_CLIENT_BUILDS
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The OTA client of the Android process (client design §3, §8): `null` — the app as before —
 * unless this build type's Gradle-generated settings enable a complete configuration
 * (`GeneratedOtaSettings.androidProduction` for release builds, `.androidStaging` for
 * debuggable ones, optionally with a staging stand's own TLS root, M5.OTA.4a). One instance
 * per process, so an Activity recreation never starts a second
 * client over the same files; its coroutines outlive the Activity.
 */
object AndroidOta {
    private var instance: OtaRuntime? = null
    private var resolved = false

    @Synchronized
    fun runtime(context: Context): OtaRuntime? {
        if (resolved) return instance
        resolved = true
        val app = context.applicationContext
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val settings = if (debuggable) GeneratedOtaSettings.androidStaging else GeneratedOtaSettings.androidProduction
        val config = (OtaConfig.resolve(settings) as? OtaConfig.Resolution.Enabled)?.config ?: return null
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        instance = OtaRuntime.open(
            config = config,
            // app-private, never cleared by the system, excluded from backups
            store = PlatformOtaFileStore(File(app.noBackupFilesDir, "ota")),
            fetcher = PlatformBlobFetcher(
                userAgent = "TalentForge/$version",
                // a staging stand's own CA (M5.OTA.4a), debuggable builds only; release builds
                // use the platform's roots (Gradle refuses staging settings for them)
                sslSocketFactory = if (debuggable) GeneratedOtaSettings.androidStagingTrustAnchorPem?.let(::pinnedTrustSocketFactory) else null,
            ),
            signatures = PlatformSignatureVerifier(),
            clock = { UtcInstant.ofEpochMillis(System.currentTimeMillis()) },
            bundle = BundleInventory(PRODUCT_BUNDLE, BUNDLED_CLIENT_BUILDS, ComposeResourceDatasetAssetSource()),
            libraryPayload = AndroidBuildLibraryStorage(app)::read,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        return instance
    }
}
