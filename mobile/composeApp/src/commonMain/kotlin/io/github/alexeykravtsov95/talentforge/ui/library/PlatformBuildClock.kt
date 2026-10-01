package io.github.alexeykravtsov95.talentforge.ui.library

import io.github.alexeykravtsov95.talentforge.data.builds.BuildClock

/**
 * The device clock as a [BuildClock]. An expect/actual at the app edge (the
 * same boundary that injects platform storage): domain code stays pure and
 * tests inject their own fixed clock.
 */
expect fun platformBuildClock(): BuildClock
