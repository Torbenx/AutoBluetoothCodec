package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log

class A2dpConnectReceiver : BroadcastReceiver() {
    private val TAG = "A2dpConnectReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.extras!!.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
            Log.i(TAG, "received intent with unexpected action: ${intent.action}")
            return
        }
        if (intent.extras!!.getInt(BluetoothProfile.EXTRA_STATE) != BluetoothProfile.STATE_CONNECTED)
            return
        if (device == null || PreferredCodecStore.get(context, device) == null)
            return

        context.startService(Intent()
            .setComponent(ComponentName(context, ApplyProfileService::class.java))
            .putExtras(intent))
    }
}