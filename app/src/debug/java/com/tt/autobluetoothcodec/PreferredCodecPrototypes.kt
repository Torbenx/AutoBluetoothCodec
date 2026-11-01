package com.tt.autobluetoothcodec

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.tt.autobluetoothcodec.SampleData.LONG_NAME
import com.tt.autobluetoothcodec.SampleData.aac
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.sony

/*
 * Prototypes for where the device card shows its preferred codec, to compare the options before one of them
 * replaces the status button in the card's header. Only the parts that differ are prototyped, the codec chips
 * and the codec selection are the app's own.
 */

/** Where the collapsed card shows that the preferred codec is active. */
internal enum class CollapsedVariant(val title: String) {
    INLINE("Last chip"),
    OWN_LINE("Own line"),
}

/** What the expanded card offers for the preferred codec, at its bottom. */
internal enum class ExpandedVariant(val title: String) {
    SAVE_WHEN_NEEDED("Save button only when needed"),
    MORPHING_CHIP("Status chip that turns into a button"),
    GROUP_DISABLED("Button group, unavailable disabled"),
    GROUP_STATUS("Button group, unavailable as status"),
    FILLING("Filling buttons"),
    FILLING_STATUS("Filling buttons with a status line"),
    FIXED_SLOTS("Equal slots, unavailable disabled"),
}

/** The preferred codec's state, which decides which actions are available. */
internal enum class PreferredState(val title: String) {
    NONE("Nothing saved"),
    ACTIVE("Preferred codec active"),
    DIFFERS("Preferred codec differs"),
}

private fun stateOf(device: DeviceData) = when {
    device.preferredConfig == null -> PreferredState.NONE
    device.preferredConfig == device.codecConfig -> PreferredState.ACTIVE
    else -> PreferredState.DIFFERS
}

/** The label that says the preferred codec is active, colored like the success it is. */
@Composable
private fun PreferredChip() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = AppColors.status.successContainer,
        contentColor = AppColors.status.onSuccessContainer,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Preferred Codec", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The codec chips of the collapsed card, like the app shows them. */
