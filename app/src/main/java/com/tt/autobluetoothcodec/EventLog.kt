package com.tt.autobluetoothcodec

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.UserManager
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Keeps the last [MAX_EVENTS] events of what the app did, as JSON Lines in [FILE_NAME]. Events are
 * either standalone or part of an operation, see [OperationFlow]: [begin] starts an operation, and
 * the returned [Operation] logs its elements and its resolution. Breaking that structure is a bug,
 * it throws in debuggable builds and is logged to Logcat otherwise.
 *
 * Logging can be disabled with [setEnabled], which deletes the log. While disabled, events only go to
 * Logcat.
 *
 * Must be used on the main thread. The file is written on a background thread, [flush] waits for it.
 */
class EventLog private constructor(context: Context) {
    companion object {
        private const val TAG = "EventLog"
        const val MAX_EVENTS = 1000
        // Excluded from backups by backup_rules.xml and data_extraction_rules.xml
        const val DIRECTORY = "logs"
        const val FILE_NAME = "event_log.jsonl"
        private const val PREFERENCES_NAME = "event_log"
        private const val KEY_ENABLED = "enabled"

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private var dpsPreferences: SharedPreferences? = null

        private var instance: EventLog? = null

        fun get(context: Context): EventLog {
            return instance ?: EventLog(context.applicationContext).also { instance = it }
        }

        /**
         * Drops the log as if the process restarted, for tests. [get] loads it again from the file and
         * the settings. Waits for the pending writes first.
         */
        @VisibleForTesting
        fun restartForTest() {
            instance?.let { log ->
                log.executor.submit {}.get()
                log.executor.shutdown()
            }
            instance = null
        }

        /**
         * Deletes the log and its settings, for tests. [get] loads it again from now on. Waits for the pending writes
         * first, so they don't recreate the file.
         */
        @VisibleForTesting
        fun resetForTest(context: Context) {
            restartForTest()
            // Each test has its own context and files
            dpsPreferences = null
            AtomicFile(getFile(context.applicationContext)).delete()
            getFile(context.createDeviceProtectedStorageContext()).delete()
            preferences(context).edit(commit = true) { clear() }
        }

        /** Parses the lines of the log file, skipping lines that can't be parsed. */
        internal fun parse(lines: List<String>): List<LogEvent> {
            return lines.filter { it.isNotBlank() }.mapNotNull {
                try {
                    json.decodeFromString<LogEvent>(it)
                } catch (e: IllegalArgumentException) {
                    // An interrupted append leaves a partial line
                    null
                }
            }
        }

        /**
         * Keeps the last [max] events, without the events of operations whose trigger was dropped,
         * so every operation in the result starts with its trigger.
         */
        internal fun trim(events: List<LogEvent>, max: Int): List<LogEvent> {
            val kept = if (events.size > max) events.takeLast(max) else events
            val triggered = kept.filter { it.type.flow == OperationFlow.TRIGGER }.mapNotNullTo(HashSet()) { it.opUID }
            return kept.filter { it.opUID == null || it.opUID in triggered }
        }

        /** Returns the trigger events of operations without a resolution. */
        internal fun findUnresolved(events: List<LogEvent>): List<LogEvent> {
            val resolved = events.filter { it.type.flow == OperationFlow.RESOLUTION }.mapNotNullTo(HashSet()) { it.opUID }
            return events.filter { it.type.flow == OperationFlow.TRIGGER && it.opUID !in resolved }
        }

        private fun preferences(context: Context): SharedPreferences {
            return dpsPreferences ?:
                context.applicationContext
                    .createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .also { dpsPreferences = it }
        }

        internal fun getFile(context: Context) = File(File(context.filesDir, DIRECTORY), FILE_NAME)
    }

    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val upsFile = AtomicFile(getFile(context))
    private val dpsFile = getFile(context.createDeviceProtectedStorageContext())
    private val executor = Executors.newSingleThreadExecutor()
    private val preferences = preferences(context)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val openOperations = HashSet<String>()
    // Open operations whose trigger is in the log, the events of the others aren't logged
    private val loggedOperations = HashSet<String>()
    private var nextSeq = 0L
    // Lines in the file, which may contain more events than are kept until it's rewritten
    private var fileLineCount = 0

    private var userStorageInitialized: Boolean = false

    private val mutableEvents = MutableStateFlow<List<LogEvent>>(emptyList())
    private val readEvents: StateFlow<List<LogEvent>> = mutableEvents.asStateFlow()

    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    init {
        dpsFile.parentFile?.mkdirs()
        // Left over if the process died before deleting it
        if (!enabled.value)
            dpsFile.delete()

        updateUserUnlocked()
        if (!userStorageInitialized)
            executor.execute { endPartialLine(dpsFile) }
    }

