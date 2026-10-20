package io.github.alexeykravtsov95.talentforge.ui.about

import io.github.alexeykravtsov95.talentforge.ui.lang.AppLanguage
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * LR-4: the bundled Android (M5.RC.2b; its stdlib's ThreeTen licence by
 * M5.LEGAL.OSS.1) and iOS (TF-RC2B-IOS-NOTICES) notices, read through the same
 * resource API the About screen uses, every bundled licence text byte for byte,
 * and the strict parser's fail-closed rules. That the lists equal what the
 * binaries ship is checked by `scripts/generate_oss_notices.py --check` (it needs
 * Gradle and the klibs).
 */
class OpenSourceNoticesTest {

    private val android: OpenSourceNotices = runBlocking { loadOpenSourceNotices(ANDROID_OSS_NOTICES_PATH) }
    private val ios: OpenSourceNotices = runBlocking { loadOpenSourceNotices(IOS_OSS_NOTICES_PATH) }

    private fun OpenSourceNotices.modules() = components.filterIsInstance<NoticeComponent.Packaged>().flatMap { it.modules }

    private fun OpenSourceNotices.linked() = components.filterIsInstance<NoticeComponent.Linked>()

    @Test
    fun theBundledAndroidNoticesParseStrictly() {
        assertEquals("android", android.platform)
        assertEquals(setOf("Apache-2.0", "ThreeTen-BSD-3-Clause"), android.licenses.keys)
        assertEquals(
            listOf(
                "AndroidX (Android Jetpack)",
                "Compose Multiplatform",
                "Kotlin Standard Library",
                "kotlinx.coroutines",
                "kotlinx.serialization",
                "JetBrains Java Annotations",
                "JSpecify",
                "Guava ListenableFuture",
            ),
            android.components.map { it.name },
        )
        // the stdlib jar embeds ThreeTen-derived kotlin.time (M5.LEGAL.OSS.1): its
        // BSD-3-Clause notice is listed next to the POM's Apache-2.0
        assertEquals(
            listOf("Apache-2.0", "ThreeTen-BSD-3-Clause"),
            android.components.single { it.name == "Kotlin Standard Library" }.licenseIds,
        )
        assertTrue(android.linked().isEmpty(), "the APK links no native third-party code of its own")
        val modules = android.modules()
        assertEquals(modules.size, android.moduleCount)
        for (module in listOf(
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.20",
            "androidx.activity:activity-compose:1.13.0",
            "org.jetbrains.compose.components:components-resources:1.10.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0",
            "com.google.guava:listenablefuture:1.0",
        )) {
            assertTrue(module in modules, "$module is shipped by the release APK but not listed")
        }
        assertTrue(modules.none { it.split(':')[1].endsWith("-bom") }, "BOMs ship no code and are not listed")
    }

    @Test
    fun theBundledIosNoticesListTheKlibsAndTheCodeLinkedIntoSkiko() {
        assertEquals("ios", ios.platform)
        val modules = ios.modules()
        for (module in listOf(
            "org.jetbrains.skiko:skiko-iosarm64:0.9.37.4",
            "org.jetbrains.compose.ui:ui-uikit-uikitarm64:1.10.3",
            "org.jetbrains.kotlin:kotlin-stdlib:2.4.20",
            "org.jetbrains.kotlinx:atomicfu-iosarm64:0.27.0",
        )) {
            assertTrue(module in modules, "$module is linked into the iOS framework but not listed")
        }
        assertTrue(modules.none { it.startsWith("androidx.activity:") }, "Android-only modules are not in the iOS list")
        assertEquals(
            listOf("Apache-2.0", "Skiko-NOTICE"),
            ios.components.single { it.name == "Skiko" }.licenseIds,
        )
        val skiko = "org.jetbrains.skiko:skiko-iosarm64:0.9.37.4"
        assertEquals(
            mapOf(
                "Skia" to listOf("Skia-BSD-3-Clause"),
                "HarfBuzz" to listOf("HarfBuzz-Old-MIT"),
                "ICU (International Components for Unicode)" to listOf("ICU"),
                "libjpeg-turbo" to listOf("libjpeg-turbo", "IJG"),
                "libpng" to listOf("libpng-2.0"),
                "libwebp" to listOf("libwebp-BSD-3-Clause", "libwebp-PATENTS"),
                "Wuffs" to listOf("Apache-2.0"),
                "zlib (Chromium)" to listOf("Zlib", "Chromium-BSD-3-Clause"),
                "Expat" to listOf("Expat-MIT"),
                "Adobe DNG SDK" to listOf("DNG-SDK", "DNG-PATENTS"),
                "Piex" to listOf("Apache-2.0"),
            ),
            ios.linked().filter { it.linkedInto == skiko }.associate { it.name to it.licenseIds },
        )
        val stdlib = ios.linked().single { it.linkedInto == "org.jetbrains.kotlin:kotlin-stdlib:2.4.20" }
        assertEquals(listOf("ThreeTen-BSD-3-Clause", "Apache-2.0"), stdlib.licenseIds)
        // statements the IJG licence and Adobe's DNG patent licence require, verbatim
        assertEquals(
            mapOf(
                "libjpeg-turbo" to "This software is based in part on the work of the Independent JPEG Group.",
                "Adobe DNG SDK" to "This product includes DNG technology under license by Adobe Systems Incorporated.",
            ),
            ios.linked().filter { it.attribution != null }.associate { it.name to it.attribution },
        )
    }

