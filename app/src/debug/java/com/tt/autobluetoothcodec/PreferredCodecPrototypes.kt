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
 * Codec" chip after the codec chips while the preferred codec is active (chosen). Expanded, it has three equal slots
 * for saving, restoring and deleting the preferred codec at its bottom (chosen); these prototypes try colors, button
 * styles, ways to show that the preferred codec is active, and a wide Save button when nothing is saved. Only the
 * parts that differ are prototyped, the codec chips and the codec selection are the app's own.
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
    MONO("Monochrome outline"),
    OUTLINE("Colored outline"),
    ICON("Colored icons"),
    TONAL("Tonal"),
    TONAL_OUTLINE("Tonal with outline"),
    FILLED("Filled"),
}

/** How the Save slot looks while the preferred codec is active. */
internal enum class ActiveTreatment {
    /** Save is disabled like Restore. */
    DISABLED,
    /** Save turns into "Saved" with a check mark and stays enabled. */
    SAVED,
    /** Save and Restore make way for one "Active" label as wide as both. */
    SPAN,
    /** Like [SPAN], shaped like the collapsed card's chip instead of a button. */
    SPAN_CHIP,
}

/** One combination of the experiments. */
internal class Design(
    val name: String,
    val style: ButtonStyle,
    val active: ActiveTreatment = ActiveTreatment.DISABLED,
    /** Whether Save takes the whole row while nothing is saved. */
    val wideSave: Boolean = false,
)

/** What an action is, which decides its color. */
private enum class ActionRole { SAVE, RESTORE, DELETE, NEUTRAL }

/** An action's colors: as text and outline, as a tonal container, and as a filled button. */
private class RoleColors(val accent: Color, val container: Color, val onContainer: Color, val fill: Color, val onFill: Color)

@Composable
private fun roleColors(role: ActionRole): RoleColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return when (role) {
        // Gold, dark enough for text on a light card
        ActionRole.SAVE -> if (dark) RoleColors(Color(0xFFF5C542), Color(0xFF5A4300), Color(0xFFFFDF99), Color(0xFFF5C542), Color(0xFF3F2E00))
            else RoleColors(Color(0xFF8F6A00), Color(0xFFFFDF99), Color(0xFF261A00), Color(0xFFF2B705), Color(0xFF261A00))
        ActionRole.RESTORE -> if (dark) RoleColors(Color(0xFF8ED793), Color(0xFF1E5124), Color(0xFFB6F2B4), Color(0xFF8ED793), Color(0xFF00390D))
            else RoleColors(Color(0xFF2E7D32), Color(0xFFC3EFC0), Color(0xFF002106), Color(0xFF2E7D32), Color.White)
        ActionRole.DELETE -> RoleColors(scheme.error, scheme.errorContainer, scheme.onErrorContainer, scheme.error, scheme.onError)
        ActionRole.NEUTRAL -> RoleColors(scheme.primary, scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.primary, scheme.onPrimary)
    }
}

/** A pill-shaped action in one of the [ButtonStyle]s, disabled like Material's buttons. */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    role: ActionRole,
    style: ButtonStyle,
    enabled: Boolean,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val c = roleColors(if (style == ButtonStyle.MONO) ActionRole.NEUTRAL else role)
    var container = Color.Transparent
    var content = c.accent
    var iconColor = c.accent
    var border: Color? = c.accent
    when (style) {
        ButtonStyle.MONO -> border = scheme.outline
        ButtonStyle.OUTLINE -> {}
        ButtonStyle.ICON -> { content = scheme.onSurface; border = scheme.outline }
        ButtonStyle.TONAL -> { container = c.container; content = c.onContainer; iconColor = c.onContainer; border = null }
        ButtonStyle.TONAL_OUTLINE -> { container = c.container; content = c.onContainer; iconColor = c.onContainer }
        ButtonStyle.FILLED -> { container = c.fill; content = c.onFill; iconColor = c.onFill; border = null }
    }
    if (!enabled) {
        if (container != Color.Transparent)
            container = scheme.onSurface.copy(alpha = 0.12f)
        content = scheme.onSurface.copy(alpha = 0.38f)
        iconColor = content
        border = border?.let { scheme.onSurface.copy(alpha = 0.12f) }
    }
    Surface(
        onClick = {},
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = border?.let { BorderStroke(1.dp, it) },
        modifier = modifier,
    ) {
        PillContent(label, icon, iconColor, showLabel)
    }
}

/** The label that says the preferred codec is active, in the success colors like the collapsed card's chip. */
@Composable
private fun ActiveLabel(showLabel: Boolean, chip: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = if (chip) RoundedCornerShape(8.dp) else CircleShape,
        color = AppColors.status.successContainer,
        contentColor = AppColors.status.onSuccessContainer,
        modifier = modifier,
    ) {
        PillContent("Active", Icons.Default.Check, AppColors.status.onSuccessContainer, showLabel)
    }
}

