package com.tt.autobluetoothcodec

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tt.autobluetoothcodec.SampleData.LONG_NAME
import com.tt.autobluetoothcodec.SampleData.aac
import com.tt.autobluetoothcodec.SampleData.device
import com.tt.autobluetoothcodec.SampleData.ldac
import com.tt.autobluetoothcodec.SampleData.sony

/*
 * Prototypes for the device card without the status button in its header. Collapsed, the card shows a "Preferred
 * Codec" chip after the codec chips while the preferred codec is active. Expanded, it has three equal slots at its
 * bottom and only shows what's available: a wide Save when nothing is saved, an "Active" label as wide as two slots
 * and Clear while the preferred codec is active, and Save, Restore and Clear when it differs. These prototypes try
 * colors for Save and layouts for rows too narrow for three labels. Only the parts that differ are prototyped, the
 * codec chips and the codec selection are the app's own.
 */

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

/** How the action buttons are drawn. */
internal enum class ButtonStyle(val title: String) {
    OUTLINE("Colored outline"),
    TONAL("Tonal"),
}

/** The color of Save. */
internal enum class SaveColor(val title: String) {
    GOLD("Gold"),
    AMBER("Amber"),
    GOLD_STAR("Neutral, gold star"),
    PRIMARY("Theme purple"),
}

/** How the row looks when the labels of three buttons don't fit in a third of it. */
internal enum class NarrowLayout(val title: String) {
    /** Save and Restore on the first row, Clear under Restore on the second. */
    TWO_ROWS("Two rows"),
    /** Every button and the Active label only show their icons. */
    ICONS("Icons only"),
    /** The one-slot buttons only show their icons, the wider Active label and Save keep their labels. */
    MIXED("Icons where needed"),
}

/** One combination of the experiments. */
internal class Design(
    val name: String,
    val style: ButtonStyle,
    val saveColor: SaveColor = SaveColor.GOLD,
    val narrow: NarrowLayout = NarrowLayout.MIXED,
)

/** What an action is, which decides its color. */
private enum class ActionRole { SAVE, RESTORE, CLEAR }

/**
 * An action's colors: [accent] for the label (and the outline and icon unless they're set), [container] and
 * [onContainer] for the tonal style.
 */
private class RoleColors(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val outline: Color = accent,
    val icon: Color? = null,
)

@Composable
private fun roleColors(role: ActionRole, saveColor: SaveColor): RoleColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return when (role) {
        ActionRole.SAVE -> when (saveColor) {
            // Gold, dark enough for text on a light card
            SaveColor.GOLD -> if (dark) RoleColors(Color(0xFFF5C542), Color(0xFF5A4300), Color(0xFFFFDF99))
                else RoleColors(Color(0xFF8F6A00), Color(0xFFFFDF99), Color(0xFF261A00))
            // Warmer, toward orange
            SaveColor.AMBER -> if (dark) RoleColors(Color(0xFFFFB951), Color(0xFF6A3D00), Color(0xFFFFDDB0))
                else RoleColors(Color(0xFF9C5700), Color(0xFFFFDDB0), Color(0xFF3E2300))
            // A neutral button, only the star in gold
            SaveColor.GOLD_STAR -> RoleColors(
                scheme.onSurface, scheme.surfaceContainerHigh, scheme.onSurface, outline = scheme.outline,
                icon = if (dark) Color(0xFFF5C542) else Color(0xFFC48C00)
            )
            SaveColor.PRIMARY -> RoleColors(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer)
        }
        ActionRole.RESTORE -> if (dark) RoleColors(Color(0xFF8ED793), Color(0xFF1E5124), Color(0xFFB6F2B4))
            else RoleColors(Color(0xFF2E7D32), Color(0xFFC3EFC0), Color(0xFF002106))
        ActionRole.CLEAR -> RoleColors(scheme.error, scheme.errorContainer, scheme.onErrorContainer)
    }
}

/** A pill-shaped action in one of the [ButtonStyle]s. */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    role: ActionRole,
    design: Design,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = roleColors(role, design.saveColor)
    val outlined = design.style == ButtonStyle.OUTLINE
    Surface(
        onClick = {},
        shape = CircleShape,
        color = if (outlined) Color.Transparent else c.container,
        contentColor = if (outlined) c.accent else c.onContainer,
        border = if (outlined) BorderStroke(1.dp, c.outline) else null,
        modifier = modifier,
    ) {
        PillContent(label, icon, c.icon ?: if (outlined) c.accent else c.onContainer, showLabel)
    }
}

/** The label that says the preferred codec is active, shaped and colored like the collapsed card's chip. */
@Composable
private fun ActiveLabel(showLabel: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = AppColors.status.successContainer,
        contentColor = AppColors.status.onSuccessContainer,
        modifier = modifier,
    ) {
        PillContent("Active", Icons.Default.Check, AppColors.status.onSuccessContainer, showLabel)
    }
}

@Composable
private fun PillContent(label: String, icon: ImageVector, iconColor: Color, showLabel: Boolean) {
    val style = MaterialTheme.typography.labelLarge
    // 18 dp at the normal font size, growing with the label's text
    val iconSize = with(LocalDensity.current) { (style.fontSize * (18f / 14f)).toDp() }
    Row(
        Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = if (showLabel) null else label, tint = iconColor, modifier = Modifier.size(iconSize))
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = style, maxLines = 1)
        } else {
            // As tall as a label, so a button with just its icon is as tall as one with its label
            Text("", style = style)
        }
    }
}

/** What fills a slot of the action row. */
private sealed class Cell(val slots: Int) {
    class Action(val label: String, val icon: ImageVector, val role: ActionRole, slots: Int = 1) : Cell(slots)
    class Active(slots: Int) : Cell(slots)
}

