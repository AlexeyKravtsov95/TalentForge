package io.github.alexeykravtsov95.talentforge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.alexeykravtsov95.talentforge.ui.about.installedAppVersion
import io.github.alexeykravtsov95.talentforge.ui.library.AndroidBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.ota.AndroidOta

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window extends behind the system bars (TalentForge paints its
        // dark background to the physical edges; the common screen insets the
        // content via WindowInsets.safeDrawing). The app palette is fixed
        // dark, so both bars use the light-icons transparent style — no
        // hardcoded bar heights anywhere.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
        val appVersion = installedAppVersion(applicationContext)
        // OTA recovery (local files only) runs before the first screen; no network here
        val ota = AndroidOta.runtime(applicationContext)
        setContent {
            App(
                storage = AndroidBuildLibraryStorage(applicationContext),
                appVersion = appVersion,
                // null unless this build type enables OTA (M5.OTA.3; disabled by default)
                ota = ota,
            )
        }
    }
}
