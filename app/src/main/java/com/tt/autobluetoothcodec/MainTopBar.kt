package com.tt.autobluetoothcodec

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Prototype: the main screen's top bar. */
internal enum class TopBarStyle {
    /** Today's: the app's name, a fixed bar. */
    SMALL,
    /** Today's, tinted once the cards scroll under it. */
    SMALL_TINTED,
    /** The name on its own row below the icons, up to two lines, collapsing into a small bar on scroll. */
    MEDIUM,
    /** Medium, with how many devices are connected below the name and the log as an icon. */
    MEDIUM_SUBTITLE,
    /** A bigger name lower down, up to two lines, collapsing on scroll. */
    LARGE,
    /** A dropdown in place of the name, between the connected and the saved devices. */
    DROPDOWN,
    /** The name, and tabs below it between the connected and the saved devices. */
    TABS,
    /**
     * Like Google Calendar's month: the list's name and an arrow, which opens a row of chips below the bar. The
     * name gets shorter, then only the arrow is left, when the bar is too narrow for it.
     */
    TOGGLE_CHIPS,
    /** The same, with Material's segmented buttons in the row. */
    TOGGLE_SEGMENTED,
}

/** Prototype: the top bar to try. */
internal val LocalTopBarStyle = staticCompositionLocalOf { TopBarStyle.SMALL }

/** Prototype: the main screen's two lists. */
internal enum class DeviceView(val label: String, val tab: String) {
    CONNECTED("Connected devices", "Connected"),
    SAVED("Saved devices", "Saved"),
}

/** How the top bar reacts to the cards scrolling: not at all, tinted, or collapsing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMainScrollBehavior(style: TopBarStyle): TopAppBarScrollBehavior? = when (style) {
    TopBarStyle.SMALL -> null
    TopBarStyle.SMALL_TINTED, TopBarStyle.DROPDOWN, TopBarStyle.TABS, TopBarStyle.TOGGLE_CHIPS,
    TopBarStyle.TOGGLE_SEGMENTED -> TopAppBarDefaults.pinnedScrollBehavior()
    TopBarStyle.MEDIUM, TopBarStyle.MEDIUM_SUBTITLE, TopBarStyle.LARGE ->
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainTopBar(
    style: TopBarStyle,
    scrollBehavior: TopAppBarScrollBehavior?,
    deviceCount: Int,
    view: DeviceView,
    onView: (DeviceView) -> Unit,
    actions: MainScreenActions,
) {
    val name = stringResource(R.string.app_name)
    val menu: @Composable () -> Unit = { AppMenu(actions::openLog, actions::openAbout) }
    when (style) {
        TopBarStyle.SMALL, TopBarStyle.SMALL_TINTED ->
            TopAppBar(title = { Text(name) }, actions = { menu() }, scrollBehavior = scrollBehavior)
        TopBarStyle.MEDIUM ->
            MediumTopAppBar(
                title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                actions = { menu() },
                expandedHeight = expandedHeight(name, MaterialTheme.typography.headlineSmall,
                    TopAppBarDefaults.MediumAppBarExpandedHeight),
                scrollBehavior = scrollBehavior
            )
        TopBarStyle.MEDIUM_SUBTITLE ->
            MediumTopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (deviceCount) {
                                0 -> "No devices connected"
                                1 -> "1 device connected"
                                else -> "$deviceCount devices connected"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions::openLog, modifier = Modifier.testTag("log_button")) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_history), contentDescription = "Log")
                    }
                    AppMenu(actions::openLog, actions::openAbout, includeLog = false)
                },
                expandedHeight = expandedHeight(name, MaterialTheme.typography.headlineSmall,
                    TopAppBarDefaults.MediumAppBarExpandedHeight, MaterialTheme.typography.bodyMedium),
                scrollBehavior = scrollBehavior
            )
        TopBarStyle.LARGE ->
            LargeTopAppBar(
                title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                actions = { menu() },
                expandedHeight = expandedHeight(name, MaterialTheme.typography.headlineMedium,
                    TopAppBarDefaults.LargeAppBarExpandedHeight),
                scrollBehavior = scrollBehavior
            )
        TopBarStyle.DROPDOWN ->
            TopAppBar(title = { ViewDropdown(view, onView) }, actions = { menu() }, scrollBehavior = scrollBehavior)
        TopBarStyle.TOGGLE_CHIPS, TopBarStyle.TOGGLE_SEGMENTED -> Column {
            var open by rememberSaveable { mutableStateOf(false) }
            TopAppBar(
                title = { ViewToggle(view, open) { open = !open } },
                actions = { menu() },
                scrollBehavior = scrollBehavior
            )
            val scheme = MaterialTheme.colorScheme
            val scrolled = (scrollBehavior?.state?.overlappedFraction ?: 0f) > 0.01f
            val color by animateColorAsState(if (scrolled) scheme.surfaceContainer else scheme.surface, label = "row")
            AnimatedVisibility(open) {
                Surface(color = color) {
                    if (style == TopBarStyle.TOGGLE_CHIPS) ViewChips(view, onView) else ViewSegments(view, onView)
                }
            }
        }
        TopBarStyle.TABS -> Column {
            TopAppBar(title = { Text(name) }, actions = { menu() }, scrollBehavior = scrollBehavior)
            // The tabs take the bar's tint, so the two read as one bar
            val scheme = MaterialTheme.colorScheme
            val scrolled = (scrollBehavior?.state?.overlappedFraction ?: 0f) > 0.01f
            val color by animateColorAsState(if (scrolled) scheme.surfaceContainer else scheme.surface, label = "tabs")
            PrimaryTabRow(selectedTabIndex = view.ordinal, containerColor = color) {
                for (entry in DeviceView.entries) {
                    Tab(
                        selected = entry == view,
                        onClick = { onView(entry) },
                        text = { Text(entry.tab, maxLines = 1) },
                        modifier = Modifier.testTag("tab_${entry.name.lowercase()}")
                    )
                }
            }
        }
    }
}

/**
 * The height of an expanded medium or large bar: room for the name's lines, one or two at this width and font size,
 * and a [subtitle]'s line below the collapsed row, at least [default]. So the name wraps instead of being cut off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun expandedHeight(name: String, style: TextStyle, default: Dp, subtitle: TextStyle? = null): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // The title's padding on both sides
    val width = LocalConfiguration.current.screenWidthDp.dp - 32.dp
    val lines = with(density) {
        measurer.measure(name, style, maxLines = 2, constraints = Constraints(maxWidth = width.roundToPx())).lineCount
    }
    val lineHeight = with(density) { style.lineHeight.toDp() }
    val subtitleHeight = subtitle?.let { with(density) { it.lineHeight.toDp() } } ?: 0.dp
    // Material's padding below the title, 24 dp for the medium bar and 28 dp for the large one
    val bottom = if (default == TopAppBarDefaults.MediumAppBarExpandedHeight) 24.dp else 28.dp
    val needed = TopAppBarDefaults.MediumAppBarCollapsedHeight + lineHeight * lines + subtitleHeight + bottom
    return maxOf(default, needed)
}

/**
 * The title as a toggle for the row of lists below the bar, like Google Calendar's month: the list's name if it fits
 * beside the arrow, else its short name, else only the arrow.
 */
