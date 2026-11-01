package com.tt.autobluetoothcodec

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
 * Colors Material's color scheme doesn't have: success for a codec that's the preferred one, the green of
 * restoring it, which leads there, and amber for a device that isn't linked, which needs attention without
 * being an error.
 */
@Immutable
internal data class StatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val restoreContainer: Color,
    val onRestoreContainer: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
)

private val lightStatusColors = StatusColors(
    successContainer = Color(0xFFE8F5E9),
    // 4.6:1 on the container, readable as text
    onSuccessContainer = Color(0xFF2E7D32),
    restoreContainer = Color(0xFFC3EFC0),
    onRestoreContainer = Color(0xFF002106),
    attentionContainer = Color(0xFFFFDDB3),
    // 8:1 on the container
    onAttentionContainer = Color(0xFF5C3A00),
)

private val darkStatusColors = StatusColors(
    successContainer = Color(0xFF1F3B27),
    onSuccessContainer = Color(0xFFA8D5B0),
    restoreContainer = Color(0xFF1E5124),
    onRestoreContainer = Color(0xFFB6F2B4),
    attentionContainer = Color(0xFF4A3200),
    onAttentionContainer = Color(0xFFFFDDB3),
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
    val palette = LocalPaletteOverride.current
    val scheme = when {
        palette != null -> if (darkTheme) palette.dark else palette.light
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val onInversePrimary = if (darkTheme) palette?.onInversePrimaryDark else palette?.onInversePrimaryLight
    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) darkStatusColors else lightStatusColors,
        LocalOnInversePrimary provides onInversePrimary,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Prototype: a palette instead of Material's baseline one, with the cards on surfaceContainerLow like Material's
 * elevated cards, so they also stand out in dark mode.
 */
internal class PaletteOverride(
    val light: ColorScheme,
    val dark: ColorScheme,
    val onInversePrimaryLight: Color? = null,
    val onInversePrimaryDark: Color? = null,
)

/**
 * Prototype: the text on a chip in the inverse primary color, when the primary container's text doesn't suit it,
 * as in a fidelity scheme, whose primary container is the saturated seed color.
 */
internal val LocalOnInversePrimary = staticCompositionLocalOf<Color?> { null }

/** Prototype: the palette to try, none for today's. */
internal val LocalPaletteOverride = staticCompositionLocalOf<PaletteOverride?> { null }
