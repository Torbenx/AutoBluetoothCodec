package com.tt.autobluetoothcodec

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors Material's color scheme doesn't have: success for a codec that's the preferred one, and the green of
 * restoring it, which leads there.
 */
@Immutable
internal data class StatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val restoreContainer: Color,
    val onRestoreContainer: Color,
)

private val lightStatusColors = StatusColors(
    successContainer = Color(0xFFE8F5E9),
    // 4.6:1 on the container, readable as text
    onSuccessContainer = Color(0xFF2E7D32),
    restoreContainer = Color(0xFFC3EFC0),
    onRestoreContainer = Color(0xFF002106),
)

private val darkStatusColors = StatusColors(
    successContainer = Color(0xFF1F3B27),
    onSuccessContainer = Color(0xFFA8D5B0),
    restoreContainer = Color(0xFF1E5124),
    onRestoreContainer = Color(0xFFB6F2B4),
)

private val LocalStatusColors = staticCompositionLocalOf { lightStatusColors }

/** The app's colors besides [MaterialTheme.colorScheme], they follow the theme like it. */
internal object AppColors {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

/**
 * The app's Material theme, dark when the system is. The colors are still Material's baseline
 * palette, the app's own palette and dynamic colors come with the UI polish.
 */
@Composable
internal fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) darkStatusColors else lightStatusColors) {
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(), content = content)
    }
}
