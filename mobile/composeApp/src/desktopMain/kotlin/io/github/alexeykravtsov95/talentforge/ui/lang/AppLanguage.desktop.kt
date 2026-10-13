package io.github.alexeykravtsov95.talentforge.ui.lang

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.Locale

/** Desktop (JVM): the JVM default locale's tag. */
@Composable
actual fun platformPrimaryLanguageTag(): String = remember { Locale.getDefault().toLanguageTag() }
