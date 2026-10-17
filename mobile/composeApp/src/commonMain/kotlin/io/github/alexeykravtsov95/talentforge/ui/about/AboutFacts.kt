package io.github.alexeykravtsov95.talentforge.ui.about

/** The installed app's version, as the platform package reports it (Android `versionName`/`versionCode`, iOS `CFBundleShortVersionString`/`CFBundleVersion`). */
data class AppVersion(val name: String, val code: Long)

/** Which backup statement is true for this platform's build (M5.RC.2b, TF-RC1-ANDROID-CONFIG). */
enum class BackupStatement {
    /** Android: `allowBackup="false"` plus data-extraction rules that exclude every domain. */
    AndroidBackupDisabled,

    /** iOS: the app's preferences are part of the device's iCloud or computer backup. */
    IosDeviceBackup,
}

/**
 * The bundled open-source notices of THIS platform's distributed binary, or
 * `null` when none are compiled for it. Android ships the generated
 * `oss-notices-android.json`; the iOS framework links a different set — its
 * klibs plus Skia and the third-party code statically linked into Skiko —
 * listed in the generated `oss-notices-ios.json` (TF-RC2B-IOS-NOTICES).
 */
expect val platformOpenSourceNoticesPath: String?

/** The backup statement the About screen shows on this platform. */
expect val platformBackupStatement: BackupStatement

const val ANDROID_OSS_NOTICES_PATH = "files/legal/oss-notices-android.json"
const val IOS_OSS_NOTICES_PATH = "files/legal/oss-notices-ios.json"

/**
 * Public legal links (M5.RC.2b). The privacy-policy URL is an OWNER decision
 * (LR-5: identity, contact, hosting, legal review) and is not approved yet, so
 * it is deliberately `null`: the About screen then shows the in-app privacy
 * summary without any link. Never put a placeholder here — set the approved
 * `https://` address in a reviewed change (validated by [privacyPolicyLinkOf]).
 */
object LegalLinks {
    val privacyPolicyUrl: String? = null
}

/**
 * The privacy-policy link to show for a configured [url]: `null` when none is
 * configured; an `https://` URL with a host otherwise. Anything else is a
 * configuration error and throws instead of showing a broken link.
 */
fun privacyPolicyLinkOf(url: String?): String? {
    if (url == null) return null
    val host = url.removePrefix("https://").substringBefore('/')
    require(url.startsWith("https://") && host.contains('.') && url.none(Char::isWhitespace)) {
        "the privacy-policy URL must be an https:// address"
    }
    return url
}
