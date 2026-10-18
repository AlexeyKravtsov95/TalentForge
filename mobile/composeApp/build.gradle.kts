import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// ------------------------------------------------- OTA settings (M5.OTA.3, client design §8)
//
// Compile-time OTA configuration per platform (android, ios) and channel (production for
// release/store builds, staging for debug/development builds), generated into
// GeneratedOtaSettings.kt. Android and iOS are enabled independently (OD-17). The values come
// ONLY from Gradle properties outside the repository or the environment:
//
//   talentforge.ota.<platform>.<channel>.enabled   true | false (default false)
//   talentforge.ota.<platform>.<channel>.baseUrl   https://… (the channel's static origin, OD-1)
//   talentforge.ota.<platform>.<channel>.keyring   path to {"keys":[{keyId, algorithm, publicKeySpki}]}
//   (environment: TALENTFORGE_OTA_<PLATFORM>_<CHANNEL>_<ENABLED|BASEURL|KEYRING>)
//
// Nothing is set in the repository, so every build keeps OTA disabled until the owner supplies
// hosting and public keys (OD-1, OD-2). An enabled channel without a valid https base URL and
// keyring, a test- key id (reserved for fixtures, contract §8), a production keyring without a
// backup key, or a staging key in the production keyring fails the build.
val otaPlatforms = listOf("android", "ios")
val otaChannels = listOf("production", "staging")

fun otaSetting(platform: String, channel: String, name: String): String? =
    (providers.environmentVariable("TALENTFORGE_OTA_${platform.uppercase()}_${channel.uppercase()}_${name.uppercase()}").orNull
        ?: providers.gradleProperty("talentforge.ota.$platform.$channel.$name").orNull)
        ?.takeIf { it.isNotBlank() }

fun otaKotlinString(value: String?): String =
    if (value == null) "null" else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

fun renderOtaSettings(): String {
    val baseUrlPattern = Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*")
    val keys = mutableMapOf<Pair<String, String>, List<Map<String, String>>>()
    val problems = mutableListOf<String>()
    val sections = StringBuilder()
    for (platform in otaPlatforms) {
        for (channel in otaChannels) {
            val where = "talentforge.ota.$platform.$channel"
            val enabled = when (val raw = otaSetting(platform, channel, "enabled")) {
                null, "false" -> false
                "true" -> true
                else -> { problems += "$where.enabled must be true or false"; false }
            }
            val baseUrl = otaSetting(platform, channel, "baseUrl")
            val keyringPath = otaSetting(platform, channel, "keyring")
            val entries: List<Map<String, String>> = if (keyringPath == null) emptyList() else {
                val file = File(keyringPath)
                if (!file.isFile) { problems += "$where.keyring does not name a file"; emptyList() } else {
                    @Suppress("UNCHECKED_CAST")
                    val parsed = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
                    (parsed["keys"] as? List<Map<String, Any?>>).orEmpty().map { key ->
                        listOf("keyId", "algorithm", "publicKeySpki").associateWith { (key[it] as? String).orEmpty() }
                    }
                }
            }
            keys[platform to channel] = entries
            if (enabled) {
                if (baseUrl == null || !baseUrlPattern.matches(baseUrl)) problems += "$where.baseUrl must be an https origin without query or trailing slash"
                if (entries.isEmpty()) problems += "$where.keyring must pin at least one key"
                if (channel == "production" && entries.size < 2) problems += "$where.keyring must pin a primary and a backup key"
            }
            if (entries.any { it.getValue("keyId").startsWith("test-") }) problems += "$where.keyring contains a test- key (fixtures only)"
            if (entries.any { it.getValue("algorithm") != "ecdsa-p256-sha256" }) problems += "$where.keyring: every key must be ecdsa-p256-sha256"
            val name = platform + channel.replaceFirstChar { it.uppercase() }
            sections.append("    val $name: OtaPlatformSettings = OtaPlatformSettings(\n")
            sections.append("        enabled = $enabled,\n")
            sections.append("        channel = OtaChannel.${channel.uppercase()},\n")
            sections.append("        baseUrl = ${otaKotlinString(baseUrl)},\n")
            sections.append("        keys = listOf(\n")
            for (key in entries) {
                sections.append("            OtaKeySetting(${otaKotlinString(key["keyId"])}, ${otaKotlinString(key["algorithm"])}, ${otaKotlinString(key["publicKeySpki"])}),\n")
            }
            sections.append("        ),\n    )\n\n")
        }
    }
    for (platform in otaPlatforms) {
        val production = keys.getValue(platform to "production").map { it["publicKeySpki"] }.toSet()
        if (keys.getValue(platform to "staging").any { it["publicKeySpki"] in production }) {
            problems += "talentforge.ota.$platform: a staging key is pinned in the production keyring"
        }
    }
    if (problems.isNotEmpty()) throw GradleException("Invalid OTA settings:\n  " + problems.joinToString("\n  "))
    return "// GENERATED by :composeApp:generateOtaSettings from Gradle properties - do not edit.\n" +
        "package io.github.alexeykravtsov95.talentforge.ui.ota\n\n" +
        "import io.github.alexeykravtsov95.talentforge.data.ota.OtaChannel\n\n" +
        "internal object GeneratedOtaSettings {\n" + sections.toString().trimEnd() + "\n}\n"
}

