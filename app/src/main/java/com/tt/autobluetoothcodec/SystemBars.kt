package com.tt.autobluetoothcodec

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Since API 35 the window is always edge-to-edge. There is no dark theme yet, so the system bars
 * have to stay light even if the system is in dark mode. The navigation bar keeps the translucent
 * white scrim enableEdgeToEdge uses by default for three-button navigation.
 */
fun ComponentActivity.enableLightEdgeToEdge() {
    val navigationBarScrim = android.graphics.Color.argb(0xe6, 0xff, 0xff, 0xff)
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(navigationBarScrim, navigationBarScrim)
    )
}