    @Test
    fun everyBundledLicenceTextIsThePinnedUpstreamFile() {
        // SHA-256 of the verbatim upstream files at the revisions pinned in generate_oss_notices.py
        val pinned = mapOf(
            "Apache-2.0" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            "Skiko-NOTICE" to "35dd077c24768b53625101cfcc0ad853ae7e2abc08bd2a111a0267760d1406d8",
            "Skia-BSD-3-Clause" to "5f787c1dee3c56547f09ccc2906ab5f5293c4d8dd6c8654e573216c38e908dbd",
            "HarfBuzz-Old-MIT" to "ba8f810f2455c2f08e2d56bb49b72f37fcf68f1f4fade38977cfd7372050ad64",
            "ICU" to "17510cf7a58b4879b887ec05a45d72cf1b73544dd9ec7e72f20110ed104229ee",
            "libjpeg-turbo" to "96f5b328adbb78eeaaec6980d73fd558cb1e4d62560ed615646bc3cf5e532430",
            "IJG" to "75815e3bf6484201a3c3d17a1bbf10f2e8e3237f84df10a2357ea896db2a81d6",
            "libpng-2.0" to "7317e078e2d3b5d7ba5a6159e650945153262b44b76f6700f8e9edb261c5143e",
            "libwebp-BSD-3-Clause" to "5aec868f669e384a22372a4e8a1a6cd7d44c64cd451f960ca69cc170d1e13acf",
            "libwebp-PATENTS" to "cc3273e0694ea5896145e0677699b53471b03ea43021ddc50e7923fbb9f5023c",
            "Zlib" to "e1cfcc55c325b3f78cf55df9664abaa066e2271dffe8213347d9fccdfbac8f2c",
            "Chromium-BSD-3-Clause" to "368cca1106be99d39ecd32a38d8305585d802a475effb66380b91ffc9bcf709b",
            "Expat-MIT" to "31b15de82aa19a845156169a17a5488bf597e561b2c318d159ed583139b25e87",
            "DNG-SDK" to "012e875468ac2f9a2070ee25a950588afa2735b4b2d961f491e3d72c8f572306",
            "DNG-PATENTS" to "bd958835863f42d8479457503a7ed716424d35b6e76ef76db06b72b109696ec4",
            "ThreeTen-BSD-3-Clause" to "d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331",
        )
        val declared = android.licenses + ios.licenses
        assertEquals(pinned.keys, declared.keys)
        for ((id, license) in declared) {
            val bytes = runBlocking { readLegalResource(license.textPath) }.encodeToByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(pinned.getValue(id), digest, "${license.textPath} is not the pinned upstream text")
        }
        val apache = runBlocking { readLegalResource(android.licenses.getValue("Apache-2.0").textPath) }
        assertTrue("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION" in apache)
    }

    @Test
    fun aMissingResourceIsATypedFailure() {
        assertFailsWith<OpenSourceNoticesException> {
            runBlocking { loadOpenSourceNotices("files/legal/absent.json") }
        }
    }

    private val apache = """"Apache-2.0": {"name": "Apache License 2.0", "file": "files/legal/LICENSE-Apache-2.0.txt"}"""
    private val module = """{"name": "X", "url": "https://example.org", "licenses": ["Apache-2.0"], "modules": ["g:a:1"]}"""

    private fun linked(
        licenses: String = """["Apache-2.0"]""",
        into: String = "\"g:a:1\"",
        attribution: String = "null",
        extra: String = "",
    ) = """{"name": "L", "url": "https://example.org", "licenses": $licenses, "version": "1",
        "linkedInto": $into, "attribution": $attribution$extra}"""

    private fun notices(
        schema: String = "\"$OSS_NOTICES_SCHEMA\"",
        licenses: String = "{$apache}",
        component: String = module,
        components: String = "[$component]",
        extra: String = "",
    ) = """{"schema": $schema, "platform": "ios", "source": ":composeApp:iosArm64CompileKlibraries",
        "licenses": $licenses, "components": $components$extra}"""