@Composable
private fun PillContent(label: String, icon: ImageVector, iconColor: Color, showLabel: Boolean) {
    Row(
        Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = if (showLabel) null else label, tint = iconColor, modifier = Modifier.size(18.dp))
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** What fills a slot of the action row. */
private sealed class Cell(val slots: Int) {
    class Action(val label: String, val icon: ImageVector, val role: ActionRole, val enabled: Boolean, slots: Int = 1) : Cell(slots)
    class Active(val chip: Boolean, slots: Int) : Cell(slots)
}

private fun cellsOf(state: PreferredState, design: Design): List<Cell> {
    if (state == PreferredState.NONE && design.wideSave)
        return listOf(Cell.Action("Save", Icons.Default.Star, ActionRole.SAVE, true, slots = 3))
    val delete = Cell.Action("Delete", Icons.Default.Delete, ActionRole.DELETE, state != PreferredState.NONE)
    if (state == PreferredState.ACTIVE) {
        when (design.active) {
            ActiveTreatment.SPAN -> return listOf(Cell.Active(chip = false, slots = 2), delete)
            ActiveTreatment.SPAN_CHIP -> return listOf(Cell.Active(chip = true, slots = 2), delete)
            ActiveTreatment.SAVED -> return listOf(
                Cell.Action("Saved", Icons.Default.Check, ActionRole.SAVE, true),
                Cell.Action("Restore", Icons.Default.Refresh, ActionRole.RESTORE, false),
                delete,
            )
            ActiveTreatment.DISABLED -> {}
        }
    }
    return listOf(
        Cell.Action("Save", Icons.Default.Star, ActionRole.SAVE, state != PreferredState.ACTIVE),
        Cell.Action("Restore", Icons.Default.Refresh, ActionRole.RESTORE, state == PreferredState.DIFFERS),
        delete,
    )
}

/**
 * Three equal slots, at most 420 dp wide together. If the labels don't fit in a third, as with the largest font on
 * a small phone, Save and Restore share the first row and Delete has the second, so the labels stay.
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
        // Icon 18, its gap 8 and the padding 16 on each side
        val labelsFit = listOf("Save", "Saved", "Restore", "Delete", "Active").all {
            with(density) { measurer.measure(it, style, softWrap = false).size.width.toDp() } + 18.dp + 8.dp + 32.dp <= third
        }
        val cells = cellsOf(state, design)

        @Composable
        fun CellView(cell: Cell, width: Dp) {
            val modifier = Modifier.width(width)
            when (cell) {
                is Cell.Action -> ActionButton(cell.label, cell.icon, cell.role, design.style, cell.enabled, showLabel = true, modifier)
                is Cell.Active -> ActiveLabel(showLabel = true, cell.chip, modifier)
            }
        }

        if (labelsFit) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                for (cell in cells)
                    CellView(cell, third * cell.slots + gap * (cell.slots - 1))
            }
        } else {
            // Two columns: the cells of the first two slots on the first row, Delete under Restore on the second
            val half = (full - gap) / 2
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                val first = cells.filter { it !is Cell.Action || it.label != "Delete" }
                val delete = cells.firstOrNull { it is Cell.Action && it.label == "Delete" }
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    for (cell in first)
                        CellView(cell, if (cell.slots >= 2) full else half)
                }
                if (delete != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CellView(delete, half) }
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

/** The combinations to compare: the styles, then the active state and the wide Save in two of them. */
internal val prototypeDesigns = ButtonStyle.entries.map { Design(it.title, it) } + listOf(
    Design("Colored outline, Saved", ButtonStyle.OUTLINE, ActiveTreatment.SAVED),
    Design("Tonal, Saved", ButtonStyle.TONAL, ActiveTreatment.SAVED),
    Design("Colored outline, Active across two slots", ButtonStyle.OUTLINE, ActiveTreatment.SPAN),
    Design("Tonal, Active across two slots", ButtonStyle.TONAL, ActiveTreatment.SPAN),
    Design("Tonal, Active as a chip", ButtonStyle.TONAL, ActiveTreatment.SPAN_CHIP),
    Design("Colored outline, Active, wide Save", ButtonStyle.OUTLINE, ActiveTreatment.SPAN, wideSave = true),
    Design("Tonal, Active, wide Save", ButtonStyle.TONAL, ActiveTreatment.SPAN, wideSave = true),
    Design("Tonal, Active as a chip, wide Save", ButtonStyle.TONAL, ActiveTreatment.SPAN_CHIP, wideSave = true),
)

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