@Composable
private fun ViewToggle(view: DeviceView, open: Boolean, onToggle: () -> Unit) {
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val style = LocalTextStyle.current
        val density = LocalDensity.current
        // The arrow and the padding around the row
        val around = 24.dp + 12.dp
        val label = listOf(view.label, view.tab).firstOrNull {
            with(density) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width.toDp() } + around <=
                maxWidth
        }
        val turn by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).testTag("device_view")
                .semantics { contentDescription = view.label }
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (label != null)
                Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.rotate(turn))
        }
    }
}

/** The lists as chips in the codec chips' style, by their short names, scrolling sideways if they don't fit. */
@Composable
private fun ViewChips(view: DeviceView, onView: (DeviceView) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (entry in DeviceView.entries) {
            val selected = entry == view
            Surface(
                onClick = { onView(entry) },
                shape = RoundedCornerShape(8.dp),
                color = if (selected) scheme.inversePrimary else Color.Transparent,
                contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier.testTag("view_${entry.name.lowercase()}")
            ) {
                Text(entry.tab, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** The lists as Material's segmented buttons, without the check, in the chips' colors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSegments(view: DeviceView, onView: (DeviceView) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
    ) {
        for (entry in DeviceView.entries) {
            SegmentedButton(
                selected = entry == view,
                onClick = { onView(entry) },
                shape = SegmentedButtonDefaults.itemShape(entry.ordinal, DeviceView.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = scheme.inversePrimary,
                    activeContentColor = scheme.onPrimaryContainer,
                ),
                icon = {},
                modifier = Modifier.testTag("view_${entry.name.lowercase()}")
            ) {
                Text(entry.tab, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The title as a dropdown between the connected and the saved devices. */
@Composable
private fun ViewDropdown(view: DeviceView, onView: (DeviceView) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = true }.testTag("device_view")
                .padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(view.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (entry in DeviceView.entries) {
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    leadingIcon = {
                        if (entry == view) Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onView(entry)
                    }
                )
            }
        }
    }
}