val generatedOtaSettingsDir = layout.buildDirectory.dir("generated/ota/commonMain/kotlin")
val generateOtaSettings = tasks.register("generateOtaSettings") {
    group = "build"
    description = "Generates the per-platform OTA settings (disabled unless the owner configures hosting and keys)."
    val source = provider { renderOtaSettings() }
    val outputDirectory = generatedOtaSettingsDir
    inputs.property("source", source)
    outputs.dir(outputDirectory)
    doLast {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val file = File(root, "io/github/alexeykravtsov95/talentforge/ui/ota/GeneratedOtaSettings.kt")
        file.parentFile.mkdirs()
        file.writeText(source.get())
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm("desktop")

    // M5.OTA.3: Android and the desktop host share one java.security / java.nio /
    // HttpURLConnection implementation of the OTA platform ports (jvmCommonMain).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateOtaSettings)
        }

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(project(":shared"))
        }

        iosTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.compose.ui.test)
            }
        }
    }
}

// ------------------------------------------------- Release signing (M5.RC.2b, TF-RC1-SIGNING)
//
// One owner-held key signs the one universal APK that goes to RuStore and AppGallery
// (docs/release/appgallery/multi-store-signing.md). The four signing settings come ONLY
// from the environment (`TALENTFORGE_RELEASE_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`,
// `_KEY_PASSWORD`) or from Gradle properties outside the repository
// (`talentforge.release.store_file`, … in ~/.gradle/gradle.properties); the environment
// wins. The expected certificate fingerprint for the publishable APK comes the same way
// (`TALENTFORGE_RELEASE_CERT_SHA256` / `talentforge.release.cert_sha256`).
//
// - none set: `assembleRelease` builds the UNSIGNED technical release APK, as before;
// - all four set and valid: the release APK is signed with that key;
// - anything else (some set, keystore missing, inside the repository, or the Android
//   debug keystore): every release build fails, naming the missing or wrong settings
//   but never printing a value.
//
// The release build type never falls back to the debug key. The ONLY artifact meant for
// upload is the output of `:composeApp:assemblePublishableRelease`, which additionally
// requires the complete signing settings and the expected fingerprint, and verifies the
// APK with scripts/verify_release_apk.py before writing it to build/outputs/publishable/.
val releaseSigningSettings = listOf("store_file", "store_password", "key_alias", "key_password")

fun releaseSettingNames(name: String) = "TALENTFORGE_RELEASE_${name.uppercase()} / talentforge.release.$name"

fun releaseSetting(name: String): String? =
    (providers.environmentVariable("TALENTFORGE_RELEASE_${name.uppercase()}").orNull
        ?: providers.gradleProperty("talentforge.release.$name").orNull)
        ?.takeIf { it.isNotBlank() }

val repositoryRoot: File = rootProject.projectDir.parentFile.canonicalFile
// where AGP keeps the SDK debug keystore (ANDROID_USER_HOME overrides ~/.android)
val androidDebugKeystore: File = File(
    providers.environmentVariable("ANDROID_USER_HOME").orNull ?: "${System.getProperty("user.home")}/.android",
    "debug.keystore",
)
val releaseSigning: Map<String, String?> = releaseSigningSettings.associateWith(::releaseSetting)

