package com.tt.autobluetoothcodec

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class EventLogTest {
    private var seq = 0L

    private fun event(type: EventType, opUID: String? = null, message: String? = null) =
        LogEvent(LogEvent.VERSION, seq++, 1_000L * seq, type, opUID = opUID, message = message)

    private fun operation(uid: String, resolved: Boolean = true): List<LogEvent> {
        val events = mutableListOf(
            event(EventType.A2DP_CONNECTED, uid),
            event(EventType.SET_CODEC_CONFIG, uid),
            event(EventType.VALIDATE_CODEC_CONFIG, uid),
        )
        if (resolved)
            events.add(event(EventType.CODEC_VERIFIED, uid))
        return events
    }

    @Test
    fun parse_skipsPartialLines() {
        val events = operation("a")
        val lines = events.map { EventLog.json.encodeToString(it) } + """{"v":1,"seq":4,"ti"""
        assertEquals(events, EventLog.parse(lines))
    }

    @Test
    fun trim_keepsUnderLimit() {
        val events = operation("a") + event(EventType.TEST_LABEL, message = "x")
        assertEquals(events, EventLog.trim(events, 10))
    }

    @Test
    fun trim_dropsOperationsWithoutTrigger() {
        val first = operation("a")
        val label = event(EventType.TEST_LABEL, message = "x")
        val second = operation("b")
        // Cutting at the limit drops the trigger of "a" but keeps two of its events
        val trimmed = EventLog.trim(first + label + second, second.size + 3)
        assertEquals(listOf(label) + second, trimmed)
    }

    @Test
    fun findUnresolved_returnsTriggersOfOpenOperations() {
        val events = operation("a") + operation("b", resolved = false) + event(EventType.DEVICE_DISAPPEARED)
        assertEquals(listOf(events[4]), EventLog.findUnresolved(events))
    }

    @Test
    fun eventTypes_haveEveryFlow() {
        val flows = EventType.entries.map { it.flow }.toSet()
        assertEquals(OperationFlow.entries.toSet(), flows)
    }
}
