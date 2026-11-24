package com.mengpolar.ldrlife.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Heart = Color(0xFFE85A71)
private val HeartDeep = Color(0xFFC73E56)

private val LightColors = lightColorScheme(
    primary = HeartDeep,
    secondary = Heart,
    surface = Color(0xFFFFF7F8),
    background = Color(0xFFFFF7F8),
)

private val DarkColors = darkColorScheme(
    primary = Heart,
    secondary = HeartDeep,
)

@Composable
fun LDRLifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
