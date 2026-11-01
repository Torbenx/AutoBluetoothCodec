package com.tt.autobluetoothcodec

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's Material theme, dark when the system is. The colors are still Material's baseline
 * palette, the app's own palette and dynamic colors come with the UI polish.
 */
@Composable
internal fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(), content = content)
}
