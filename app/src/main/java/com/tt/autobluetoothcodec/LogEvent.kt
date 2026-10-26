package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothDevice
import kotlinx.serialization.Serializable

/**
 * The role of an event in an operation. Each operation starts with a [TRIGGER] event, followed by
 * any number of [ELEMENT] events, and ends with a [RESOLUTION] event. All of them carry the
 * operation's opUID. [STANDALONE] events don't belong to an operation.
 */
enum class OperationFlow { TRIGGER, ELEMENT, RESOLUTION, STANDALONE }

/** The least detailed view of the log that shows an event. */
enum class DetailLevel { SUMMARY, DETAILED }

enum class EventType(val flow: OperationFlow, val level: DetailLevel) {
    // A device appeared to CodecCompanionService, its preferred codec is applied
    DEVICE_APPEARED(OperationFlow.TRIGGER, DetailLevel.DETAILED),
    // A2DP connected to a device with a preferred codec, it's applied
    A2DP_CONNECTED(OperationFlow.TRIGGER, DetailLevel.DETAILED),
    // The user chose a codec in the UI
    USER_SET_CODEC(OperationFlow.TRIGGER, DetailLevel.SUMMARY),
    USER_SAVE_PREFERRED(OperationFlow.TRIGGER, DetailLevel.SUMMARY),
    USER_LINK_DEVICE(OperationFlow.TRIGGER, DetailLevel.SUMMARY),

    // config is the target configuration
    SET_CODEC_CONFIG(OperationFlow.ELEMENT, DetailLevel.DETAILED),
    // Reads the codec back to check whether the target took effect. config is the target
    // configuration, actual what the Bluetooth stack reported
    VALIDATE_CODEC_CONFIG(OperationFlow.ELEMENT, DetailLevel.DETAILED),
    ASSOCIATION_REQUESTED(OperationFlow.ELEMENT, DetailLevel.DETAILED),

    CODEC_VERIFIED(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    CODEC_SET_SENT(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    PREFERRED_SAVED(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    ASSOCIATION_CREATED(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    ASSOCIATION_FAILED(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    MAX_ATTEMPTS(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    NOT_CONNECTED(OperationFlow.RESOLUTION, DetailLevel.DETAILED),
    NO_ASSOCIATION(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    NO_PREFERRED_CODEC(OperationFlow.RESOLUTION, DetailLevel.DETAILED),
    NO_PERMISSION(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    SECURITY_EXCEPTION(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    PROFILE_UNAVAILABLE(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    PROFILE_DISCONNECTED(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    TIMEOUT(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    CANCELLED(OperationFlow.RESOLUTION, DetailLevel.DETAILED),
    // The device is already pending in another operation, the message names it
    ALREADY_PENDING(OperationFlow.RESOLUTION, DetailLevel.DETAILED),
    ERROR(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),
    // Added by EventLog for operations a previous process didn't resolve. That process ended,
    // but why isn't known: it may have crashed, been force-stopped or been killed by the system
    OPERATION_INTERRUPTED(OperationFlow.RESOLUTION, DetailLevel.SUMMARY),

    DEVICE_DISAPPEARED(OperationFlow.STANDALONE, DetailLevel.DETAILED),
    // MainActivity read a codec that differs from the last one known for the device. When the
    // codec changed isn't known, only that it changed since the last known one was logged
    ACTIVITY_OBSERVED_CODEC_CHANGE(OperationFlow.STANDALONE, DetailLevel.DETAILED),
    READ_FAILED(OperationFlow.STANDALONE, DetailLevel.DETAILED),
    PREFERRED_DELETED(OperationFlow.STANDALONE, DetailLevel.SUMMARY),
    DEVICE_UNLINKED(OperationFlow.STANDALONE, DetailLevel.SUMMARY),
    PERMISSION_CHANGED(OperationFlow.STANDALONE, DetailLevel.DETAILED),
    OBSERVING_CHANGED(OperationFlow.STANDALONE, DetailLevel.DETAILED),
    OBSERVING_FAILED(OperationFlow.STANDALONE, DetailLevel.SUMMARY),
    // Sent by tests to mark where a test step starts, the message is the label
    TEST_LABEL(OperationFlow.STANDALONE, DetailLevel.SUMMARY),
}

/**
 * One line of the event log. Only [EventLog] creates events, it checks that they form operations
 * as described by [OperationFlow].
 */
@Serializable
data class LogEvent(
    // Version of this format
    val v: Int,
    // Increases by one for each event
    val seq: Long,
    // Milliseconds since the epoch
    val time: Long,
    val type: EventType,
    val opUID: String? = null,
    val device: LogDevice? = null,
    val config: LogCodecConfig? = null,
    val actual: LogCodecConfig? = null,
    val attempt: Int? = null,
    val message: String? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class LogDevice(val address: String, val name: String? = null) {
    companion object {
        fun of(device: BluetoothDevice): LogDevice {
            // The name requires the Bluetooth permission, which may have been revoked
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            return LogDevice(device.address.uppercase(), name)
        }
    }
}

/** Both models of a codec configuration, [own] is null if the codec is unknown to the app. */
@Serializable
data class LogCodecConfig(val android: AndroidCodecConfig, val own: CodecConfig? = null) {
    companion object {
        fun of(config: AndroidCodecConfig) = LogCodecConfig(config, CodecConfig.fromAndroid(config))

        fun of(config: CodecConfig) = LogCodecConfig(config.toAndroid(), config.clone() as CodecConfig)
    }

    fun describe(): String = own?.describe() ?: "unknown codec (type ${android.codecType})"
}
