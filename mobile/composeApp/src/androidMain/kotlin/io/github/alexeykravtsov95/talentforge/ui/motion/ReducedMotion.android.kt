package io.github.alexeykravtsov95.talentforge.ui.motion

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android: "Remove animations" (Accessibility) and the developer animator
 * scale both set `ANIMATOR_DURATION_SCALE`; 0 means no animations.
 */
@Composable
actual fun platformPrefersReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
