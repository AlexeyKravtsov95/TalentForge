package io.github.alexeykravtsov95.talentforge.ui.about

import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M5.LEGAL.LINK on iOS: the published iPhone/iPad policy follows the UI language;
 * the local privacy summary retains the iOS backup statement and notices.
 */
class IosAboutFactsTest {

    @Test
    fun iosOpensItsOwnPublishedPrivacyPolicyInEachUiLanguage() {
        assertEquals(PublishedPrivacyPolicy.Ios, platformPrivacyPolicy)
        assertEquals(
            "https://legal.mythweld.com/privacy-ios.html",
            privacyPolicyUrlOf(platformPrivacyPolicy, AppLanguage.RUSSIAN),
        )
        assertEquals(
            "https://legal.mythweld.com/privacy-ios-en.html",
            privacyPolicyUrlOf(platformPrivacyPolicy, AppLanguage.ENGLISH),
        )
    }

    @Test
    fun iosKeepsItsOwnPrivacySummaryFacts() {
        assertEquals(BackupStatement.IosDeviceBackup, platformBackupStatement)
        assertEquals(IOS_OSS_NOTICES_PATH, platformOpenSourceNoticesPath)
    }
}
