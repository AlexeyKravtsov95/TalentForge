package io.github.alexeykravtsov95.talentforge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.alexeykravtsov95.talentforge.ui.library.AndroidBuildLibraryStorage

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
        setContent {
            App(storage = AndroidBuildLibraryStorage(applicationContext))
        }
    }
}