// Signing values never belong in a committed file: a talentforge.release.* key in the
// repository's gradle.properties fails the build whatever its value.
val committedReleaseKeys: List<String> = listOf(rootProject.file("gradle.properties"), file("gradle.properties"))
    .filter { it.isFile }
    .flatMap { properties ->
        properties.readLines()
            .map { it.substringBefore('=').trim() }
            .filter { it.startsWith("talentforge.release.") }
    }

val releaseSigningProblem: String? = run {
    val missing = releaseSigning.filterValues { it == null }.keys
    val storeFile = releaseSigning["store_file"]?.let { File(it).canonicalFile }
    when {
        committedReleaseKeys.isNotEmpty() ->
            "Release signing settings must not be committed: remove ${committedReleaseKeys.distinct().joinToString()} " +
                "from the repository's gradle.properties and set them in the environment or ~/.gradle/gradle.properties."
        missing.size == releaseSigningSettings.size -> null
        missing.isNotEmpty() ->
            "Incomplete release signing configuration. Missing: ${missing.joinToString { releaseSettingNames(it) }}. " +
                "Set: ${(releaseSigningSettings - missing).joinToString { releaseSettingNames(it) }}. " +
                "Set all four or none (none = unsigned technical release build). Values are not printed."
        storeFile == null || !storeFile.isFile ->
            "${releaseSettingNames("store_file")} does not point to an existing keystore file."
        storeFile.path.startsWith(repositoryRoot.path + File.separator) ->
            "The release keystore must be stored outside the repository checkout."
        storeFile == androidDebugKeystore.canonicalFile || releaseSigning["key_alias"] == "androiddebugkey" ->
            "Release signing must not use the Android debug keystore or its androiddebugkey alias."
        else -> null
    }
}
val releaseSigningComplete = releaseSigningProblem == null && releaseSigning.values.all { it != null }

val expectedReleaseCertSha256: String? = releaseSetting("cert_sha256")
    ?.replace(":", "")
    ?.lowercase()

val publishableProblem: String? = when {
    releaseSigningProblem != null -> releaseSigningProblem
    !releaseSigningComplete ->
        "The publishable release APK needs the release signing configuration: set " +
            releaseSigningSettings.joinToString { releaseSettingNames(it) } +
            " (docs/release/android-release-signing.md). It is never built unsigned or debug-signed."
    expectedReleaseCertSha256 == null ->
        "The publishable release APK needs the expected certificate fingerprint: set ${releaseSettingNames("cert_sha256")}."
    !Regex("[0-9a-f]{64}").matches(expectedReleaseCertSha256) ->
        "${releaseSettingNames("cert_sha256")} must be a SHA-256 fingerprint (64 hexadecimal characters)."
    else -> null
}