@Composable
private fun CodecChips(config: CodecConfig) {
    CodecConfig.getCodecName(config.getCodecType())?.let { ActiveConfigChip(it) }
    CodecConfig.getSampleRate(config.sampleRate)?.let { ActiveConfigChip("$it Hz") }
    CodecConfig.getBitsPerSample(config.bitsPerSample)?.let { ActiveConfigChip("$it bits/sample") }
    if (config is LDACCodecConfig) {
        if (config.bitrate == LDACCodecConfig.BITRATE_ADAPTIVE) {
            ActiveConfigChip("Adaptive")
        } else {
            LDACCodecConfig.getFixedBitrate(config.bitrate, config.sampleRate)?.let { ActiveConfigChip("$it kbps") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsedContent(device: DeviceData, variant: CollapsedVariant) {
    val config = device.codecConfig ?: return
    val active = stateOf(device) == PreferredState.ACTIVE
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CodecChips(config)
        if (active && variant == CollapsedVariant.INLINE)
            PreferredChip()
    }
    if (active && variant == CollapsedVariant.OWN_LINE) {
        Spacer(Modifier.height(8.dp))
        PreferredChip()
    }
}

private class PreferredAction(val label: String, val icon: ImageVector, val available: Boolean, val done: String)

private fun actionsOf(state: PreferredState) = listOf(
    PreferredAction("Save", Icons.Default.Star, state != PreferredState.ACTIVE, "Saved"),
    PreferredAction("Restore", Icons.Default.Refresh, state == PreferredState.DIFFERS, "Applied"),
    PreferredAction("Delete", Icons.Default.Delete, state != PreferredState.NONE, "Nothing saved"),
)

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

/** A line under the section's title that says what's saved. */
@Composable
private fun StatusLine(device: DeviceData) {
    val text = when (stateOf(device)) {
        PreferredState.NONE -> "None saved yet. The preferred codec is applied whenever the device connects."
        PreferredState.ACTIVE -> "The current codec is the preferred one."
        PreferredState.DIFFERS -> "Saved: ${device.preferredConfig!!.describe()}"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpandedActions(device: DeviceData, variant: ExpandedVariant) {
    val state = stateOf(device)
    val actions = actionsOf(state)
    when (variant) {
        ExpandedVariant.SAVE_WHEN_NEEDED -> {
            if (state != PreferredState.ACTIVE) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = {}) { Text("Save As Preferred Codec") }
            }
        }
        ExpandedVariant.MORPHING_CHIP -> {
            Spacer(Modifier.height(16.dp))
            StatusChip(
                if (state == PreferredState.ACTIVE) "Preferred Codec" else "Save As Preferred Codec",
                statusChipColors(AppColors.status.successContainer, AppColors.status.onSuccessContainer),
                isButton = state != PreferredState.ACTIVE,
                onButtonClick = {}
            )
        }
        ExpandedVariant.GROUP_DISABLED, ExpandedVariant.GROUP_STATUS -> {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Preferred Codec")
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (action in actions) {
                    if (action.available || variant == ExpandedVariant.GROUP_DISABLED) {
                        OutlinedButton(onClick = {}, enabled = action.available) { Text(action.label) }
                    } else {
                        // Takes the button's place, so nothing moves
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(min = 40.dp)
                        ) {
                            Box40 { Text(action.done, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
        }
        ExpandedVariant.FILLING, ExpandedVariant.FILLING_STATUS -> {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Preferred Codec")
            if (variant == ExpandedVariant.FILLING_STATUS) {
                Spacer(Modifier.height(2.dp))
                StatusLine(device)
            }
            Spacer(Modifier.height(8.dp))
            FillingButtons(actions.filter { it.available })
        }
        ExpandedVariant.FIXED_SLOTS -> {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Preferred Codec")
            Spacer(Modifier.height(8.dp))
            // All three always, so nothing moves, at most as wide as on a phone
            FillingButtons(actions, Modifier.widthIn(max = 420.dp))
        }
    }
}

/** Centers its content in the height of a button. */
@Composable
private fun Box40(content: @Composable () -> Unit) {
    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/** The available actions side by side, sharing the width; just their icons if a label doesn't fit. */
@Composable
private fun FillingButtons(actions: List<PreferredAction>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        val each = (maxWidth - gap * (actions.size - 1)) / actions.size
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        // Icon 18, its gap 8 and the button's padding 16 on each side
        val labelsFit = actions.all {
            with(density) { measurer.measure(it.label, style, softWrap = false).size.width.toDp() } + 18.dp + 8.dp + 32.dp <= each
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (action in actions) {
                OutlinedButton(
                    onClick = {},
                    enabled = action.available,
                    // Keeps the height of a button with a label when it only has its icon
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(action.icon, contentDescription = if (labelsFit) null else action.label,
                        modifier = Modifier.size(ButtonDefaults.IconSize))
                    if (labelsFit) {
                        Spacer(Modifier.width(8.dp))
                        Text(action.label, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A device card with the header that only has the name, and the preferred codec where a variant puts it. */
@Composable
private fun PrototypeCard(
    device: DeviceData,
    expanded: Boolean,
    collapsed: CollapsedVariant,
    expandedVariant: ExpandedVariant,
) {
    // The two variants that keep only a button need the menu for restoring and deleting
    val menu = expanded && device.preferredConfig != null &&
        expandedVariant in setOf(ExpandedVariant.SAVE_WHEN_NEEDED, ExpandedVariant.MORPHING_CHIP)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shadowElevation = 6.dp,
        shape = CardDefaults.elevatedShape,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (menu) {
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                CodecConfigSelection(device) {}
                ExpandedActions(device, expandedVariant)
            } else {
                CollapsedContent(device, collapsed)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrototypeScreen(
    devices: List<DeviceData>,
    expanded: Boolean,
    collapsed: CollapsedVariant = CollapsedVariant.OWN_LINE,
    expandedVariant: ExpandedVariant = ExpandedVariant.FILLING,
) = AppTheme {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        // Expanded, the actions are at the bottom of the card, which may be out of view
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(if (expanded) Int.MAX_VALUE else 0)).padding(innerPadding)) {
            for (device in devices)
                PrototypeCard(device, expanded, collapsed, expandedVariant)
        }
    }
}

/** LDAC with a fixed bitrate, four chips. */
private val LDAC_990 = ldac(bitrate = LDACCodecConfig.BITRATE_HIGH)

/** Devices with the preferred codec active, with three and four chips, a long name, and one without. */
private val collapsedDevices = listOf(
    device(LDAC_990, preferred = LDAC_990),
    sony(preferred = aac()),
    device(aac(), masks = SampleData.SONY_MASKS, name = "Pixel Buds Pro 2", address = "00:00:00:00:00:05"),
    device(ldac(bitrate = LDACCodecConfig.BITRATE_ADAPTIVE), preferred = ldac(bitrate = LDACCodecConfig.BITRATE_ADAPTIVE),
        name = LONG_NAME, address = "00:1A:7D:DA:71:13"),
)

/** The Qudelix expanded, with the preferred codec in each of its states. */
private fun expandedDevice(state: PreferredState) = when (state) {
    PreferredState.NONE -> device(LDAC_990)
    PreferredState.ACTIVE -> device(LDAC_990, preferred = LDAC_990)
    PreferredState.DIFFERS -> device(ldac(CodecConfig.SAMPLE_RATE_48000, CodecConfig.BITS_PER_SAMPLE_24), preferred = LDAC_990)
}

/** The collapsed cards, one scene per variant. */
internal val collapsedPrototypes = CollapsedVariant.entries.map { variant ->
    Scene("Collapsed, ${variant.title}") { PrototypeScreen(collapsedDevices, expanded = false, collapsed = variant) }
}

/** The expanded card, one scene per variant and state of the preferred codec. */
internal val expandedPrototypes = ExpandedVariant.entries.flatMap { variant ->
    PreferredState.entries.map { state ->
        Scene("Expanded, ${variant.title}, ${state.title}") {
            PrototypeScreen(listOf(expandedDevice(state)), expanded = true, expandedVariant = variant)
        }
    }
}

private class PrototypeScenes : PreviewParameterProvider<Scene> {
    override val values = (collapsedPrototypes + expandedPrototypes).asSequence()
}

@StatePreviews
@Composable
private fun PreferredCodecPrototypesPreview(@PreviewParameter(PrototypeScenes::class) scene: Scene) = scene.content()

@Preview(name = "Small phone, font 200%", device = "spec:width=360dp,height=640dp", showSystemUi = true, fontScale = 2f)
@Composable
private fun PreferredCodecPrototypesLargeFontPreview(@PreviewParameter(PrototypeScenes::class) scene: Scene) = scene.content()
