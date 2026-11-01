package com.tt.autobluetoothcodec

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.tt.autobluetoothcodec.SampleData.ADDRESS
import com.tt.autobluetoothcodec.SampleData.LONG_NAME
import com.tt.autobluetoothcodec.SampleData.aac
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.sony
import com.tt.autobluetoothcodec.SampleData.state

/*
 * Prototypes for wide and tall windows: the content's width capped hard or soft, with or without a tint beside it,
 * on every screen; and where the main screen's cards sit when they don't fill its height.
 */

@Composable
private fun WithLayout(layout: ContentLayout, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentLayout provides layout, content = content)

internal val widthDesigns = listOf(
    "Today" to ContentLayout(),
    "Hard cap" to ContentLayout(cap = WidthCap.HARD),
    "Soft cap" to ContentLayout(cap = WidthCap.SOFT),
    "Hard cap, tinted sides" to ContentLayout(cap = WidthCap.HARD, tintSides = true),
    "Soft cap, tinted sides" to ContentLayout(cap = WidthCap.SOFT, tintSides = true),
)

/** The screens, the main one with an expanded card to show how wide its chips and buttons get. */
private val widthScreens = listOf<Pair<String, @Composable () -> Unit>>(
    "Main" to { AppTheme { MainScreen(state(device(preferred = ldac()), sony()), NoMainActions, setOf(ADDRESS)) } },
    "Log" to { Screens.log.content() },
    "About" to { Screens.about.content() },
    "Licenses" to { Screens.licenses.content() },
)

internal val widthPrototypes: List<Scene> = widthDesigns.flatMap { (design, layout) ->
    widthScreens.map { (screen, content) -> Scene("Width, $design, $screen") { WithLayout(layout) { content() } } }
}

internal val placements = listOf("Top" to 0f, "Centered" to 0.5f, "A third down" to 1f / 3)

private val third = device(aac(), name = LONG_NAME, address = "00:1A:7D:DA:71:13")

/** Device lists, the expanded ones as if tapped, or the lone device expanded on the first pull. */
private val heightCases = listOf(
    Triple("One device, expanded", state(device(preferred = ldac())), setOf(ADDRESS)),
    Triple("One device", state(device(preferred = ldac())), emptySet()),
    Triple("Two devices", state(device(preferred = ldac()), sony()), emptySet()),
    Triple("Two devices, first expanded", state(device(preferred = ldac()), sony()), setOf(ADDRESS)),
    Triple("Three devices", state(device(preferred = ldac()), sony(), third), emptySet()),
)

internal val heightPrototypes: List<Scene> = placements.flatMap { (placement, bias) ->
    heightCases.map { (case, state, expanded) ->
        Scene("Height, $placement, $case") {
            WithLayout(ContentLayout(cap = WidthCap.SOFT, devicePlacement = bias)) {
                AppTheme { MainScreen(state, NoMainActions, expanded) }
            }
        }
    }
}
