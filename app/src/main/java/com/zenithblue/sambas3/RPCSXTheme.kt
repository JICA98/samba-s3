package com.zenithblue.sambas3

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat

object RPCSXColors {
    val background = Color(0xFF0A0D1A)
    val surface = Color(0xFF0F1528)
    val surfaceElevated = Color(0xFF172040)
    val surfaceOverlay = Color(0xFF1C2850)
    val primary = Color(0xFFC9A84C)
    val primaryDim = Color(0xFF8C6E2A)
    val primaryMuted = Color(0xFF2A2010)
    val onPrimary = Color(0xFF0A0D1A)
    val textPrimary = Color(0xFFF0E8D0)
    val textSecondary = Color(0xFF9A8E72)
    val textDisabled = Color(0xFF3D3A30)
    val focusRing = Color(0xFFF0C040)
    val focusGlow = Color(0xFFC9A84C)
    val errorColor = Color(0xFFE05252)
    val onErrorColor = Color(0xFFF0E8D0)
}

private val sambaScheme = darkColorScheme(
    primary = RPCSXColors.primary,
    onPrimary = RPCSXColors.onPrimary,
    primaryContainer = RPCSXColors.primaryMuted,
    onPrimaryContainer = RPCSXColors.primary,
    secondary = RPCSXColors.primaryDim,
    onSecondary = RPCSXColors.onPrimary,
    background = RPCSXColors.background,
    onBackground = RPCSXColors.textPrimary,
    surface = RPCSXColors.surface,
    onSurface = RPCSXColors.textPrimary,
    surfaceVariant = RPCSXColors.surfaceElevated,
    onSurfaceVariant = RPCSXColors.textSecondary,
    error = RPCSXColors.errorColor,
    onError = RPCSXColors.onErrorColor
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.96.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 1.2.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.32.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.55.sp
    )
)

@Composable
fun RPCSXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = sambaScheme

    val view = LocalView.current
    val activity = view.context as? Activity

    SideEffect {
        activity?.window?.apply {
            statusBarColor = android.graphics.Color.TRANSPARENT
            navigationBarColor = android.graphics.Color.TRANSPARENT
            isNavigationBarContrastEnforced = false
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.isAppearanceLightNavigationBars = false
            insetsController.isAppearanceLightStatusBars = false
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}

