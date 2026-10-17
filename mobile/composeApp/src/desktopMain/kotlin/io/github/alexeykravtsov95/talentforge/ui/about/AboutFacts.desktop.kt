package io.github.alexeykravtsov95.talentforge.ui.about

// The desktop target is the development and test host of the Android UI and is not
// distributed: it presents the Android build's notices and backup statement.
actual val platformOpenSourceNoticesPath: String? = ANDROID_OSS_NOTICES_PATH

actual val platformBackupStatement: BackupStatement = BackupStatement.AndroidBackupDisabled
