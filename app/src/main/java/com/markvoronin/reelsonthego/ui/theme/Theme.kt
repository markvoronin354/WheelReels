package com.markvoronin.reelsonthego.ui.theme

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
import com.markvoronin.reelsonthego.data.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueLight,
    onPrimary = DarkBackground,
    primaryContainer = PrimaryBlueDark,
    onPrimaryContainer = DarkOnSurface,
    secondary = SecondaryTealLight,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = LightSurface,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = LightOnSurface,
    secondary = SecondaryTeal,
    onSecondary = LightSurface,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
)

@Composable
fun ReelsWhileDrivingTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        (dynamicColor) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
