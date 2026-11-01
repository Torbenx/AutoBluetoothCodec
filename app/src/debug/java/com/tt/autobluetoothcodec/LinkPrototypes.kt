package com.tt.autobluetoothcodec

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.sony

/*
 * Prototypes for a device that isn't linked, in the style of the preferred codec's buttons. Collapsed, the card
 * mostly stays as it is; expanded, a "Link Device" button sits where the lone Save button does, and the prototypes
 * try where its explanation goes. The codec chips, the codec selection and the preferred codec's row are the app's.
 */

/** Material's "link" and "link_off" icons, which aren't in the core icons. */
private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    .build()

private val LinkIcon = icon("Link",
    "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1z" +
        "M8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
private val LinkOffIcon = icon("LinkOff",
    "M17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1 0,1.43 -0.98,2.63 -2.31,2.98l1.46,1.46C20.88,15.61 22,13.95 22,12" +
        "c0,-2.76 -2.24,-5 -5,-5zM16,11h-2.19l2,2H16zM2,4.27l3.11,3.11C3.29,8.12 2,9.91 2,12c0,2.76 2.24,5 5,5h4v-1.9H7" +
        "c-1.71,0 -3.1,-1.39 -3.1,-3.1 0,-1.59 1.21,-2.9 2.76,-3.07L8.73,11H8v2h2.73L13,15.27V17h1.73l4.01,4L20,19.74 " +
        "3.27,3 2,4.27z")

/** The states of a device that isn't linked. */
internal enum class LinkState(val title: String) {
    HIDDEN("Codec hidden until linked"),
    // Before Android 16 the codec can be read without a link; changing it or saving it asks to link first
    READABLE("Codec readable, not linked"),
    LINKING("Linking"),
    CANT_LINK("Phone can't link"),
}

private fun deviceOf(state: LinkState) = when (state) {
    LinkState.READABLE -> device(associationRequired = true, associated = false)
    else -> device(statusReadable = false, associationRequired = true, associated = false)
}

/** Why the device should be linked, without its name, which the card already shows. */
private fun explanation(state: LinkState) = when (state) {
    LinkState.HIDDEN, LinkState.LINKING -> "Link this device to see and change its codec."
    LinkState.READABLE -> "Link this device to change its codec and apply a preferred codec when it connects."
    LinkState.CANT_LINK -> "This phone can't link devices, so the codec can't be read or changed."
}

internal enum class CollapsedDesign(val title: String) {
    CHIP("Not linked chip"),
    CHIP_TERTIARY("Not linked chip, theme tertiary"),
    CHIP_OUTLINE("Not linked chip, outlined"),
    PLACEHOLDER("Codec placeholder and chip"),
    STATUS_LINE("Status line"),
    NAME_ICON("Icon by the name"),
    INLINE_BUTTON("Link button in the card"),
}

internal enum class ExpandedDesign(val title: String) {
    SECTION("Section with heading"),
    ABOVE("Text above the button"),
    BELOW("Text below the button"),
    BANNER("Banner at the top"),
    NO_BUTTON("No button while readable"),
}

/** Amber, for a state that needs attention without being an error. */
private class Attention(val container: Color, val onContainer: Color, val accent: Color)

@Composable
private fun attention(): Attention =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Attention(Color(0xFF4A3200), Color(0xFFFFDDB3), Color(0xFFFFB95C))
    } else {
        Attention(Color(0xFFFFDDB3), Color(0xFF5C3A00), Color(0xFF8B5000))
    }

/** A spinner frozen at a quarter turn, since the renderer stops animations at their first frame, a dot. */
@Composable
private fun Spinner(size: Dp, color: Color) =
    CircularProgressIndicator(progress = { 0.3f }, modifier = Modifier.size(size), color = color, strokeWidth = 2.dp,
        trackColor = Color.Transparent)

