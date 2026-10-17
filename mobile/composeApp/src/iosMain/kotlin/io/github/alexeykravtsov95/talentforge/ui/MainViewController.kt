package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.ui.window.ComposeUIViewController
import io.github.alexeykravtsov95.talentforge.ui.about.bundleAppVersion
import io.github.alexeykravtsov95.talentforge.ui.library.IosBuildLibraryStorage

fun MainViewController() = ComposeUIViewController {
    App(storage = IosBuildLibraryStorage(), appVersion = bundleAppVersion())
}
