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
 */
class CodecApplier(context: Context, private val onIdle: () -> Unit) : BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "CodecApplier"
        private const val VERIFY_DELAY_MILLIS: Long = 500
        private const val MAX_ATTEMPT_COUNT: Long = 5
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
    fun apply(device: BluetoothDevice) {
        if (closed)
            return
        val config = PreferredCodecStore.get(context, device)
        if (!PermissionUtil.testPermission(context)) {
            Log.i(TAG, "insufficient permissions to apply the codec of ${device.address}")
        } else if (config == null) {
            Log.i(TAG, "device ${device.address} has no preferred codec")
        } else if (pendingDevices.any { it.device == device }) {
            Log.d(TAG, "device ${device.address} is already pending")
            return
        } else {
            pendingDevices.add(PendingDevice(device, config))
            if (a2dpProfile != null)
                processPendingDevices()
            else
                connectProfile()
            return
        }
        if (pendingDevices.isEmpty())
            onIdle()
    }

    fun cancel(device: BluetoothDevice) {
        if (!pendingDevices.removeAll { it.device == device })
            return
        if (pendingDevices.isEmpty()) {
            handler.removeCallbacks(processRunnable)
            onIdle()
        }
    }

    fun close() {
        if (closed)
            return
        closed = true
        handler.removeCallbacks(processRunnable)
        pendingDevices.clear()
        a2dpProfile?.let { closeProfileProxy(it) }
        a2dpProfile = null
    }

    private fun connectProfile() {
        if (profileRequested)
            return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        profileRequested = adapter?.getProfileProxy(context, this, BluetoothProfile.A2DP) ?: false
        if (!profileRequested) {
            Log.i(TAG, "A2DP profile unavailable, dropping ${pendingDevices.size} pending devices")
            pendingDevices.clear()
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
            Log.i(TAG, "A2DP disconnected while devices were pending")
            pendingDevices.clear()
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
            try {
                if (now - item.lastApplyAttempt < VERIFY_DELAY_MILLIS) {
                    pendingDevices.add(item)
                    continue
                }
                if (profile.getConnectionState(item.device) != BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "device ${item.device.name} not connected according to profile")
                    continue
                }

                if (PermissionUtil.isAssociationRequired(context, item.device)) {
                    Log.i(TAG, "no companion association for ${item.device.name}, can't change its codec")
                    continue
                }

                if (item.attemptCount > 0) {
                    val actualConfig = A2dpProfileUtil.getCurrentCodecConfig(profile, item.device)
                    val actualCodecConfig = actualConfig?.let { CodecConfig.fromAndroid(it) }
                    if (actualCodecConfig == item.targetConfig) {
                        Log.i(TAG, "Codec applied correctly on ${item.device.name}")
                        continue
                    }
                    Log.i(TAG, "Codec did not apply correctly on ${item.device.name}, trying it again")
                    Log.d(TAG, "Target: ${item.targetConfig.toAndroid()}")
                    Log.d(TAG, "Actual: $actualConfig")
                }

                if (item.attemptCount >= MAX_ATTEMPT_COUNT) {
                    Log.i(TAG, "Exceeded maximum number of attempts for ${item.device.name}")
                    continue
                }

                Log.i(TAG, "Apply attempt ${item.attemptCount + 1} on ${item.device.name}")
                A2dpProfileUtil.setCodecConfig(profile, item.device, item.targetConfig.toAndroid())
                item.attemptCount += 1
                item.lastApplyAttempt = now
                pendingDevices.add(item)
            } catch (e: SecurityException) {
                Log.i(TAG, "Bluetooth refused codec access for ${item.device.name}", e)
            } catch (e: Throwable) {
                Log.i(TAG, "codec change for ${item.device.name} ran into exception", e)
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
        var lastApplyAttempt: Long = 0,
        var attemptCount: Long = 0)
}
