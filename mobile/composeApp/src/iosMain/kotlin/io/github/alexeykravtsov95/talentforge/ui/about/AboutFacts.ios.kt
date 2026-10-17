package io.github.alexeykravtsov95.talentforge.ui.about

import platform.Foundation.NSBundle

// The iOS framework statically links Skiko/Skia: its notices are not compiled yet
// (TF-RC2B-IOS-NOTICES), so no list is shown rather than the Android one.
actual val platformOpenSourceNoticesPath: String? = null

actual val platformBackupStatement: BackupStatement = BackupStatement.IosDeviceBackup

/** The bundle's marketing version and build number, or `null` when either is missing. */
fun bundleAppVersion(): AppVersion? {
    val bundle = NSBundle.mainBundle
    val name = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    val code = (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull()
    return if (name.isNullOrBlank() || code == null) null else AppVersion(name = name, code = code)
}
