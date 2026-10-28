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
 * Applies the preferred codec of observed devices, see [CompanionPresence]. Since API 33 the system
 * keeps this service bound while such a device is present, which keeps the process out of the
 * background. On API 31 and 32 it only binds it for the callbacks, so A2DP connecting later is
 * handled by [A2dpConnectReceiver] there.
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

    private val applier by lazy { CodecApplier.get(this) }
    private val presentDevices = HashSet<String>()
    private var receiverRegistered = false

    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED) != BluetoothProfile.STATE_CONNECTED)
                return
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device != null && device.address in presentDevices) {
                val operation = EventLog.get(context).begin(EventType.A2DP_CONNECTED, LogDevice.of(device),
                    message = "companion service")
                applyCodec(device, operation)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        val device = getDevice(address)
        val operation = EventLog.get(this).begin(EventType.DEVICE_APPEARED,
            device?.let { LogDevice.of(it) } ?: LogDevice(address.uppercase()))
        if (device == null) {
            operation.resolve(EventType.ERROR, message = "no Bluetooth adapter")
            return
        }
        if (PreferredCodecStore.get(this, device) == null) {
            operation.resolve(EventType.NO_PREFERRED_CODEC)
            return
        }
        presentDevices.add(device.address)
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, a2dpReceiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        // Applies right away if A2DP is connected already, otherwise the receiver picks it up
        applyCodec(device, operation)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        val device = getDevice(address)
        EventLog.get(this).log(EventType.DEVICE_DISAPPEARED,
            device?.let { LogDevice.of(it) } ?: LogDevice(address.uppercase()))
        if (device == null)
            return
        presentDevices.remove(device.address)
        applier.cancel(device, EventType.CANCELLED, "device disappeared")
    }

    // Pending requests are left to the applier, they end within a few seconds
    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            unregisterReceiver(a2dpReceiver)
            receiverRegistered = false
        }
    }

    private fun applyCodec(device: BluetoothDevice, operation: EventLog.Operation) {
        try {
            applier.apply(device, operation)
        } catch (e: Throwable) {
            Log.e(TAG, "applying the codec of ${device.address} failed", e)
            if (!applier.cancel(operation, EventType.ERROR, e.toString()) && operation.isOpen)
                operation.resolve(EventType.ERROR, message = e.toString())
        }
    }

    private fun getDevice(address: String): BluetoothDevice? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        // getRemoteDevice only accepts upper case addresses
        return adapter.getRemoteDevice(address.uppercase())
    }
}