    @Test
    fun minimalValidDocumentsParse() {
        assertEquals(listOf("g:a:1"), (parseOpenSourceNotices(notices()).components.single() as NoticeComponent.Packaged).modules)
        val both = parseOpenSourceNotices(notices(components = "[$module, ${linked(attribution = "\"Required statement.\"")}]"))
        val linkedComponent = both.components.last() as NoticeComponent.Linked
        assertEquals("g:a:1", linkedComponent.linkedInto)
        assertEquals("Required statement.", linkedComponent.attribution)
        assertEquals(1, both.moduleCount)
        assertNull((parseOpenSourceNotices(notices(components = "[$module, ${linked()}]")).components.last() as NoticeComponent.Linked).attribution)
    }

    @Test
    fun malformedDocumentsFailClosed() {
        val other = """"MIT": {"name": "MIT License", "file": "files/legal/LICENSE-MIT.txt"}"""
        val malformed = listOf(
            "not json",
            "[]",
            notices(extra = ""","unexpected": 1"""),
            notices(schema = "\"talentforge.oss-notices.v1\""),
            notices(schema = "1"),
            notices(licenses = "{}"),
            notices(licenses = """{"Apache-2.0": {"name": "Apache License 2.0", "file": "/etc/passwd"}}"""),
            notices(licenses = """{"Apache-2.0": {"name": "Apache License 2.0"}}"""),
            notices(licenses = "{$apache, $other}"), // a declared licence nothing uses
            // v1's single "license" key is not v2
            notices(component = """{"name": "X", "url": "u", "license": "Apache-2.0", "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": ["MIT"], "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": [], "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": "Apache-2.0", "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": ["Apache-2.0", "Apache-2.0"], "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": ["Apache-2.0"], "modules": []}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": ["Apache-2.0"], "modules": ["g:a"]}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": ["Apache-2.0"], "modules": [1]}"""),
            notices(component = """{"name": "", "url": "u", "licenses": ["Apache-2.0"], "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "licenses": ["Apache-2.0"], "modules": ["g:a:1"], "note": "x"}"""),
            notices(components = "[$module, $module]"), // a module listed twice
            notices(components = "[$module, ${linked(into = "\"g:b:1\"")}]"), // linked into an unlisted module
            notices(components = "[${linked()}]"),
            notices(components = "[$module, ${linked(attribution = "1")}]"),
            notices(components = "[$module, ${linked(attribution = "\"\"")}]"),
            notices(components = "[$module, ${linked(licenses = "[\"MIT\"]")}]"),
            notices(components = "[$module, ${linked(extra = ", \"modules\": [\"g:c:1\"]")}]"),
            notices(components = "[$module, " + linked().replace(", \"attribution\": null", "") + "]"), // required key, even when null
            notices(components = "[]"),
            notices(components = "{}"),
        )
        for (document in malformed) {
            assertFailsWith<OpenSourceNoticesException>("must not parse: $document") { parseOpenSourceNotices(document) }
        }
    }

    @Test
    fun thePublishedPrivacyPolicyAddressesAreTheOwnersAndroidPages() {
        assertEquals("https://legal.mythweld.com/privacy.html", LegalLinks.androidPrivacyPolicyUrlRu)
        assertEquals("https://legal.mythweld.com/privacy-en.html", LegalLinks.androidPrivacyPolicyUrlEn)
    }

    @Test
    fun theAndroidPrivacyPolicyUrlFollowsTheUiLanguage() {
        assertEquals(LegalLinks.androidPrivacyPolicyUrlRu, privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, AppLanguage.RUSSIAN))
        assertEquals(LegalLinks.androidPrivacyPolicyUrlEn, privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, AppLanguage.ENGLISH))
    }

    @Test
    fun aPlatformWithoutAPublishedPolicyGetsNoLinkInAnyLanguage() {
        // iOS (M5.LEGAL.LINK): the Android policy must never be offered as the iOS one
        for (language in AppLanguage.entries) {
            assertNull(privacyPolicyUrlOf(PublishedPrivacyPolicy.NotPublished, language))
            assertNull(privacyPolicyLinkOf(privacyPolicyUrlOf(PublishedPrivacyPolicy.NotPublished, language)))
        }
    }

    @Test
    fun theDesktopHostPresentsTheAndroidPolicy() {
        // the desktop target is the Android UI's development host, like its notices and backup statement
        assertEquals(PublishedPrivacyPolicy.Android, platformPrivacyPolicy)
        assertEquals(BackupStatement.AndroidBackupDisabled, platformBackupStatement)
    }

    @Test
    fun everyPublishedPrivacyPolicyUrlIsALegalHttpsLink() {
        for (language in AppLanguage.entries) {
            val url = privacyPolicyUrlOf(PublishedPrivacyPolicy.Android, language)
            assertEquals(url, privacyPolicyLinkOf(url))
        }
    }

    @Test
    fun onlyAnHttpsPrivacyPolicyUrlIsAccepted() {
        val url = "https://policy.example.org/talentforge"
        assertEquals(url, privacyPolicyLinkOf(url))
        for (bad in listOf("", "http://policy.example.org", "policy.example.org", "https://", "https://localhost", "https://a b.org")) {
            assertFailsWith<IllegalArgumentException>("must be rejected: '$bad'") { privacyPolicyLinkOf(bad) }
        }
    }
}
