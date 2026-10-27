package com.tt.autobluetoothcodec

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Helpers for the Robolectric component tests. */
object TestSupport {
    const val ADDRESS = "0A:1B:2C:3D:4E:5F"
    const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:01"

    val context: Application get() = ApplicationProvider.getApplicationContext()

    fun device(address: String = ADDRESS): BluetoothDevice =
        context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)

    fun grantPermission() = shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    fun denyPermission() = shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    /** Resets the process-wide state and returns an applier that uses [a2dp]. */
    fun resetApp(a2dp: FakeA2dp): CodecApplier {
        // Robolectric's uptime starts at 100 ms, but the applier takes a request's first attempt at
        // 0 to be 500 ms in the past, like it is on a phone that has been running for a while
        runFor(60_000)
        EventLog.resetForTest(context)
        CodecApplier.resetForTest(context, a2dp)
        PreferredCodecStore.clearForTest(context)
        return CodecApplier.get(context)
    }

    /** Runs the main looper's tasks for [millis], advancing the clock. */
    fun runFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    fun runPending() = shadowOf(Looper.getMainLooper()).idle()

    val events: List<LogEvent> get() = EventLog.get(context).events.value

    /** The events of the operation [uid], in order. */
    fun operation(uid: String) = events.filter { it.opUID == uid }

    fun resolution(uid: String) = operation(uid).lastOrNull { it.type.flow == OperationFlow.RESOLUTION }

    /** Waits until the log's background writes are done. */
    fun flushLog() {
        val done = CountDownLatch(1)
        EventLog.get(context).flush { done.countDown() }
        check(done.await(5, TimeUnit.SECONDS)) { "the log didn't flush" }
    }

    fun sbc(sampleRate: Int = CodecConfig.SAMPLE_RATE_44100) = SBCCodecConfig().also {
        it.sampleRate = sampleRate
        it.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_16
    }

    fun ldac(bitrate: Int = LDACCodecConfig.BITRATE_SYSTEM_DEFAULT) = LDACCodecConfig().also {
        it.sampleRate = CodecConfig.SAMPLE_RATE_96000
        it.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_24
        it.bitrate = bitrate
    }
}
