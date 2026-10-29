package com.tt.autobluetoothcodec

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.OTHER_ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.flushLog
import com.tt.autobluetoothcodec.TestSupport.ldac
import com.tt.autobluetoothcodec.TestSupport.sbc
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** K7 of the test concept: the log's file, its structure checks and its repairs. */
@RunWith(AndroidJUnit4::class)
class EventLogComponentTest {
    private val file get() = File(File(context.filesDir, EventLog.DIRECTORY), EventLog.FILE_NAME)
    private val log get() = EventLog.get(context)

    @Before
    fun setUp() {
        // The structure checks only throw in debuggable builds
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        EventLog.resetForTest(context)
    }

    private fun lines(): List<String> {
        flushLog()
        return file.readLines().filter { it.isNotBlank() }
    }

    private fun writeFile(vararg events: LogEvent, partial: String? = null) {
        file.parentFile!!.mkdirs()
        file.writeText(events.joinToString("") { EventLog.json.encodeToString(it) + "\n" } + (partial ?: ""))
    }

    private fun event(seq: Long, type: EventType, opUID: String? = null) =
        LogEvent(LogEvent.VERSION, seq, 1000 * seq, type, opUID = opUID, device = LogDevice(ADDRESS))

    @Test
    fun structureViolationsThrow() {
        assertThrows(IllegalStateException::class.java) { log.log(EventType.DEVICE_APPEARED) }
        assertThrows(IllegalStateException::class.java) { log.begin(EventType.CODEC_VERIFIED, null) }
        val operation = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        assertThrows(IllegalStateException::class.java) { operation.log(EventType.CODEC_VERIFIED) }
        operation.resolve(EventType.CODEC_VERIFIED)
        assertThrows(IllegalStateException::class.java) { operation.resolve(EventType.ERROR) }
        assertThrows(IllegalStateException::class.java) { operation.log(EventType.SET_CODEC_CONFIG) }
    }

    @Test
    fun appendsUntilTwiceTheLimitThenRewrites() {
        repeat(2 * EventLog.MAX_EVENTS - 1) { log.log(EventType.TEST_LABEL, message = "$it") }
        assertEquals(2 * EventLog.MAX_EVENTS - 1, lines().size)
        assertEquals(EventLog.MAX_EVENTS, log.events.value.size)
        log.log(EventType.TEST_LABEL, message = "last")
        val lines = lines()
        assertEquals(EventLog.MAX_EVENTS, lines.size)
        assertEquals(log.events.value, EventLog.parse(lines))
        assertEquals(2L * EventLog.MAX_EVENTS - 1, log.events.value.last().seq)
    }

    @Test
    fun loadingResolvesInterruptedOperations() {
        writeFile(
            event(0, EventType.A2DP_CONNECTED, "done"),
            event(1, EventType.CODEC_VERIFIED, "done"),
            event(2, EventType.DEVICE_APPEARED, "open"),
            event(3, EventType.SET_CODEC_CONFIG, "open"),
        )
        val last = log.events.value.last()
        assertEquals(EventType.OPERATION_INTERRUPTED, last.type)
        assertEquals("open", last.opUID)
        assertEquals(4L, last.seq)
        assertEquals(LogDevice(ADDRESS), last.device)
        assertEquals(5, lines().size)
        // The next event continues the sequence
        log.log(EventType.TEST_LABEL, message = "next")
        assertEquals(5L, log.events.value.last().seq)
    }

    @Test
    fun partialLinesAreDroppedAndTheFileRewritten() {
        writeFile(event(0, EventType.DEVICE_DISAPPEARED), event(1, EventType.DEVICE_DISAPPEARED), partial = """{"v":1,"seq":2,"ti""")
        assertEquals(2, log.events.value.size)
        assertEquals(2, lines().size)
        assertEquals(log.events.value, EventLog.parse(lines()))
    }

