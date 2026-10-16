package com.victorious.flash.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0B1530)
val Panel = Color(0xFF13214A)
val Electric = Color(0xFF39FF14)
val Electric2 = Color(0xFF00FF85)
val Danger = Color(0xFFFF4D5E)

private val scheme = darkColorScheme(
    primary = Electric, onPrimary = Navy, secondary = Electric2, onSecondary = Navy,
    background = Navy, onBackground = Color.White, surface = Panel, onSurface = Color.White,
    error = Danger, onError = Color.White,
)

@Composable
fun VictoriousTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
