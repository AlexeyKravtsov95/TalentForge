package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock
import java.lang.System.currentTimeMillis

/** Android: `System.currentTimeMillis`. */
actual fun platformBuildClock(): BuildClock = BuildClock(::currentTimeMillis)