/** Only what's available: a wide Save, Active and Clear, or all three actions. */
private fun cellsOf(state: PreferredState): List<Cell> = when (state) {
    PreferredState.NONE -> listOf(Cell.Action("Save", Icons.Default.Star, ActionRole.SAVE, slots = 3))
    PreferredState.ACTIVE -> listOf(Cell.Active(slots = 2), Cell.Action("Clear", Icons.Default.Delete, ActionRole.CLEAR))
    PreferredState.DIFFERS -> listOf(
        Cell.Action("Save", Icons.Default.Star, ActionRole.SAVE),
        Cell.Action("Restore", Icons.Default.Refresh, ActionRole.RESTORE),
        Cell.Action("Clear", Icons.Default.Delete, ActionRole.CLEAR),
    )
}

/**
 * Three equal slots, at most 420 dp wide together. When the labels of the one-slot buttons don't fit in a third of
 * the row, as with large fonts or on small phones, the [NarrowLayout] of the design decides.
 */
@Composable
private fun SlotActions(state: PreferredState, design: Design) {
    BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        val gap = 8.dp
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val full = maxWidth
        val third = (full - gap * 2) / 3
        // The icon, its gap 8 and the padding 16 on each side
        val iconSize = with(density) { (style.fontSize * (18f / 14f)).toDp() }
        fun fits(label: String, width: Dp) =
            with(density) { measurer.measure(label, style, softWrap = false).size.width.toDp() } + iconSize + 8.dp + 32.dp <= width
        // The same for every state, so a button doesn't lose its label when another one appears
        val thirdsFit = listOf("Save", "Restore", "Clear").all { fits(it, third) }
        val cells = cellsOf(state)

        @Composable
        fun CellView(cell: Cell, width: Dp, showLabel: Boolean) {
            val modifier = Modifier.width(width)
            when (cell) {
                is Cell.Action -> ActionButton(cell.label, cell.icon, cell.role, design, showLabel, modifier)
                is Cell.Active -> ActiveLabel(showLabel, modifier)
            }
        }

        fun labelOf(cell: Cell) = if (cell is Cell.Action) cell.label else "Active"

        if (thirdsFit || design.narrow != NarrowLayout.TWO_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                for (cell in cells) {
                    val width = third * cell.slots + gap * (cell.slots - 1)
                    val showLabel = when {
                        thirdsFit -> true
                        design.narrow == NarrowLayout.ICONS -> false
                        else -> cell.slots > 1 && fits(labelOf(cell), width)
                    }
                    CellView(cell, width, showLabel)
                }
            }
        } else {
            // Two columns: Save and Restore, or Active, on the first row, Clear under Restore on the second
            val half = (full - gap) / 2
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                val first = cells.filter { it !is Cell.Action || it.label != "Clear" }
                val clear = cells.firstOrNull { it is Cell.Action && it.label == "Clear" }
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    for (cell in first)
                        CellView(cell, if (cell.slots >= 2) full else half, showLabel = true)
                }
                if (clear != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CellView(clear, half, showLabel = true) }
                }
            }
        }
    }
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

/** The codec chips of the collapsed card, like the app shows them, and the preferred chip after them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsedContent(device: DeviceData) {
    val config = device.codecConfig ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (stateOf(device) == PreferredState.ACTIVE)
            PreferredChip()
    }
}

/** A device card whose header only has the name. */
@Composable
private fun PrototypeCard(device: DeviceData, design: Design?) {
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
            }
            Spacer(Modifier.height(8.dp))
            if (design != null) {
                CodecConfigSelection(device) {}
                Spacer(Modifier.height(16.dp))
                Text("Preferred Codec", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                SlotActions(stateOf(device), design)
            } else {
                CollapsedContent(device)
            }
        }
    }
}

/** The main screen with the devices, expanded in [design] or collapsed if it's null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrototypeScreen(devices: List<DeviceData>, design: Design?) = AppTheme {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        // Expanded, the actions are at the bottom of the card, which may be out of view
        val scroll = rememberScrollState(if (design != null) Int.MAX_VALUE else 0)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(innerPadding)) {
            for (device in devices)
                PrototypeCard(device, design)
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

/** The colors of Save in both styles, then the layouts for narrow rows. */
internal val prototypeDesigns =
    ButtonStyle.entries.flatMap { style -> SaveColor.entries.map { Design("${style.title}, ${it.title}", style, it) } } +
        NarrowLayout.entries.map { Design("Narrow, ${it.title}", ButtonStyle.TONAL, narrow = it) }

/** The collapsed cards with the preferred chip after the codec chips. */
internal val collapsedPrototype = Scene("Collapsed") { PrototypeScreen(collapsedDevices, null) }

/** The expanded card, one scene per design and state of the preferred codec. */
internal val expandedPrototypes = prototypeDesigns.flatMap { design ->
    PreferredState.entries.map { state ->
        Scene("Expanded, ${design.name}, ${state.title}") { PrototypeScreen(listOf(expandedDevice(state)), design) }
    }
}

private class PrototypeScenes : PreviewParameterProvider<Scene> {
    override val values = (listOf(collapsedPrototype) + expandedPrototypes).asSequence()
}

@StatePreviews
@Composable
private fun PreferredCodecPrototypesPreview(@PreviewParameter(PrototypeScenes::class) scene: Scene) = scene.content()

@Preview(name = "Small phone, font 200%", device = "spec:width=360dp,height=640dp", showSystemUi = true, fontScale = 2f)
@Composable
private fun PreferredCodecPrototypesLargeFontPreview(@PreviewParameter(PrototypeScenes::class) scene: Scene) = scene.content()
