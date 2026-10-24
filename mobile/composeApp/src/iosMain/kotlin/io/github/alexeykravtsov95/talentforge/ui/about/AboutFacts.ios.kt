package io.github.alexeykravtsov95.talentforge.ui.about

import platform.Foundation.NSBundle

// The iOS framework's own notices: its klibs plus the Skia and third-party code
// statically linked into Skiko (TF-RC2B-IOS-NOTICES), never the Android list.
actual val platformOpenSourceNoticesPath: String? = IOS_OSS_NOTICES_PATH

actual val platformBackupStatement: BackupStatement = BackupStatement.IosDeviceBackup

// The owner published the iPhone/iPad policy in RU and EN on 2026-10-24.
// About opens the iOS page matching its UI language (M5.LEGAL.LINK).
actual val platformPrivacyPolicy: PublishedPrivacyPolicy = PublishedPrivacyPolicy.Ios

/** The bundle's marketing version and build number, or `null` when either is missing. */
fun bundleAppVersion(): AppVersion? {
    val bundle = NSBundle.mainBundle
    val name = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    val code = (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull()
    return if (name.isNullOrBlank() || code == null) null else AppVersion(name = name, code = code)
}