/** The chip after the codec's chips: "Not linked", "Linking…" or "Can't link". */
@Composable
private fun LinkChip(state: LinkState, design: CollapsedDesign) {
    val scheme = MaterialTheme.colorScheme
    val amber = attention()
    val neutral = state == LinkState.CANT_LINK
    val (container, content) = when {
        neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
        design == CollapsedDesign.CHIP_TERTIARY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        design == CollapsedDesign.CHIP_OUTLINE -> Color.Transparent to amber.accent
        else -> amber.container to amber.onContainer
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        border = if (design == CollapsedDesign.CHIP_OUTLINE && !neutral) BorderStroke(1.dp, content) else null,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state == LinkState.LINKING) {
                Spinner(14.dp, content)
            } else {
                Icon(LinkOffIcon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                when (state) {
                    LinkState.LINKING -> "Linking…"
                    LinkState.CANT_LINK -> "Can't link"
                    else -> "Not linked"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** "Link Device" where the lone Save button would be: as wide as the row, at most 420 dp, tonal in the theme's color. */
@Composable
private fun LinkButton(state: LinkState) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        Surface(
            onClick = {},
            shape = CircleShape,
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state == LinkState.LINKING) {
                Row(
                    Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spinner(preferredIconSize(), scheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Linking…", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            } else {
                PreferredContent("Link Device", LinkIcon, showLabel = true)
            }
        }
    }
}

/** The explanation in a tinted box with the "link off" icon. */
@Composable
private fun Banner(state: LinkState) {
    val amber = attention()
    val neutral = state == LinkState.CANT_LINK
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (neutral) MaterialTheme.colorScheme.surfaceVariant else amber.container,
        contentColor = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else amber.onContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(LinkOffIcon, contentDescription = null, modifier = Modifier.size(preferredIconSize()))
            Spacer(Modifier.width(12.dp))
            Text(explanation(state), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The expanded card's link section, at its bottom. */
@Composable
private fun LinkSection(state: LinkState, design: ExpandedDesign, topGap: Dp) {
    val canLink = state != LinkState.CANT_LINK
    Column {
        Spacer(Modifier.height(topGap))
        when (design) {
            // Choosing a codec and Save ask to link anyway, so a readable codec only gets a note
            ExpandedDesign.NO_BUTTON -> if (state == LinkState.READABLE) {
                Text(
                    "Changing the codec or saving it as preferred asks to link this device first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                Text(if (canLink) "Not Linked" else "Can't Link", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(explanation(state), style = MaterialTheme.typography.bodyMedium)
                if (canLink) {
                    Spacer(Modifier.height(8.dp))
                    LinkButton(state)
                }
            }
            ExpandedDesign.SECTION -> {
                Text(if (canLink) "Not Linked" else "Can't Link", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(explanation(state), style = MaterialTheme.typography.bodyMedium)
                if (canLink) {
                    Spacer(Modifier.height(8.dp))
                    LinkButton(state)
                }
            }
            ExpandedDesign.ABOVE -> {
                Text(explanation(state), style = MaterialTheme.typography.bodyMedium)
                if (canLink) {
                    Spacer(Modifier.height(8.dp))
                    LinkButton(state)
                }
            }
            ExpandedDesign.BELOW -> {
                if (canLink) {
                    LinkButton(state)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    explanation(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            ExpandedDesign.BANNER -> if (canLink) LinkButton(state)
        }
    }
}

/** A device card like the app's, with the prototyped parts. */
@Composable
private fun ProtoCard(
    device: DeviceData,
    state: LinkState?,
    collapsed: CollapsedDesign?,
    expanded: ExpandedDesign?,
) {
    val amber = attention()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shadowElevation = 6.dp,
        shape = CardDefaults.elevatedShape,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (state != null && collapsed == CollapsedDesign.NAME_ICON) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        when (state) {
                            LinkState.LINKING -> Spinner(20.dp, amber.accent)
                            LinkState.CANT_LINK ->
                                Icon(LinkOffIcon, "Can't link", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Icon(LinkOffIcon, "Not linked", tint = amber.accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (state == null) {
                CodecChips(device)
            } else if (expanded != null) {
                if (expanded == ExpandedDesign.BANNER) {
                    Banner(state)
                    Spacer(Modifier.height(if (device.statusReadable) 16.dp else 4.dp))
                }
                if (device.statusReadable) {
                    CodecConfigSelection(device, onConfigChange = {})
                    PreferredCodecActions(device, NoMainActions)
                }
                LinkSection(state, expanded, topGap = if (device.statusReadable) 16.dp else 8.dp)
            } else {
                Collapsed(device, state, collapsed!!)
            }
        }
    }
}

@Composable
private fun Collapsed(device: DeviceData, state: LinkState, design: CollapsedDesign) {
    val amber = attention()
    when (design) {
        CollapsedDesign.CHIP, CollapsedDesign.CHIP_TERTIARY, CollapsedDesign.CHIP_OUTLINE, CollapsedDesign.PLACEHOLDER ->
            if (device.statusReadable) {
                CodecChips(device) { LinkChip(state, design) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (design == CollapsedDesign.PLACEHOLDER)
                        ActiveConfigChip("Codec hidden")
                    LinkChip(state, design)
                }
            }
        CollapsedDesign.STATUS_LINE -> Column {
            val neutral = state == LinkState.CANT_LINK
            val color = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else amber.accent
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == LinkState.LINKING) Spinner(16.dp, color)
                else Icon(LinkOffIcon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (state) {
                        LinkState.HIDDEN -> "Not linked, so the codec is hidden"
                        LinkState.READABLE -> "Not linked, changing the codec asks to link it"
                        LinkState.LINKING -> "Linking…"
                        LinkState.CANT_LINK -> "This phone can't link devices"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
            if (device.statusReadable) {
                Spacer(Modifier.height(10.dp))
                CodecChips(device)
            }
        }
        CollapsedDesign.NAME_ICON -> if (device.statusReadable) CodecChips(device)
        CollapsedDesign.INLINE_BUTTON -> Column {
            Text(explanation(state), style = MaterialTheme.typography.bodyMedium)
            if (state != LinkState.CANT_LINK) {
                Spacer(Modifier.height(8.dp))
                LinkButton(state)
            }
            if (device.statusReadable) {
                Spacer(Modifier.height(12.dp))
                CodecChips(device)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtoScreen(state: LinkState, collapsed: CollapsedDesign? = null, expanded: ExpandedDesign? = null) {
    AppTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More options") } }
                )
            },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            // Expanded, scrolled to the bottom, where the link section is
            val scroll = rememberScrollState(if (expanded != null) Int.MAX_VALUE else 0)
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(innerPadding)) {
                ProtoCard(deviceOf(state), state, collapsed, expanded)
                // A linked device to compare with
                if (collapsed != null)
                    ProtoCard(sony(), null, null, null)
            }
        }
    }
}

internal val linkPrototypes: List<Scene> =
    CollapsedDesign.entries.flatMap { design ->
        LinkState.entries.map { Scene("Collapsed, ${design.title}, ${it.title}") { ProtoScreen(it, collapsed = design) } }
    } + ExpandedDesign.entries.flatMap { design ->
        LinkState.entries.map { Scene("Expanded, ${design.title}, ${it.title}") { ProtoScreen(it, expanded = design) } }
    }