    @Test
    fun clearKeepsOpenOperations() {
        val open = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        val done = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        done.resolve(EventType.CODEC_VERIFIED)
        log.log(EventType.TEST_LABEL, message = "x")
        log.clear()
        assertEquals(listOf(open.uid), log.events.value.map { it.opUID })
        open.resolve(EventType.CODEC_VERIFIED)
        assertEquals(2, EventLog.parse(lines()).size)
    }

    @Test
    fun disablingDeletesTheLogAndStopsLogging() {
        val open = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        log.log(EventType.TEST_LABEL, message = "x")
        log.setEnabled(false)
        assertEquals(emptyList<LogEvent>(), log.events.value)
        open.resolve(EventType.CODEC_VERIFIED)
        log.log(EventType.TEST_LABEL, message = "y")
        val whileDisabled = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        assertEquals(emptyList<LogEvent>(), log.events.value)
        flushLog()
        assertFalse(file.exists())
        // The events of an operation begun while disabled stay out of the log after enabling it
        log.setEnabled(true)
        log.log(EventType.TEST_LABEL, message = "z")
        whileDisabled.resolve(EventType.CODEC_VERIFIED)
        assertEquals(listOf(EventType.TEST_LABEL), log.events.value.map { it.type })
        assertEquals(1, lines().size)
    }

    @Test
    fun operationsSpanningADisabledPeriodStayOutOfTheLog() {
        val before = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        log.setEnabled(false)
        val during = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        log.setEnabled(true)
        before.log(EventType.SET_CODEC_CONFIG)
        during.log(EventType.SET_CODEC_CONFIG)
        before.resolve(EventType.CODEC_VERIFIED)
        during.resolve(EventType.CODEC_VERIFIED)
        assertEquals(emptyList<LogEvent>(), log.events.value)
        // An operation begun after enabling is logged again
        val after = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        after.log(EventType.SET_CODEC_CONFIG)
        after.resolve(EventType.CODEC_VERIFIED)
        assertEquals(listOf(after.uid, after.uid, after.uid), log.events.value.map { it.opUID })
        assertEquals(3, lines().size)
    }

    @Test
    fun loggingStaysDisabledAfterARestart() {
        log.setEnabled(false)
        EventLog.restartForTest()
        assertFalse(log.enabled.value)
        log.log(EventType.TEST_LABEL, message = "x")
        assertEquals(emptyList<LogEvent>(), log.events.value)
        flushLog()
        assertFalse(file.exists())
        log.setEnabled(true)
        EventLog.restartForTest()
        assertTrue(log.enabled.value)
    }

    @Test
    fun aFileLeftWhileDisabledIsDeletedOnLoad() {
        log.setEnabled(false)
        EventLog.restartForTest()
        // The process died before the background thread deleted it
        writeFile(event(0, EventType.TEST_LABEL))
        assertEquals(emptyList<LogEvent>(), log.events.value)
        assertFalse(file.exists())
    }

    @Test
    fun flushRunsAfterTheWritesBeforeIt() {
        repeat(10) { log.log(EventType.TEST_LABEL, message = "$it") }
        var seen = 0
        val read = java.util.concurrent.CountDownLatch(1)
        log.flush {
            seen = file.readLines().count { it.isNotBlank() }
            read.countDown()
        }
        read.await()
        assertEquals(10, seen)
    }

    @Test
    fun lastKnownCodecIsTheMostRecentReadOfTheDevice() {
        assertNull(log.lastKnownCodec(ADDRESS))
        log.log(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, LogDevice(ADDRESS), actual = LogCodecConfig.of(sbc()))
        log.log(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, LogDevice(OTHER_ADDRESS), actual = LogCodecConfig.of(ldac()))
        assertEquals(sbc().toAndroid(), log.lastKnownCodec(ADDRESS.lowercase()))
        val operation = log.begin(EventType.USER_SET_CODEC, LogDevice(ADDRESS))
        operation.resolve(EventType.CODEC_VERIFIED, actual = LogCodecConfig.of(ldac()))
        assertEquals(ldac().toAndroid(), log.lastKnownCodec(ADDRESS))
    }
}
