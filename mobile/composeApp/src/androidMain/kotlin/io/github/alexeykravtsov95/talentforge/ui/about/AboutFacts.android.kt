package io.github.alexeykravtsov95.talentforge.ui.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

actual val platformOpenSourceNoticesPath: String? = ANDROID_OSS_NOTICES_PATH

actual val platformBackupStatement: BackupStatement = BackupStatement.AndroidBackupDisabled

actual val platformPrivacyPolicy: PublishedPrivacyPolicy = PublishedPrivacyPolicy.Android

/** The installed package's version, or `null` when the package manager cannot report it. */
fun installedAppVersion(context: Context): AppVersion? = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    info.versionName?.let { AppVersion(name = it, code = code) }
} catch (e: PackageManager.NameNotFoundException) {
    null
}
