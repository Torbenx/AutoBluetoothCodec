package com.tt.autobluetoothcodec

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Shows the [EventLog], newest first. An operation is shown as one entry, the [DetailLevel] decides
 * whether its events are listed, and in the debug level each event can be expanded to its JSON.
 */
class LogActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LogActivity"
        private const val PREFERENCES_NAME = "log_viewer"
        private const val KEY_DETAIL_LEVEL = "detail_level"

        // Shown in the error color
        private val FAILURES = setOf(
            EventType.ASSOCIATION_FAILED,
            EventType.MAX_ATTEMPTS,
            EventType.NO_ASSOCIATION,
            EventType.NO_PERMISSION,
            EventType.SECURITY_EXCEPTION,
            EventType.PROFILE_UNAVAILABLE,
            EventType.PROFILE_DISCONNECTED,
            EventType.TIMEOUT,
            EventType.ERROR,
            EventType.OPERATION_INTERRUPTED,
            EventType.READ_FAILED,
            EventType.OBSERVING_FAILED,
        )

        // Their message is part of the description, so it isn't shown separately
        private val MESSAGE_DESCRIBED = setOf(
            EventType.PERMISSION_CHANGED,
            EventType.OBSERVING_CHANGED,
            EventType.TEST_LABEL,
        )
    }

    private val log by lazy { EventLog.get(this) }
    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson = Json(from = EventLog.json) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    private var detailLevel by mutableStateOf(DetailLevel.SUMMARY)
    private var showExportDialog by mutableStateOf(false)
    private var showClearDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        detailLevel = preferences().getString(KEY_DETAIL_LEVEL, null)
            ?.let { name -> DetailLevel.entries.firstOrNull { it.name == name } }
            ?: DetailLevel.SUMMARY
        setContent { LogScreen() }
    }

    private fun preferences() = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun changeDetailLevel(level: DetailLevel) {
        detailLevel = level
        preferences().edit { putString(KEY_DETAIL_LEVEL, level.name) }
    }

    private fun shareLog(mode: LogExport.Mode) {
        val uri = try {
            LogExport.write(this, log.events.value, mode)
        } catch (e: IOException) {
            Log.e(TAG, "exporting the log failed", e)
            Toast.makeText(this, "Exporting the log failed", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share log"))
    }

    /** The events of an operation in order, or a single standalone event. */
    private class LogEntry(val events: List<LogEvent>) {
        val first get() = events.first()
        val isOperation get() = first.type.flow == OperationFlow.TRIGGER
        val resolution get() = events.lastOrNull { it.type.flow == OperationFlow.RESOLUTION }
        // The least detailed level that shows any of the events
        val level get() = events.minOf { it.type.level }
    }

    private fun buildEntries(events: List<LogEvent>): List<LogEntry> {
        val operations = HashMap<String, MutableList<LogEvent>>()
        val entries = ArrayList<MutableList<LogEvent>>()
        for (event in events) {
            val uid = event.opUID
            val operation = uid?.let { operations[it] }
            if (operation != null) {
                operation.add(event)
            } else {
                val entry = mutableListOf(event)
                if (uid != null)
                    operations[uid] = entry
                entries.add(entry)
            }
        }
        return entries.map { LogEntry(it) }
    }

    private fun describe(event: LogEvent): String {
        val config = event.config?.describe()
        val actual = event.actual?.describe() ?: "no codec"
        return when (event.type) {
            EventType.DEVICE_APPEARED -> "Device connected"
            EventType.A2DP_CONNECTED -> "Audio connected"
            EventType.USER_SET_CODEC -> "Codec chosen: $config"
            EventType.USER_SAVE_PREFERRED -> "Saving preferred codec: $config"
            EventType.USER_LINK_DEVICE -> "Linking device"
            EventType.SET_CODEC_CONFIG -> "Setting codec" + (event.attempt?.let { ", attempt $it" } ?: "") + ": $config"
            EventType.VALIDATE_CODEC_CONFIG -> when {
                event.actual == null -> "Codec not applied yet, no codec reported"
                event.actual.own != event.config?.own -> "Codec not applied yet, active: $actual"
                else -> "Codec validated: $actual"
            }
            EventType.ASSOCIATION_REQUESTED -> "Asked to link the device"
            EventType.CODEC_VERIFIED -> "Codec applied: $actual"
            EventType.CODEC_SET_SENT -> "Codec change sent"
            EventType.PREFERRED_SAVED -> "Preferred codec saved"
            EventType.ASSOCIATION_CREATED -> "Device linked"
            EventType.ASSOCIATION_FAILED -> "Device wasn't linked"
            EventType.MAX_ATTEMPTS -> "Codec not applied after ${event.attempt} attempts" +
                    (event.actual?.let { ", still ${it.describe()}" } ?: "")
            EventType.NOT_CONNECTED -> "Audio not connected yet"
            EventType.NO_ASSOCIATION -> "Device isn't linked, so the codec can't be changed"
            EventType.NO_PREFERRED_CODEC -> "No preferred codec"
            EventType.NO_PERMISSION -> "Nearby devices permission missing"
            EventType.SECURITY_EXCEPTION -> "Bluetooth refused the codec change"
            EventType.PROFILE_UNAVAILABLE -> "Bluetooth audio unavailable"
            EventType.PROFILE_DISCONNECTED -> "Bluetooth audio service disconnected"
            EventType.TIMEOUT -> "Timed out"
            EventType.CANCELLED -> "Cancelled"
            EventType.ALREADY_PENDING -> "Already in progress"
            EventType.ERROR -> "Failed"
            EventType.OPERATION_INTERRUPTED -> "Never finished, the app stopped running first"
            EventType.DEVICE_DISAPPEARED -> "Device disconnected"
            EventType.ACTIVITY_OBSERVED_CODEC_CHANGE -> "App observed a different active codec: $actual"
            EventType.READ_FAILED -> "Couldn't read the codec"
            EventType.PREFERRED_DELETED -> "Preferred codec deleted"
            EventType.DEVICE_UNLINKED -> "Device unlinked"
            EventType.PERMISSION_CHANGED -> "Nearby devices permission ${event.message}"
            EventType.OBSERVING_CHANGED -> "Presence: ${event.message}"
            EventType.OBSERVING_FAILED -> "Couldn't observe the device's presence"
            EventType.TEST_LABEL -> "Test: ${event.message}"
        }
    }

    /** The time from the trigger to the resolution, null if it isn't worth showing. */
    private fun describeDuration(entry: LogEntry): String? {
        val resolution = entry.resolution ?: return null
        // Resolved when the app started again, which says nothing about the operation
        if (resolution.type == EventType.OPERATION_INTERRUPTED)
            return null
        val millis = resolution.time - entry.first.time
        return when {
            millis < 100 -> null
            millis < 60_000 -> String.format(Locale.getDefault(), "%.1f s", millis / 1000.0)
            millis < 3_600_000 -> "${millis / 60_000} min ${millis / 1000 % 60} s"
            else -> "${millis / 3_600_000} h ${millis / 60_000 % 60} min"
        }
    }

    private fun describeOperation(entry: LogEntry): String {
        val trigger = describe(entry.first)
        val resolution = entry.resolution ?: return "$trigger → in progress"
        return "$trigger → ${describe(resolution)}" + (describeDuration(entry)?.let { " ($it)" } ?: "")
    }

    private fun zoned(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())

    private fun describeDay(day: LocalDate): String {
        val today = LocalDate.now()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> dateFormatter.format(day)
        }
    }

    @Composable
    private fun eventColor(type: EventType): Color {
        return if (type in FAILURES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    }

    @Composable
    private fun EventRow(event: LogEvent, suffix: String = "") {
        var expanded by remember(event.seq) { mutableStateOf(false) }
        val expandable = detailLevel == DetailLevel.DEBUG
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(vertical = 2.dp)
        ) {
            // The time is smaller than the text, aligning their baselines keeps it on the first line
            Row {
                Text(
                    timeFormatter.format(zoned(event.time)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp).alignByBaseline()
                )
                Text(
                    describe(event) + suffix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = eventColor(event.type),
                    modifier = Modifier.alignByBaseline()
                )
            }
            val message = event.message
            if (message != null && event.type !in MESSAGE_DESCRIBED) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 80.dp)
                )
            }
            if (expandable && expanded) {
                // Scrolls instead of wrapping, wrapped JSON is hard to read
                Text(
                    prettyJson.encodeToString(event),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier
                        .padding(start = 80.dp, top = 4.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
        }
    }

    @Composable
    private fun EntryItem(entry: LogEntry) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val device = entry.first.device
            if (device != null) {
                Text(
                    device.name ?: device.address,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!entry.isOperation) {
                EventRow(entry.first)
            } else if (detailLevel == DetailLevel.SUMMARY) {
                OperationSummary(entry)
            } else {
                // The trigger and the resolution frame the operation, so they are always listed
                val resolution = entry.resolution
                val duration = describeDuration(entry)?.let { " ($it)" } ?: ""
                for (event in entry.events) {
                    if (event.type.flow != OperationFlow.ELEMENT || event.type.level <= detailLevel)
                        EventRow(event, if (event === resolution) duration else "")
                }
            }
        }
    }

    @Composable
    private fun OperationSummary(entry: LogEntry) {
        val resolution = entry.resolution
        Row {
            Text(
                timeFormatter.format(zoned(entry.first.time)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp).alignByBaseline()
            )
            Text(
                describeOperation(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = if (resolution != null) eventColor(resolution.type) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alignByBaseline()
            )
        }
        // "Failed" alone doesn't say what went wrong
        val message = resolution?.message
        if (resolution?.type == EventType.ERROR && message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 80.dp)
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DetailLevelSelection() {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val levels = DetailLevel.entries
            levels.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = level == detailLevel,
                    onClick = { changeDetailLevel(level) },
                    shape = SegmentedButtonDefaults.itemShape(index, levels.size)
                ) {
                    Text(when (level) {
                        DetailLevel.SUMMARY -> "Summary"
                        DetailLevel.DETAILED -> "Detailed"
                        DetailLevel.DEBUG -> "Debug"
                    })
                }
            }
        }
    }

    @Composable
    private fun ExportOption(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ExportDialog() {
        var mode by remember { mutableStateOf(LogExport.Mode.ANONYMISED) }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Share log") },
            text = {
                Column(Modifier.selectableGroup()) {
                    ExportOption(
                        selected = mode == LogExport.Mode.ANONYMISED,
                        title = "Anonymised (recommended)",
                        description = "Bluetooth addresses are replaced by random ids and device names are removed. Use this when posting the log for diagnosis.",
                        onSelect = { mode = LogExport.Mode.ANONYMISED }
                    )
                    ExportOption(
                        selected = mode == LogExport.Mode.FULL,
                        title = "Full",
                        description = "Includes the Bluetooth addresses and names of your devices.",
                        onSelect = { mode = LogExport.Mode.FULL }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    shareLog(mode)
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            }
        )
    }

    @Composable
    private fun ClearDialog() {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear log?") },
            text = { Text("All logged events are deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    log.clear()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LogScreen() {
        val events by log.events.collectAsState()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Log") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showExportDialog = true }, enabled = events.isNotEmpty()) {
                            Icon(Icons.Default.Share, contentDescription = "Share log")
                        }
                        IconButton(onClick = { showClearDialog = true }, enabled = events.isNotEmpty()) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear log")
                        }
                    }
                )
            },
            // Also keeps the content clear of display cutouts in landscape
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            // The bottom padding is inside the list, so the entries scroll behind the navigation bar
            Column(
                Modifier.fillMaxSize().padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection)
                )
            ) {
                DetailLevelSelection()
                val entries = buildEntries(events).filter { it.level <= detailLevel }.reversed()
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No events logged yet", style = MaterialTheme.typography.bodyLarge)
                    }
                    return@Column
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.Top
                ) {
                    var day: LocalDate? = null
                    for (entry in entries) {
                        val entryDay = zoned(entry.first.time).toLocalDate()
                        if (entryDay != day) {
                            day = entryDay
                            item(key = "day $entryDay") {
                                Text(
                                    describeDay(entryDay),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                                )
                            }
                        } else {
                            item(key = "divider ${entry.first.seq}") { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                        }
                        item(key = entry.first.seq) { EntryItem(entry) }
                    }
                }
            }
        }
        if (showExportDialog)
            ExportDialog()
        if (showClearDialog)
            ClearDialog()
    }
}
