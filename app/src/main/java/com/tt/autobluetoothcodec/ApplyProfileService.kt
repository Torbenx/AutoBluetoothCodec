package com.tt.autobluetoothcodec

import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Handler
import android.os.Message

class ApplyProfileService : Service(), BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "ApplyProfileService"
        private const val ATTEMPT_FREQUENCY_MILLIS: Long = 1000
        private const val MAX_ATTEMPT_COUNT: Long = 5
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onCreate() {
        super.onCreate()
        pendingDevicesHandler = Handler(mainLooper)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter.getProfileProxy(this, this, BluetoothProfile.A2DP)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "received connect intent, but context has insufficient permissions")
            stopSelf()
        } else {
            val config = LDACCodecConfig()
            config.sampleRate = CodecConfig.SAMPLE_RATE_44100
            config.bitsPerSample = CodecConfig.BITS_PER_SAMPLE_24
            config.bitrate = LDACCodecConfig.BITRATE_HIGH

            pendingConnectedDevices.add(
                PendingDevice(
                    intent!!.extras!!.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!, config))
            if (a2dpProfile != null)
                processPendingDevices()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        if (a2dpProfile != null) {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile)
            a2dpProfile = null
        }
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        a2dpProfile = proxy as BluetoothA2dp
        processPendingDevices()
    }

    override fun onServiceDisconnected(profile: Int) {
        a2dpProfile = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processPendingDevices() {
        val now = System.currentTimeMillis()
        val pendingDevicesCopy = pendingConnectedDevices
        pendingConnectedDevices = ArrayList()
        if (a2dpProfile == null) {
            if (pendingDevicesCopy.isNotEmpty())
                Log.i(TAG, "A2DP disconnected while device were pending")
        }
        for (item in pendingDevicesCopy) {
            try {
                if (now - item.lastApplyAttempt < ATTEMPT_FREQUENCY_MILLIS) {
                    pendingConnectedDevices.add(item)
                    continue
                }
                if (a2dpProfile!!.getConnectionState(item.device) != BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "device ${item.device.name} not connect according to profile")
                    continue
                }

                if (item.attemptCount > 0) {
                    val actualConfig = A2dpProfileUtil.getCurrentCodecConfig(a2dpProfile!!, item.device)
                    if (actualConfig == item.targetConfig.toAndroid()) {
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
                A2dpProfileUtil.setCodecConfig(a2dpProfile!!, item.device, item.targetConfig.toAndroid())
                item.attemptCount += 1
                item.lastApplyAttempt = now
                pendingConnectedDevices.add(item)
            } catch (e: Throwable) {
                Log.i(TAG, "codec change for ${item.device.name} ran into exception", e)
            }
        }
        if (pendingConnectedDevices.isEmpty()) {
            stopSelf()
        } else {
            if (!pendingDevicesHandler!!.hasMessages(100)) {
                val msg = Message.obtain(pendingDevicesHandler) { processPendingDevices() }
                msg.what = 100
                pendingDevicesHandler!!.sendMessageDelayed(msg, ATTEMPT_FREQUENCY_MILLIS)
            }
        }
    }

    data class PendingDevice(
        val device: BluetoothDevice,
        val targetConfig: CodecConfig,
        var lastApplyAttempt: Long = 0,
        var attemptCount: Long = 0)

    private var a2dpProfile: BluetoothA2dp? = null
    private var pendingConnectedDevices = ArrayList<PendingDevice>()
    private var pendingDevicesHandler: Handler? = null
}