    /** Ends a line left partial by an interrupted append, so the next event starts a line of its own. */
    private fun endPartialLine(file: File) {
        if (!file.exists())
            return
        try {
            RandomAccessFile(file, "rw").use {
                if (it.length() == 0L)
                    return
                it.seek(it.length() - 1)
                if (it.read() != '\n'.code)
                    it.write('\n'.code)
            }
        } catch (e: IOException) {
            Log.w(TAG, "repairing the device protected log failed", e)
        }
    }

    private fun initWithUnlockedUser() {
        userStorageInitialized = true
        upsFile.baseFile.parentFile?.mkdirs()
        // Left over if the process died before deleting it
        if (!enabled.value)
            upsFile.delete()

        // Wait for pending writes to the device protected file and read its contents
        val future = executor.submit<List<String>> {
            try {
                dpsFile.bufferedReader().use { it.readLines() }
            } catch (e: FileNotFoundException) {
                emptyList()
            } catch (e: IOException) {
                Log.w(TAG, "reading the device protected log failed", e)
                emptyList()
            } finally {
                dpsFile.delete()
            }
        }

        val upsLines = try {
            upsFile.openRead().bufferedReader().use { it.readLines() }
        } catch (e: FileNotFoundException) {
            emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "reading the user protected log failed", e)
            emptyList()
        }
        val upsEvents = parse(upsLines)
        val upsParseDroppedLines = upsEvents.size != upsLines.size
        nextSeq = (upsEvents.maxOfOrNull { it.seq } ?: -1) + 1

