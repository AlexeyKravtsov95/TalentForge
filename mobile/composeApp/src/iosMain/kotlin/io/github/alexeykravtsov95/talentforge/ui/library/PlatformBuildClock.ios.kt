package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** iOS: `NSDate` seconds since epoch, in milliseconds. */
actual fun platformBuildClock(): BuildClock = BuildClock {
    (NSDate().timeIntervalSince1970 * 1000).toLong()
}
