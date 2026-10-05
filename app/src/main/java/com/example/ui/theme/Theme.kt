package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = SanaPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1032),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = SanaPurpleLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF38154D),
    onSecondaryContainer = Color(0xFFF3DAFF),
    tertiary = SanaGold,
    onTertiary = Color(0xFF452B00),
    background = DarkBackground,
    onBackground = Color(0xFFF6EEFA),
    surface = DarkSurface,
    onSurface = Color(0xFFF6EEFA),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFD4C2DC),
    outline = Color(0xFF6B5878)
)

private val LightColorScheme = lightColorScheme(
    primary = SanaPinkDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF3E0021),
    secondary = SanaPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DAFF),
    onSecondaryContainer = Color(0xFF2E004E),
    tertiary = Color(0xFF8B5000),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF1F1A24),
    surface = LightSurface,
    onSurface = Color(0xFF1F1A24),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF4F4357),
    outline = Color(0xFF81738A)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to rich cyber-romantic dark theme for SANA
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