android {
    namespace = "io.github.alexeykravtsov95.talentforge"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.alexeykravtsov95.talentforge"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningComplete) {
            create("release") {
                storeFile = File(releaseSigning.getValue("store_file")!!)
                storePassword = releaseSigning.getValue("store_password")
                keyAlias = releaseSigning.getValue("key_alias")
                keyPassword = releaseSigning.getValue("key_password")
            }
        }
    }

    // Opt-in isolated debug install for device checks (M5.UX.2b.1):
    // `-Ptalentforge.applicationIdSuffix=.devcheck` installs next to the real
    // app with its own data, so a scripted check never sees or changes the
    // user's saved builds. Unset (the default): the unchanged application id.
    buildTypes {
        getByName("debug") {
            providers.gradleProperty("talentforge.applicationIdSuffix").orNull?.let { applicationIdSuffix = it }
        }
        getByName("release") {
            // the owner's key, or none (unsigned) — never signingConfigs.debug
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

// Every release-variant build stops here on an incomplete or invalid signing setup.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "release"
    description = "Fails on an incomplete or invalid release signing configuration (never prints values)."
    val problem = releaseSigningProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

val checkPublishableSigning = tasks.register("checkPublishableSigning") {
    group = "release"
    description = "Fails unless the complete release signing configuration and the expected fingerprint are set."
    val problem = publishableProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseSigning)
    mustRunAfter(checkPublishableSigning)
}

/**
 * Copies the signed release APK to `build/outputs/publishable/TalentForge-<versionName>-<versionCode>.apk`
 * and keeps it only if scripts/verify_release_apk.py passes (report next to it). On any failure
 * — validation or any exception — the publishable directory is deleted: there is no other upload
 * artifact. (A failure BEFORE this task runs is covered by the task-graph hook below.)
 */
abstract class AssemblePublishableApk @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val artifactsLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    abstract val validator: RegularFileProperty

    @get:Input
    abstract val buildToolsDirectory: Property<String>

    @get:Input
    abstract val keytool: Property<String>

    @get:Internal
    abstract val debugKeystore: Property<String>

    @get:Input
    abstract val expectedCertificateSha256: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {
        val built = artifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("No release APK was produced.")
        val apk = built.elements.singleOrNull()?.outputFile?.let(::File)
            ?: throw GradleException("Expected exactly one universal release APK, found ${built.elements.size}.")
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        try {
            stage(apk, out)
        } catch (failure: Throwable) {
            out.deleteRecursively()
            throw failure
        }
    }

    private fun stage(apk: File, out: File) {
        out.mkdirs()
        val name = "TalentForge-${versionName.get()}-${versionCode.get()}.apk"
        val staged = File(out, name)
        apk.copyTo(staged, overwrite = true)
        val log = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(
                "python3", validator.get().asFile.path,
                "--apk", staged.path,
                "--expected-cert-sha256", expectedCertificateSha256.get(),
                "--expected-version-code", versionCode.get().toString(),
                "--expected-version-name", versionName.get(),
                "--build-tools", buildToolsDirectory.get(),
                "--keytool", keytool.get(),
                "--debug-keystore", debugKeystore.get(),
                "--report", File(out, "$name.report.json").path,
            )
            standardOutput = log
            errorOutput = log
            isIgnoreExitValue = true
        }
        logger.lifecycle(log.toString())
        if (result.exitValue != 0) {
            throw GradleException("The release APK failed validation (exit ${result.exitValue}); nothing is publishable.")
        }
        logger.lifecycle("Publishable APK: ${staged.path}")
    }
}

val assemblePublishableRelease = tasks.register<AssemblePublishableApk>("assemblePublishableRelease") {
    group = "release"
    description = "Builds, verifies and stages the one signed universal APK for RuStore and AppGallery."
    dependsOn(checkPublishableSigning)
    validator.set(rootProject.layout.projectDirectory.file("../scripts/verify_release_apk.py"))
    buildToolsDirectory.set(
        androidComponents.sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}").asFile.path },
    )
    keytool.set(File(System.getProperty("java.home"), "bin/keytool").path)
    debugKeystore.set(androidDebugKeystore.path)
    expectedCertificateSha256.set(expectedReleaseCertSha256.orEmpty())
    outputDirectory.set(layout.buildDirectory.dir("outputs/publishable"))
    outputs.upToDateWhen { false }
}

// Fail-closed staging (M5.RC.2b review): a publishable build that fails ANYWHERE — the signing
// checks, compilation, packaging, validation — must not leave the previous attempt's APK in
// build/outputs/publishable/. Task order cannot guarantee that (checkReleaseSigning, compile or
// :shared tasks may fail before any publishable task runs), so the directory is cleared as soon
// as the task graph containing assemblePublishableRelease is ready, before any task executes.
// It is refilled only by a fully successful assemblePublishableRelease.
val publishableOutput = layout.buildDirectory.dir("outputs/publishable")
gradle.taskGraph.whenReady {
    if (hasTask("${project.path}:assemblePublishableRelease")) {
        publishableOutput.get().asFile.deleteRecursively()
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val output = variant.outputs.single()
        assemblePublishableRelease.configure {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            versionCode.set(output.versionCode.map { it ?: 0 })
            versionName.set(output.versionName.map { it.orEmpty() })
        }
    }
}

