@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.UtcInstant
import io.github.alexeykravtsov95.talentforge.ui.assets.ComposeResourceDatasetAssetSource
import io.github.alexeykravtsov95.talentforge.ui.library.BUNDLED_CLIENT_BUILDS
import io.github.alexeykravtsov95.talentforge.ui.library.IosBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import kotlin.native.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

/**
 * The OTA client of the iOS process (client design §3, §8): `null` — the app exactly as before
 * — unless the iOS settings of this binary enable a complete configuration
 * (`GeneratedOtaSettings.iosProduction` for release binaries, `.iosStaging` for debug ones).
 * iOS is enabled independently of Android and stays off until the iOS release gate passes
 * (test-plan §6C, OD-17). A terminated app restarts through `recover()` like any cold start.
 */
object IosOta {
    private var instance: OtaRuntime? = null
    private var resolved = false

    fun runtime(): OtaRuntime? {
        if (resolved) return instance
        resolved = true
        val settings = if (Platform.isDebugBinary) GeneratedOtaSettings.iosStaging else GeneratedOtaSettings.iosProduction
        val config = (OtaConfig.resolve(settings) as? OtaConfig.Resolution.Enabled)?.config ?: return null
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: return null
        val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"
        instance = OtaRuntime.open(
            config = config,
            store = PlatformOtaFileStore("$support/TalentForge/ota"),
            fetcher = PlatformBlobFetcher(userAgent = "TalentForge/$version"),
            signatures = PlatformSignatureVerifier(),
            clock = { UtcInstant.ofEpochMillis((NSDate().timeIntervalSince1970 * 1000).toLong()) },
            bundle = BundleInventory(PRODUCT_BUNDLE, BUNDLED_CLIENT_BUILDS, ComposeResourceDatasetAssetSource()),
            libraryPayload = IosBuildLibraryStorage()::read,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        return instance
    }
}
