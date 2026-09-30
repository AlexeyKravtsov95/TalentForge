package io.github.alexeykravtsov95.talentforge.ui

import androidx.compose.runtime.Composable
import io.github.alexeykravtsov95.talentforge.ui.theme.TalentForgeTheme
import io.github.alexeykravtsov95.talentforge.ui.tree.WarriorTalentTreeScreen

/**
 * App entry shared by the Android, iOS and desktop hosts.
 *
 * M4.1 launches directly into the production read-only Talent Tree screen for
 * the bundled baseline Forever Warrior dataset. Home, class picking and
 * navigation arrive in later milestones.
 */
@Composable
fun App() {
    TalentForgeTheme {
        WarriorTalentTreeScreen()
    }
}
