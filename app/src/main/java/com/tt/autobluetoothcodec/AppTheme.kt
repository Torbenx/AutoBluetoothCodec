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

/** Colors Material's color scheme doesn't have, for states like a codec that's the preferred one. */
@Immutable
internal data class StatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
)

private val lightStatusColors = StatusColors(
    successContainer = Color(0xFFE8F5E9),
    onSuccessContainer = Color(0xFF388E3C),
)

private val darkStatusColors = StatusColors(
    successContainer = Color(0xFF1F3B27),
    onSuccessContainer = Color(0xFFA8D5B0),
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
