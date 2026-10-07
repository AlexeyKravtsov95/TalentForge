import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(project(":shared"))
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
}

// Opt-in screenshot evidence: `./gradlew :composeApp:desktopTest \
//     -Dtalentforge.m41.screenshots=<dir>` (M4.1), `-Dtalentforge.m42.screenshots`
// (M4.2), `-Dtalentforge.m43.screenshots` (M4.3) or
// `-Dtalentforge.m51.screenshots` (M5.1) or `-Dtalentforge.m5v1.screenshots`
// (M5.V1, desktop-rendered) — skipped by default.
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
    System.getProperty("talentforge.m5v1.screenshots")?.let {
        systemProperty("talentforge.m5v1.screenshots", it)
    }
}
