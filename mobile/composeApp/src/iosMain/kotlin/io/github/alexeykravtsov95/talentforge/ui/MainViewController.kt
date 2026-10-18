package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.ui.window.ComposeUIViewController
import io.github.alexeykravtsov95.talentforge.ui.about.bundleAppVersion
import io.github.alexeykravtsov95.talentforge.ui.library.IosBuildLibraryStorage
import io.github.alexeykravtsov95.talentforge.ui.ota.IosOta

fun MainViewController() = ComposeUIViewController {
    App(
        storage = IosBuildLibraryStorage(),
        appVersion = bundleAppVersion(),
        // null unless this build enables OTA (M5.OTA.3; disabled by default, OD-17)
        ota = IosOta.runtime(),
    )
}
