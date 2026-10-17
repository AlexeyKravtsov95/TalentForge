package io.github.alexeykravtsov95.talentforge.ui.about

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M5.RC.2b (LR-4): the bundled Android notices, read through the same
 * resource API the About screen uses, and the strict parser's fail-closed
 * rules. That the list equals the release runtime classpath is checked by
 * `scripts/generate_oss_notices.py --check` (it needs Gradle).
 */
class OpenSourceNoticesTest {

    private val bundled: OpenSourceNotices = runBlocking { loadOpenSourceNotices(ANDROID_OSS_NOTICES_PATH) }

    @Test
    fun theBundledAndroidNoticesParseStrictly() {
        assertEquals("android", bundled.platform)
        assertEquals(setOf("Apache-2.0"), bundled.licenses.keys)
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
            bundled.components.map { it.name },
        )
        val modules = bundled.components.flatMap { it.modules }
        assertEquals(modules.size, modules.toSet().size, "a module is listed twice")
        assertEquals(modules.size, bundled.moduleCount)
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
    fun theBundledLicenceTextIsTheOfficialApacheLicence() {
        val text = runBlocking { readLegalResource(bundled.licenses.getValue("Apache-2.0").textPath) }
        // https://www.apache.org/licenses/LICENSE-2.0.txt, byte for byte
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", digest)
        assertTrue("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION" in text)
    }

    @Test
    fun aMissingResourceIsATypedFailure() {
        assertFailsWith<OpenSourceNoticesException> {
            runBlocking { loadOpenSourceNotices("files/legal/absent.json") }
        }
    }

    private fun notices(
        schema: String = "\"$OSS_NOTICES_SCHEMA\"",
        licenses: String = """{"Apache-2.0": {"name": "Apache License 2.0", "file": "files/legal/LICENSE-Apache-2.0.txt"}}""",
        component: String = """{"name": "X", "url": "https://example.org", "license": "Apache-2.0", "modules": ["g:a:1"]}""",
        components: String = "[$component]",
        extra: String = "",
    ) = """{"schema": $schema, "platform": "android", "source": ":composeApp:releaseRuntimeClasspath",
        "licenses": $licenses, "components": $components$extra}"""

    @Test
    fun aMinimalValidDocumentParses() {
        val parsed = parseOpenSourceNotices(notices())
        assertEquals(listOf("g:a:1"), parsed.components.single().modules)
    }

    @Test
    fun malformedDocumentsFailClosed() {
        val malformed = listOf(
            "not json",
            "[]",
            notices(extra = ""","unexpected": 1"""),
            notices(schema = "\"talentforge.oss-notices.v2\""),
            notices(schema = "1"),
            notices(licenses = "{}"),
            notices(licenses = """{"Apache-2.0": {"name": "Apache License 2.0", "file": "/etc/passwd"}}"""),
            notices(licenses = """{"Apache-2.0": {"name": "Apache License 2.0"}}"""),
            notices(component = """{"name": "X", "url": "u", "license": "MIT", "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "license": "Apache-2.0", "modules": []}"""),
            notices(component = """{"name": "X", "url": "u", "license": "Apache-2.0", "modules": ["g:a"]}"""),
            notices(component = """{"name": "X", "url": "u", "license": "Apache-2.0", "modules": [1]}"""),
            notices(component = """{"name": "", "url": "u", "license": "Apache-2.0", "modules": ["g:a:1"]}"""),
            notices(component = """{"name": "X", "url": "u", "license": "Apache-2.0", "modules": ["g:a:1"], "note": "x"}"""),
            notices(components = "[]"),
            notices(components = "{}"),
        )
        for (document in malformed) {
            assertFailsWith<OpenSourceNoticesException>("must not parse: $document") { parseOpenSourceNotices(document) }
        }
    }

    @Test
    fun noPrivacyPolicyLinkIsConfiguredUntilTheOwnerApprovesOne() {
        // LR-5: the URL is an owner decision; the shipped configuration is "none" until then
        assertNull(LegalLinks.privacyPolicyUrl)
        assertNull(privacyPolicyLinkOf(LegalLinks.privacyPolicyUrl))
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