        val dpsLines = try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "reading the device protected log timed out, its events are lost", e)
            emptyList()
        } catch (e: ExecutionException) {
            Log.w(TAG, "reading the device protected log failed", e)
            emptyList()
        }
        val dpsEvents = parse(dpsLines).map { it.copy(seq = nextSeq++) }

        val allFileEvents = upsEvents + dpsEvents
        // Add interruption events for stale operations
        val interruptionEvents = findUnresolved(allFileEvents)
            .filter { it.opUID !in openOperations }
            .map {
                val event = LogEvent(
                    LogEvent.VERSION, nextSeq++,
                    System.currentTimeMillis(), EventType.OPERATION_INTERRUPTED,
                    opUID = it.opUID, device = it.device)
                Log.i(TAG, toLogcat(event))
                event
            }

        val allEvents = allFileEvents + interruptionEvents
        mutableEvents.value = trim(allEvents, MAX_EVENTS)
        fileLineCount = allEvents.size
        if (
            mutableEvents.value.size != allEvents.size ||
            interruptionEvents.isNotEmpty() || dpsEvents.isNotEmpty() ||
            upsParseDroppedLines
        ) {
            rewrite()
        }
    }

    fun isUserStorageInitialized() = userStorageInitialized

    fun updateUserUnlocked() {
        if (!userStorageInitialized && userManager.isUserUnlocked)
            initWithUnlockedUser()
    }

    /** Starts an operation with a [OperationFlow.TRIGGER] event. */
    fun begin(
        type: EventType,
        device: LogDevice?,
        config: LogCodecConfig? = null,
        message: String? = null,
    ): Operation {
        checkFlow(type, OperationFlow.TRIGGER)
        val operation = Operation(UUID.randomUUID().toString(), device)
        openOperations.add(operation.uid)
        if (enabled.value)
            loggedOperations.add(operation.uid)
        append(type = type, opUID = operation.uid, device = device, config = config, message = message)
        return operation
    }

    /** Logs a [OperationFlow.STANDALONE] event. */
    fun log(
        type: EventType,
        device: LogDevice? = null,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        message: String? = null,
    ) {
        checkFlow(type, OperationFlow.STANDALONE)
        append(type = type, device = device, config = config, actual = actual, message = message)
    }

    /** Returns the codec most recently read from the device, according to the log. */
    fun lastKnownCodec(address: String): AndroidCodecConfig? {
        checkEventAccess()
        return mutableEvents.value.lastOrNull {
            it.actual != null && it.device?.address.equals(address, ignoreCase = true)
        }?.actual?.android
    }

    /** Removes all events, except those of open operations. */
    fun clear() {
        checkEventAccess()
        mutableEvents.value = mutableEvents.value.filter { it.opUID in openOperations }
        rewrite()
    }

    /**
     * Enables or disables logging. Disabling deletes all events, including those of open operations,
     * whose later events aren't logged either.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled == this.enabled.value)
            return
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        mutableEnabled.value = enabled
        if (!enabled) {
            loggedOperations.clear()
            mutableEvents.value = emptyList()
            fileLineCount = 0
            executor.execute { dpsFile.delete(); upsFile.delete() }
        }
    }

    /** Runs [action] on the background thread once all events logged so far are written. */
    fun flush(action: Runnable) {
        executor.execute(action)
    }

    val events: StateFlow<List<LogEvent>>
        get() {
            checkEventAccess()
            return readEvents
        }

    private fun checkEventAccess() {
        updateUserUnlocked()
        if (!userStorageInitialized)
            violation("events accessed before user protected storage was unlocked")
    }

    private fun checkFlow(type: EventType, expected: OperationFlow) {
        if (type.flow != expected)
            violation("$type is a ${type.flow} event, expected a $expected event")
    }

    private fun violation(message: String) {
        if (debuggable)
            throw IllegalStateException(message)
        Log.w(TAG, "log structure violated: $message")
    }

    /** Logs [event] to Logcat, and to the log if it's [logged] and logging is enabled. */
    private fun append(
        type: EventType,
        opUID: String? = null,
        device: LogDevice? = null,
        config: LogCodecConfig? = null,
        actual: LogCodecConfig? = null,
        attempt: Int? = null,
        message: String? = null,
        logged: Boolean = true
    ) {
        updateUserUnlocked()
        val event = LogEvent(
            v = LogEvent.VERSION,
            seq = nextSeq++,
            time = System.currentTimeMillis(),
            type = type,
            opUID = opUID,
            device = device,
            config = config,
            actual = actual,
            attempt = attempt,
            message = message
        )
        Log.i(TAG, toLogcat(event))
        if (!logged || !enabled.value)
            return

        if (userStorageInitialized) {
            mutableEvents.value = trim(mutableEvents.value + event, MAX_EVENTS)
            if (fileLineCount + 1 >= 2 * MAX_EVENTS) {
                rewrite()
                return
            }
            fileLineCount += 1
            val line = json.encodeToString(event) + "\n"
            executor.execute {
                try {
                    FileOutputStream(upsFile.baseFile, true).use { it.write(line.toByteArray()) }
                } catch (e: IOException) {
                    Log.w(TAG, "appending to the user protected log file failed", e)
                }
            }
        } else {
            val line = json.encodeToString(event) + "\n"
            executor.execute {
                try {
                    FileOutputStream(dpsFile, true).use { it.write(line.toByteArray()) }
                } catch (e: IOException) {
                    Log.w(TAG, "appending to the device protected log file failed", e)
                }
            }
        }
    }

    private fun rewrite() {
        val events = mutableEvents.value
        fileLineCount = events.size
        executor.execute {
            val stream = try {
                upsFile.startWrite()
            } catch (e: IOException) {
                Log.w(TAG, "rewriting the log failed", e)
                return@execute
            }
            try {
                val writer = stream.bufferedWriter()
                for (event in events) {
                    writer.write(json.encodeToString(event))
                    writer.write("\n")
                }
                writer.flush()
                upsFile.finishWrite(stream)
            } catch (e: IOException) {
                Log.w(TAG, "rewriting the log failed", e)
                upsFile.failWrite(stream)
            }
        }
    }

    private fun toLogcat(event: LogEvent): String {
        return buildString {
            append(event.type)
            event.opUID?.let { append(" op=").append(it.take(8)) }
            event.device?.let { append(" device=").append(it.address) }
            event.attempt?.let { append(" attempt=").append(it) }
            event.config?.let { append(" config=").append(it.android) }
            event.actual?.let { append(" actual=").append(it.android) }
            event.message?.let { append(": ").append(it) }
        }
    }

    /**
     * An operation started by [begin]. Its events carry its uid and the device it was started with.
     */
    inner class Operation internal constructor(val uid: String, val device: LogDevice?) {
        val isOpen get() = uid in openOperations

        /** Logs an [OperationFlow.ELEMENT] event. */
        fun log(
            type: EventType,
            config: LogCodecConfig? = null,
            actual: LogCodecConfig? = null,
            attempt: Int? = null,
            message: String? = null,
        ) {
            checkFlow(type, OperationFlow.ELEMENT)
            if (!isOpen)
                violation("$type logged to resolved operation $uid")
            append(
                type = type, opUID = uid, device = device, config = config, actual = actual,
                attempt = attempt, message = message, logged = uid in loggedOperations)
        }

        /** Ends the operation with a [OperationFlow.RESOLUTION] event. */
        fun resolve(
            type: EventType,
            config: LogCodecConfig? = null,
            actual: LogCodecConfig? = null,
            attempt: Int? = null,
            message: String? = null,
        ) {
            checkFlow(type, OperationFlow.RESOLUTION)
            append(
                type = type, opUID = uid, device = device, config = config, actual = actual,
                attempt = attempt, message = message, logged = loggedOperations.remove(uid))
            if (!openOperations.remove(uid))
                violation("$type resolves operation $uid, which is resolved already")
        }
    }
}
