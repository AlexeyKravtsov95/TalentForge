package io.github.alexeykravtsov95.talentforge.ui.about

import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M5.LEGAL.LINK on iOS: the published privacy policy is the Android app's, so the iOS build
 * offers no policy link in any UI language and keeps the in-app summary with its own backup
 * statement and notices.
 */
class IosAboutFactsTest {

    @Test
    fun iosHasNoPublishedPrivacyPolicyAndNoLinkInAnyLanguage() {
        assertEquals(PublishedPrivacyPolicy.NotPublished, platformPrivacyPolicy)
        for (language in AppLanguage.entries) {
            assertNull(privacyPolicyUrlOf(platformPrivacyPolicy, language))
        }
    }

    @Test
    fun iosKeepsItsOwnPrivacySummaryFacts() {
        assertEquals(BackupStatement.IosDeviceBackup, platformBackupStatement)
        assertEquals(IOS_OSS_NOTICES_PATH, platformOpenSourceNoticesPath)
    }
}
