package io.github.alexeykravtsov95.talentforge.ui.ota

import io.github.alexeykravtsov95.talentforge.data.ota.CanonicalBase64
import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel
import io.github.alexeykravtsov95.talentforge.ui.library.BUNDLED_CLIENT_BUILDS
import io.github.alexeykravtsov95.talentforge.ui.library.PRODUCT_BUNDLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Production safety of the OTA configuration (client design §8, OD-1/OD-2/OD-17, contract §8). */
class OtaConfigTest {

    private val primary = CanonicalBase64.encode(OtaTestRepo.keyring.key("test-primary")!!.spki)
    private val backup = CanonicalBase64.encode(OtaTestRepo.keyring.key("test-backup")!!.spki)

    private fun settings(channel: OtaChannel = OtaChannel.PRODUCTION, baseUrl: String? = "https://ota.example.org", vararg keys: OtaKeySetting) =
        OtaPlatformSettings(enabled = true, channel = channel, baseUrl = baseUrl, keys = keys.toList())

    private fun key(id: String, spki: String) = OtaKeySetting(id, "ecdsa-p256-sha256", spki)

    private fun reason(settings: OtaPlatformSettings) = assertIs<OtaConfig.Resolution.Disabled>(OtaConfig.resolve(settings)).reason

    @Test
    fun everyGeneratedPlatformSettingIsDisabledWithoutHostingOrKeys() {
        val all = listOf(
            GeneratedOtaSettings.androidProduction,
            GeneratedOtaSettings.androidStaging,
            GeneratedOtaSettings.iosProduction,
            GeneratedOtaSettings.iosStaging,
        )
        for (setting in all) {
            assertEquals(false, setting.enabled)
            assertEquals(null, setting.baseUrl, "no placeholder URL can ever be used by a release")
            assertTrue(setting.keys.isEmpty())
            assertEquals(OtaConfig.DisabledReason.NOT_ENABLED, reason(setting))
        }
        assertEquals(OtaChannel.PRODUCTION, GeneratedOtaSettings.androidProduction.channel)
        assertEquals(OtaChannel.STAGING, GeneratedOtaSettings.iosStaging.channel)
    }

    @Test
    fun anEnabledConfigurationMustBeCompleteAndValid() {
        assertEquals(OtaConfig.DisabledReason.BAD_BASE_URL, reason(settings(baseUrl = null, keys = arrayOf(key("tf-prod-a", primary), key("tf-prod-b", backup)))))
        assertEquals(OtaConfig.DisabledReason.BAD_BASE_URL, reason(settings(baseUrl = "http://ota.example.org", keys = arrayOf(key("tf-prod-a", primary), key("tf-prod-b", backup)))))
        assertEquals(OtaConfig.DisabledReason.BAD_BASE_URL, reason(settings(baseUrl = "https://ota.example.org/", keys = arrayOf(key("tf-prod-a", primary)))))
        assertEquals(OtaConfig.DisabledReason.BAD_BASE_URL, reason(settings(baseUrl = "https://ota.example.org?x=1", keys = arrayOf(key("tf-prod-a", primary)))))
        assertEquals(OtaConfig.DisabledReason.NO_KEYS, reason(settings()))
        assertEquals(OtaConfig.DisabledReason.BAD_KEYRING, reason(settings(keys = arrayOf(key("tf-prod-a", "not base64")))))
        assertEquals(OtaConfig.DisabledReason.PRODUCTION_NEEDS_BACKUP_KEY, reason(settings(keys = arrayOf(key("tf-prod-a", primary)))))
        val enabled = assertIs<OtaConfig.Resolution.Enabled>(OtaConfig.resolve(settings(keys = arrayOf(key("tf-prod-a", primary), key("tf-prod-b", backup))))).config
        assertEquals("https://ota.example.org/v1/channels/production/catalog.json", enabled.catalogUrl)
        assertEquals("https://ota.example.org/v1/blobs/sha256/" + "a".repeat(64), enabled.blobUrl("a".repeat(64)))
        // a staging channel may pin one key
        assertIs<OtaConfig.Resolution.Enabled>(OtaConfig.resolve(settings(OtaChannel.STAGING, keys = arrayOf(key("tf-staging-2026", primary)))))
    }

    @Test
    fun theFixtureKeysCanNeverBePinnedByAnApp() {
        for (channel in OtaChannel.entries) {
            assertEquals(
                OtaConfig.DisabledReason.TEST_KEY_IN_APP_KEYRING,
                reason(settings(channel, keys = arrayOf(key("test-primary", primary), key("test-backup", backup)))),
            )
        }
    }

    /** PR #44 review P1-2: a production app never trusts a staging-signed catalog, whatever the staging settings are. */
    @Test
    fun aKeyringPinsOnlyKeysMadeForItsOwnChannel() {
        val prodA = key("tf-prod-2026a", primary)
        val prodB = key("tf-prod-2026b", backup)
        val staging = key("tf-staging-2026", primary)
        // a staging key among production keys: refused although a primary and a backup are pinned
        assertEquals(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL, reason(settings(keys = arrayOf(prodA, prodB, staging))))
        assertEquals(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL, reason(settings(keys = arrayOf(prodA, staging.copy(publicKeySpki = backup)))))
        // only staging keys, or keys of no known purpose, in production
        assertEquals(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL, reason(settings(keys = arrayOf(key("tf-staging-2026a", primary), key("tf-staging-2026b", backup)))))
        assertEquals(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL, reason(settings(keys = arrayOf(key("release-a", primary), key("release-b", backup)))))
        assertEquals(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL, reason(settings(keys = arrayOf(prodA, key("prod-2026b", backup)))))
        // a production key in a staging build is refused too: every key has one purpose
        assertEquals(OtaConfig.DisabledReason.KEY_NOT_FOR_CHANNEL, reason(settings(OtaChannel.STAGING, keys = arrayOf(staging, prodB))))
        // a test- key is reported as such before its channel
        assertEquals(OtaConfig.DisabledReason.TEST_KEY_IN_APP_KEYRING, reason(settings(keys = arrayOf(prodA, prodB, key("test-backup", backup)))))
        // the channel's own keys resolve
        assertIs<OtaConfig.Resolution.Enabled>(OtaConfig.resolve(settings(keys = arrayOf(prodA, prodB))))
        assertIs<OtaConfig.Resolution.Enabled>(OtaConfig.resolve(settings(OtaChannel.STAGING, keys = arrayOf(staging))))
    }

    @Test
    fun theBundledBuildListMatchesTheBundledProduct() {
        assertTrue(PRODUCT_BUNDLE.clientBuild in BUNDLED_CLIENT_BUILDS)
        assertEquals(BUNDLED_CLIENT_BUILDS.maxWith(compareBy { io.github.alexeykravtsov95.talentforge.data.ota.ClientBuildVersion.parse(it) }), PRODUCT_BUNDLE.clientBuild)
        assertEquals(BUNDLED_CLIENT_BUILDS.toList(), BUNDLED_CLIENT_BUILDS.sortedWith(compareBy { io.github.alexeykravtsov95.talentforge.data.ota.ClientBuildVersion.parse(it) }))
    }
}
