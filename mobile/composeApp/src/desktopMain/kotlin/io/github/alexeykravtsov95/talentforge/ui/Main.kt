package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.alexeykravtsov95.talentforge.ui.library.DesktopBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.navigation.DesktopBackDispatcher

fun main() = application {
    Window(
        title = "TalentForge",
        state = rememberWindowState(width = 420.dp, height = 780.dp),
        onCloseRequest = ::exitApplication,
        // Esc = the system Back of this host (same dispatcher rules as Android)
        onKeyEvent = { event ->
            event.type == KeyEventType.KeyDown && event.key == Key.Escape && DesktopBackDispatcher.dispatch()
        },
    ) {
        App(storage = DesktopBuildLibraryStorage())
    }
}
