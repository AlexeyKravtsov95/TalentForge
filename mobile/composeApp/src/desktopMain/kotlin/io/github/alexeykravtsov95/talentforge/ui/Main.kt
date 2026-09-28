package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        title = "TalentForge",
        state = rememberWindowState(width = 420.dp, height = 780.dp),
        onCloseRequest = ::exitApplication,
    ) {
        App()
    }
}
