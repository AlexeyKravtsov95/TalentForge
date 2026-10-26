package io.github.alexeykravtsov95.talentforge.ui.about

import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage

/** The installed app's version, as the platform package reports it (Android `versionName`/`versionCode`, iOS `CFBundleShortVersionString`/`CFBundleVersion`). */
data class AppVersion(val name: String, val code: Long)

/**
 * Which backup statement is true for this platform's build (M5.RC.2b, TF-RC1-ANDROID-CONFIG), and
 * with it how saved builds are removed (M5.IOS.1: Android can clear an app's storage, iOS deletes the app)
 * and which talent data updates paragraph matches the platform's published policy (iOS: the App Privacy
 * declaration of retained request logs).
 */
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
 * Which owner-published privacy policy covers THIS platform's distributed
 * build (M5.LEGAL.LINK). Android and iOS have separate owner-published pages;
 * a platform without a published policy shows only the in-app privacy summary.
 */
enum class PublishedPrivacyPolicy {
    /** The Android app's policy: [LegalLinks.androidPrivacyPolicyUrlRu] / [LegalLinks.androidPrivacyPolicyUrlEn]. */
    Android,

    /** The iOS app's policy: [LegalLinks.iosPrivacyPolicyUrlRu] / [LegalLinks.iosPrivacyPolicyUrlEn]. */
    Ios,

    /** No policy is published for this platform: no link, only the in-app summary. */
    NotPublished,
}

/** The published privacy policy that covers this platform's build. */
expect val platformPrivacyPolicy: PublishedPrivacyPolicy

/**
 * Public legal links (M5.RC.2b). The owner publishes separate Android and iOS
 * policies in Russian and English on `legal.mythweld.com` (LR-5).
 * [privacyPolicyUrlOf] picks the page for the platform and the UI language;
 * every configured address stays validated by [privacyPolicyLinkOf].
 */
object LegalLinks {
    /** The published Russian Android privacy policy — the page a Russian interface opens. */
    const val androidPrivacyPolicyUrlRu: String = "https://legal.mythweld.com/privacy.html"

    /** The published English Android privacy policy — the page every other interface opens. */
    const val androidPrivacyPolicyUrlEn: String = "https://legal.mythweld.com/privacy-en.html"

    /** The published Russian policy for iPhone and iPad. */
    const val iosPrivacyPolicyUrlRu: String = "https://legal.mythweld.com/privacy-ios.html"

    /** The published English policy for iPhone and iPad. */
    const val iosPrivacyPolicyUrlEn: String = "https://legal.mythweld.com/privacy-ios-en.html"
}

/**
 * The privacy-policy page to show for [policy] in the UI [language]
 * (M5.LEGAL.LINK): the platform's Russian page for a Russian interface and its
 * English page for every other; `null` when no policy is published for the platform.
 */
fun privacyPolicyUrlOf(policy: PublishedPrivacyPolicy, language: AppLanguage): String? = when (policy) {
    PublishedPrivacyPolicy.Android -> when (language) {
        AppLanguage.RUSSIAN -> LegalLinks.androidPrivacyPolicyUrlRu
        AppLanguage.ENGLISH -> LegalLinks.androidPrivacyPolicyUrlEn
    }
    PublishedPrivacyPolicy.Ios -> when (language) {
        AppLanguage.RUSSIAN -> LegalLinks.iosPrivacyPolicyUrlRu
        AppLanguage.ENGLISH -> LegalLinks.iosPrivacyPolicyUrlEn
    }
    PublishedPrivacyPolicy.NotPublished -> null
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
