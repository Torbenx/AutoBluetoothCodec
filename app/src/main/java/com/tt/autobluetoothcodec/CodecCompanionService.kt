package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Applies the preferred codec of observed devices, see [CompanionPresence]. The system keeps this
 * service bound while such a device is present, which keeps the process out of the background.
 *
 * A device appears as soon as its Bluetooth link connects, usually before A2DP is connected. So
 * while a device is present, a runtime receiver waits for A2DP to connect. Unlike the manifest
 * receiver it doesn't queue up behind other apps' receivers.
 *
 * Only the String callbacks are overridden: they exist since API 31, the AssociationInfo variants
 * forward to them, and since API 36 the system still calls those next to onDevicePresenceEvent.
 */
@RequiresApi(31)
class CodecCompanionService : CompanionDeviceService() {
    companion object {
        private const val TAG = "CodecCompanionService"
    }

    // Created on the first device and kept until the system unbinds the service
    private var applier: CodecApplier? = null
    private val presentDevices = HashSet<String>()
    private var receiverRegistered = false

    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED) != BluetoothProfile.STATE_CONNECTED)
                return
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device != null && device.address in presentDevices) {
                Log.i(TAG, "A2DP connected to ${device.address}")
                applyCodec(device)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        Log.i(TAG, "device appeared: $address")
        val device = getDevice(address) ?: return
        if (PreferredCodecStore.get(this, device) == null) {
            Log.i(TAG, "device $address has no preferred codec")
            return
        }
        presentDevices.add(device.address)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, a2dpReceiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        // Applies right away if A2DP is connected already, otherwise the receiver picks it up
        applyCodec(device)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "device disappeared: $address")
        val device = getDevice(address) ?: return
        presentDevices.remove(device.address)
        applier?.cancel(device)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            unregisterReceiver(a2dpReceiver)
            receiverRegistered = false
        }
        applier?.close()
        applier = null
    }

    private fun applyCodec(device: BluetoothDevice) {
        val applier = this.applier ?: CodecApplier(this) {}.also { this.applier = it }
        try {
            applier.apply(device)
        } catch (e: Throwable) {
            Log.e(TAG, "applying the codec of ${device.address} failed", e)
        }
    }

    private fun getDevice(address: String): BluetoothDevice? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        // getRemoteDevice only accepts upper case addresses
        return adapter.getRemoteDevice(address.uppercase())
    }
}
