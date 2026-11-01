package com.tt.autobluetoothcodec

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

/** What the readable state's note or banner says: the next change asks to link, so there's no button. */
private const val READABLE_NOTE = "Changing the codec or saving it as preferred asks to link this device first."

/** The collapsed card: the amber "Not linked" chip, with a placeholder while the codec is hidden. */
internal enum class CollapsedDesign(val title: String, val placeholder: String? = null, val open: Boolean = false) {
    CHIP_ONLY("Chip only"),
    UNKNOWN("Codec unknown", placeholder = "Codec unknown"),
    UNAVAILABLE("Codec unavailable", placeholder = "Codec unavailable"),
    NO_INFO("No codec information", placeholder = "No codec information"),
    /** With nothing to collapse, a hidden codec's card always shows its explanation and button. */
    OPEN("Always open while hidden", open = true),
    OPEN_CHIPS("Always open while hidden, with chips", placeholder = "Codec unknown", open = true),
}

/** Where the readable state's note goes, and whether a hidden codec's card is always open. */
internal enum class ExpandedDesign(val title: String, val readableBanner: BannerPlace?, val open: Boolean = false) {
    NOTE("Note under Save", null),
    BANNER_TOP("Banner at the top while readable", BannerPlace.TOP),
    BANNER_SAVE("Banner under Save while readable", BannerPlace.UNDER_SAVE),
    OPEN_TOP("Always open while hidden, banner at the top", BannerPlace.TOP, open = true),
    OPEN_SAVE("Always open while hidden, banner under Save", BannerPlace.UNDER_SAVE, open = true),
}

internal enum class BannerPlace { TOP, UNDER_SAVE }

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

/** The chip after the codec's chips: "Not linked" and "Linking…" in amber, "Can't link" in grey. */
@Composable
private fun LinkChip(state: LinkState) {
    val scheme = MaterialTheme.colorScheme
    val amber = attention()
    val (container, content) =
        if (state == LinkState.CANT_LINK) scheme.surfaceVariant to scheme.onSurfaceVariant
        else amber.container to amber.onContainer
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
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
private fun Banner(state: LinkState, text: String = explanation(state)) {
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
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A hidden codec's link section: a "Not Linked" section like Preferred Codec, or just the text and the button. */
@Composable
private fun LinkSection(state: LinkState, heading: Boolean) {
    val canLink = state != LinkState.CANT_LINK
    Column {
        if (heading) {
            Text(if (canLink) "Not Linked" else "Can't Link", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
        }
        Text(explanation(state), style = MaterialTheme.typography.bodyMedium)
        if (canLink) {
            Spacer(Modifier.height(8.dp))
            LinkButton(state)
        }
    }
}

/** A device card like the app's, with the prototyped parts. */
@Composable
private fun ProtoCard(device: DeviceData, state: LinkState?, collapsed: CollapsedDesign?, expanded: ExpandedDesign?) {
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
            }
            Spacer(Modifier.height(8.dp))
            when {
                state == null -> CodecChips(device)
                expanded != null -> Expanded(device, state, expanded)
                else -> Collapsed(device, state, collapsed!!)
            }
        }
    }
}

@Composable
private fun Expanded(device: DeviceData, state: LinkState, design: ExpandedDesign) {
    if (!device.statusReadable) {
        // The same as collapsed when it's always open
        if (design.open) LinkSection(state, heading = false) else LinkSection(state, heading = true)
        return
    }
    Column {
        if (design.readableBanner == BannerPlace.TOP) {
            Banner(state, READABLE_NOTE)
            Spacer(Modifier.height(16.dp))
        }
        CodecConfigSelection(device, onConfigChange = {})
        PreferredCodecActions(device, NoMainActions)
        when (design.readableBanner) {
            BannerPlace.UNDER_SAVE -> {
                Spacer(Modifier.height(12.dp))
                Banner(state, READABLE_NOTE)
            }
            null -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    READABLE_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun Collapsed(device: DeviceData, state: LinkState, design: CollapsedDesign) {
    if (device.statusReadable) {
        CodecChips(device) { LinkChip(state) }
        return
    }
    Column {
        if (!design.open || design.placeholder != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                design.placeholder?.let { ActiveConfigChip(it) }
                LinkChip(state)
            }
        }
        if (design.open) {
            if (design.placeholder != null)
                Spacer(Modifier.height(12.dp))
            LinkSection(state, heading = false)
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
