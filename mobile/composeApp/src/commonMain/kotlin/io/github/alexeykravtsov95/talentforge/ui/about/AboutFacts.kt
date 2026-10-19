package io.github.alexeykravtsov95.talentforge.ui.about

import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage

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
 * Public legal links (M5.RC.2b). Since M5.LEGAL.LINK the owner-published
 * privacy-policy addresses are wired in (the hosting decision of LR-5): the
 * Russian and the English page on `legal.mythweld.com`.
 * [privacyPolicyUrlOf] picks the page for the UI language; every configured
 * address stays validated by [privacyPolicyLinkOf].
 */
object LegalLinks {
    /** The published Russian privacy policy — the page a Russian interface opens. */
    const val privacyPolicyUrlRu: String = "https://legal.mythweld.com/privacy.html"

    /** The published English privacy policy — the page every other interface opens. */
    const val privacyPolicyUrlEn: String = "https://legal.mythweld.com/privacy-en.html"
}

/**
 * The privacy-policy page to show for the UI [language] (M5.LEGAL.LINK): the
 * Russian page for a Russian interface, the English page for every other.
 */
fun privacyPolicyUrlOf(language: AppLanguage): String = when (language) {
    AppLanguage.RUSSIAN -> LegalLinks.privacyPolicyUrlRu
    AppLanguage.ENGLISH -> LegalLinks.privacyPolicyUrlEn
}

/**
 * The configured [url] as the privacy-policy link to open: an `https://` URL
 * with a host. Anything else is a configuration error and throws instead of
 * showing a broken link.
 */
fun privacyPolicyLinkOf(url: String): String {
    val host = url.removePrefix("https://").substringBefore('/')
    require(url.startsWith("https://") && host.contains('.') && url.none(Char::isWhitespace)) {
        "the privacy-policy URL must be an https:// address"
    }
    return url
}
