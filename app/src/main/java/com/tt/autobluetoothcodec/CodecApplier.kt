package com.tt.autobluetoothcodec

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Applies the preferred codec to connected devices and checks that it took effect, trying again a
 * few times if it didn't. Must be used on the main thread. [onIdle] is called whenever no device is
 * pending anymore; the owner calls [close] once it doesn't need the applier anymore.
 *
 * Each [apply] continues an operation of the [EventLog] and resolves it.
 */
class CodecApplier(context: Context, private val onIdle: () -> Unit) : BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "CodecApplier"
        private const val VERIFY_DELAY_MILLIS: Long = 500
        private const val MAX_ATTEMPT_COUNT = 5
    }

    // A BroadcastReceiver's context isn't allowed to bind services, which the profile proxy does
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val processRunnable = Runnable { processPendingDevices() }

    private var a2dpProfile: BluetoothA2dp? = null
    private var profileRequested = false
    private var closed = false
    private var pendingDevices = ArrayList<PendingDevice>()

    /** Applies the preferred codec of [device], which is dropped if A2DP isn't connected to it. */
    fun apply(device: BluetoothDevice, operation: EventLog.Operation) {
        if (closed) {
            operation.resolve(EventType.CANCELLED, message = "applier closed")
            return
        }
        val config = PreferredCodecStore.get(context, device)
        val pending = pendingDevices.firstOrNull { it.device == device }
        if (!PermissionUtil.testPermission(context)) {
            operation.resolve(EventType.NO_PERMISSION)
        } else if (config == null) {
            operation.resolve(EventType.NO_PREFERRED_CODEC)
        } else if (pending != null) {
            operation.resolve(EventType.ALREADY_PENDING, message = "pending in operation ${pending.operation.uid}")
            return
        } else {
            pendingDevices.add(PendingDevice(device, config, operation))
            if (a2dpProfile != null)
                processPendingDevices()
            else
                connectProfile()
            return
        }
        if (pendingDevices.isEmpty())
            onIdle()
    }

    fun cancel(device: BluetoothDevice, message: String) {
        val cancelled = pendingDevices.filter { it.device == device }
        if (cancelled.isEmpty())
            return
        pendingDevices.removeAll(cancelled.toSet())
        for (item in cancelled)
            item.operation.resolve(EventType.CANCELLED, message = message)
        if (pendingDevices.isEmpty()) {
            handler.removeCallbacks(processRunnable)
            onIdle()
        }
    }

    /** Resolves the operations of the devices still pending with [resolution]. */
    fun close(resolution: EventType = EventType.CANCELLED) {
        if (closed)
            return
        closed = true
        handler.removeCallbacks(processRunnable)
        dropPendingDevices(resolution, "applier closed")
        a2dpProfile?.let { closeProfileProxy(it) }
        a2dpProfile = null
    }

    private fun dropPendingDevices(resolution: EventType, message: String?) {
        val dropped = pendingDevices
        pendingDevices = ArrayList()
        for (item in dropped)
            item.operation.resolve(resolution, attempt = item.attemptCount, message = message)
    }

    private fun connectProfile() {
        if (profileRequested)
            return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        profileRequested = adapter?.getProfileProxy(context, this, BluetoothProfile.A2DP) ?: false
        if (!profileRequested) {
            dropPendingDevices(EventType.PROFILE_UNAVAILABLE, null)
            onIdle()
        }
    }

    private fun closeProfileProxy(profile: BluetoothA2dp) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.closeProfileProxy(BluetoothProfile.A2DP, profile)
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        if (closed) {
            (proxy as? BluetoothA2dp)?.let { closeProfileProxy(it) }
            return
        }
        a2dpProfile = proxy as BluetoothA2dp
        processPendingDevices()
    }

    override fun onServiceDisconnected(profile: Int) {
        a2dpProfile = null
        profileRequested = false
        handler.removeCallbacks(processRunnable)
        if (pendingDevices.isNotEmpty()) {
            dropPendingDevices(EventType.PROFILE_DISCONNECTED, null)
            onIdle()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processPendingDevices() {
        handler.removeCallbacks(processRunnable)
        val profile = a2dpProfile ?: return
        val now = SystemClock.uptimeMillis()
        val pendingDevicesCopy = pendingDevices
        pendingDevices = ArrayList()
        for (item in pendingDevicesCopy) {
            val operation = item.operation
            val target = LogCodecConfig.of(item.targetConfig)
            try {
                if (now - item.lastApplyAttempt < VERIFY_DELAY_MILLIS) {
                    pendingDevices.add(item)
                    continue
                }
                if (profile.getConnectionState(item.device) != BluetoothProfile.STATE_CONNECTED) {
                    operation.resolve(EventType.NOT_CONNECTED, attempt = item.attemptCount)
                    continue
                }

                if (PermissionUtil.isAssociationRequired(context, item.device)) {
                    operation.resolve(EventType.NO_ASSOCIATION)
                    continue
                }

                var actual: LogCodecConfig? = null
                if (item.attemptCount > 0) {
                    actual = A2dpProfileUtil.getCurrentCodecConfig(profile, item.device)?.let { LogCodecConfig.of(it) }
                    operation.log(EventType.VALIDATE_CODEC_CONFIG, config = target, actual = actual, attempt = item.attemptCount)
                    if (actual?.own == item.targetConfig) {
                        operation.resolve(EventType.CODEC_VERIFIED, actual = actual, attempt = item.attemptCount)
                        continue
                    }
                }

                if (item.attemptCount >= MAX_ATTEMPT_COUNT) {
                    operation.resolve(EventType.MAX_ATTEMPTS, config = target, actual = actual, attempt = item.attemptCount)
                    continue
                }

                item.attemptCount += 1
                operation.log(EventType.SET_CODEC_CONFIG, config = target, attempt = item.attemptCount)
                A2dpProfileUtil.setCodecConfig(profile, item.device, item.targetConfig.toAndroid())
                item.lastApplyAttempt = now
                pendingDevices.add(item)
            } catch (e: SecurityException) {
                Log.d(TAG, "Bluetooth refused codec access", e)
                operation.resolve(EventType.SECURITY_EXCEPTION, attempt = item.attemptCount, message = e.toString())
            } catch (e: Throwable) {
                Log.d(TAG, "codec change ran into exception", e)
                operation.resolve(EventType.ERROR, attempt = item.attemptCount, message = e.toString())
            }
        }
        if (pendingDevices.isEmpty()) {
            onIdle()
        } else {
            val nextAttempt = pendingDevices.minOf { it.lastApplyAttempt } + VERIFY_DELAY_MILLIS
            handler.postAtTime(processRunnable, nextAttempt)
        }
    }

    private data class PendingDevice(
        val device: BluetoothDevice,
        val targetConfig: CodecConfig,
        val operation: EventLog.Operation,
        var lastApplyAttempt: Long = 0,
        var attemptCount: Int = 0)
}
