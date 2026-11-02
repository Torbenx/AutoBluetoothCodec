package com.tt.autobluetoothcodec

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.tt.autobluetoothcodec.SampleData.ADDRESS
import com.tt.autobluetoothcodec.SampleData.aac
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.sbc
import com.tt.autobluetoothcodec.SampleData.sony
import com.tt.autobluetoothcodec.SampleData.state

/* Prototypes for the main screen's top bar, at the top of the list and scrolled down a long one. */

internal val topBarStyles = listOf(
    "Today" to TopBarStyle.SMALL,
    "Tinted on scroll" to TopBarStyle.SMALL_TINTED,
    "Medium" to TopBarStyle.MEDIUM,
    "Medium, subtitle, log icon" to TopBarStyle.MEDIUM_SUBTITLE,
    "Large" to TopBarStyle.LARGE,
    "Dropdown" to TopBarStyle.DROPDOWN,
    "Tabs" to TopBarStyle.TABS,
)

/** Two devices, and a list longer than the screen with its first card expanded, to scroll. */
internal val topBarCases = listOf<Triple<String, MainScreenState, Set<String>>>(
    Triple("Two devices", state(device(preferred = ldac()), sony()), emptySet()),
    Triple("Long list", state(
        device(preferred = ldac()),
        sony(preferred = aac()),
        device(aac(), masks = SampleData.SONY_MASKS, name = "Pixel Buds Pro 2", address = "00:00:00:00:00:05"),
        device(sbc(), masks = SampleData.SONY_MASKS, name = "Car", address = "00:00:00:00:00:06"),
    ), setOf(ADDRESS)),
)

@Composable
private fun WithTopBar(style: TopBarStyle, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalTopBarStyle provides style, content = content)

internal val topBarPrototypes: List<Scene> = topBarStyles.flatMap { (styleName, style) ->
    topBarCases.map { (case, state, expanded) ->
        Scene("Top bar, $styleName, $case") {
            WithTopBar(style) { AppTheme { MainScreen(state, NoMainActions, expanded) } }
        }
    }
}