// Opt-in screenshot evidence: `./gradlew :composeApp:desktopTest \
//     -Dtalentforge.m41.screenshots=<dir>` (M4.1), `-Dtalentforge.m42.screenshots`
// (M4.2), `-Dtalentforge.m43.screenshots` (M4.3) or
// `-Dtalentforge.m51.screenshots` (M5.1) or `-Dtalentforge.m5v1.screenshots`
// (M5.V1, desktop-rendered) or `-Dtalentforge.m5ux.screenshots` (M5.UX.1,
// desktop-rendered section tabs) or `-Dtalentforge.m5ux2.screenshots`
// (M5.UX.2a, adaptive inspector and header) or `-Dtalentforge.m5ux2b1.screenshots`
// (M5.UX.2b.1, landscape phones) or `-Dtalentforge.m5ui1.screenshots`
// (M5.UI.1, B+ Home / All Builds) or `-Dtalentforge.m5ui2.screenshots`
// (M5.UI.2, B+ Class Selection) or `-Dtalentforge.m5rel1.screenshots`
// (M5.REL.1, localized specialization names) or
// `-Dtalentforge.m5rel2a.screenshots` (M5.REL.2a, localized shell screens) or
// `-Dtalentforge.m5rel2b.screenshots` (M5.REL.2b, localized talent editor) or
// `-Dtalentforge.m5rel3b2.screenshots` (M5.REL.3b.2, Russian talent text) or
// `-Dtalentforge.m5share2.screenshots` (M5.SHARE.2, build export/import) or
// `-Dtalentforge.m5rc2b.screenshots` (M5.RC.2b, About / legal) or
// `-Dtalentforge.m5ota3.screenshots` (M5.OTA.3, talent data updates) or
// `-Dtalentforge.pr19.recording`
// (PR #19, gesture frames) — skipped by default.
tasks.named<Test>("desktopTest") {
    System.getProperty("talentforge.m41.screenshots")?.let {
        systemProperty("talentforge.m41.screenshots", it)
    }
    System.getProperty("talentforge.m42.screenshots")?.let {
        systemProperty("talentforge.m42.screenshots", it)
    }
    System.getProperty("talentforge.m43.screenshots")?.let {
        systemProperty("talentforge.m43.screenshots", it)
    }
    System.getProperty("talentforge.m51.screenshots")?.let {
        systemProperty("talentforge.m51.screenshots", it)
    }
    System.getProperty("talentforge.m52a.screenshots")?.let {
        systemProperty("talentforge.m52a.screenshots", it)
    }
    System.getProperty("talentforge.m5v1.screenshots")?.let {
        systemProperty("talentforge.m5v1.screenshots", it)
    }
    System.getProperty("talentforge.m5ux.screenshots")?.let {
        systemProperty("talentforge.m5ux.screenshots", it)
    }
    System.getProperty("talentforge.m5ux2.screenshots")?.let {
        systemProperty("talentforge.m5ux2.screenshots", it)
    }
    System.getProperty("talentforge.m5ux2b1.screenshots")?.let {
        systemProperty("talentforge.m5ux2b1.screenshots", it)
    }
    System.getProperty("talentforge.m5ui1.screenshots")?.let {
        systemProperty("talentforge.m5ui1.screenshots", it)
    }
    System.getProperty("talentforge.m5ui2.screenshots")?.let {
        systemProperty("talentforge.m5ui2.screenshots", it)
    }
    System.getProperty("talentforge.m5rel1.screenshots")?.let {
        systemProperty("talentforge.m5rel1.screenshots", it)
    }
    System.getProperty("talentforge.m5rel2a.screenshots")?.let {
        systemProperty("talentforge.m5rel2a.screenshots", it)
    }
    System.getProperty("talentforge.m5rel2b.screenshots")?.let {
        systemProperty("talentforge.m5rel2b.screenshots", it)
    }
    System.getProperty("talentforge.m5rel3b2.screenshots")?.let {
        systemProperty("talentforge.m5rel3b2.screenshots", it)
    }
    System.getProperty("talentforge.m5share2.screenshots")?.let {
        systemProperty("talentforge.m5share2.screenshots", it)
    }
    System.getProperty("talentforge.m5rc2b.screenshots")?.let {
        systemProperty("talentforge.m5rc2b.screenshots", it)
    }
    System.getProperty("talentforge.ota.benchmark")?.let {
        systemProperty("talentforge.ota.benchmark", it)
    }
    System.getProperty("talentforge.m5ota3.screenshots")?.let {
        systemProperty("talentforge.m5ota3.screenshots", it)
    }
    System.getProperty("talentforge.pr19.recording")?.let {
        systemProperty("talentforge.pr19.recording", it)
    }
}
