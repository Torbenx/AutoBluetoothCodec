package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.companion.DeviceNotAssociatedException
import android.companion.ObservingDevicePresenceRequest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Since API 31 the system binds [CodecCompanionService] while an observed device is present. A
 * device is observed while it's associated and has a preferred codec. Phones without the companion
 * device feature use [A2dpConnectReceiver] instead, like before API 31.
 */
object CompanionPresence {
    private const val TAG = "CompanionPresence"

    /** [logChange] logs the observing state to the [EventLog], failures are logged anyway. */
    fun update(context: Context, device: BluetoothDevice, logChange: Boolean = true) {
        if (Build.VERSION.SDK_INT < 31 || !PermissionUtil.canAssociate(context))
            return
        setObserving(context, device, PreferredCodecStore.get(context, device) != null, logChange)
    }

    fun stopObserving(context: Context, device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT < 31 || !PermissionUtil.canAssociate(context))
            return
        setObserving(context, device, false, logChange = true)
    }

    /**
     * Brings the observed devices and the enabled receiver in line with the stored state.
     *
     * Enabling the receiver is required, the manifest can't depend on the companion device feature.
     * The system persists observing across process deaths and reboots, and [update] is called on
     * every change, so the loop only repairs drift: associations kept after clearing app data or
     * a failed call. It can't repair a preferred codec without an association, which requires the user.
     */
    fun syncAll(context: Context) {
        updateReceiverEnabled(context)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (address in PermissionUtil.getAssociatedAddresses(context)) {
            // getRemoteDevice only accepts upper case addresses. This runs on every start of the
            // app and usually changes nothing, so only failures are logged.
            update(context, adapter.getRemoteDevice(address.uppercase()), logChange = false)
        }
    }

    @RequiresApi(31)
    private fun setObserving(context: Context, device: BluetoothDevice, observe: Boolean, logChange: Boolean) {
        if (Build.VERSION.SDK_INT >= 36) {
            setObservingById(context, device, observe, logChange)
            return
        }
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        for (address in PermissionUtil.findAssociatedAddresses(context, device)) {
            try {
                @Suppress("DEPRECATION")
                if (observe)
                    companionManager.startObservingDevicePresence(address)
                else
                    companionManager.stopObservingDevicePresence(address)
                Log.d(TAG, (if (observe) "observing" else "not observing") + " presence of $address")
                if (logChange)
                    logObserving(context, device, observe)
            } catch (e: DeviceNotAssociatedException) {
                Log.w(TAG, "can't change observing $address, it isn't associated", e)
                EventLog.get(context).log(EventType.OBSERVING_FAILED, LogDevice.of(device), message = e.toString())
            }
        }
    }

    /**
     * Since API 36 the address variants are deprecated, the system implements them by looking up
     * the association and observing it by its id.
     */
    @RequiresApi(36)
    private fun setObservingById(context: Context, device: BluetoothDevice, observe: Boolean, logChange: Boolean) {
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        for (association in PermissionUtil.findAssociations(context, device)) {
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build()
            try {
                if (observe)
                    companionManager.startObservingDevicePresence(request)
                else
                    companionManager.stopObservingDevicePresence(request)
                Log.d(TAG, (if (observe) "observing" else "not observing") + " presence of association ${association.id}")
                if (logChange)
                    logObserving(context, device, observe)
            } catch (e: IllegalArgumentException) {
                // The association was removed meanwhile
                Log.w(TAG, "can't change observing association ${association.id}", e)
                EventLog.get(context).log(EventType.OBSERVING_FAILED, LogDevice.of(device), message = e.toString())
            }
        }
    }

    private fun logObserving(context: Context, device: BluetoothDevice, observe: Boolean) {
        EventLog.get(context).log(EventType.OBSERVING_CHANGED, LogDevice.of(device),
            message = if (observe) "observing presence" else "not observing presence")
    }

    /**
     * The manifest enables the receiver only before API 31. Since then it's still needed on phones
     * without the companion device feature.
     */
    private fun updateReceiverEnabled(context: Context) {
        val needed = Build.VERSION.SDK_INT >= 31 && !PermissionUtil.canAssociate(context)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, A2dpConnectReceiver::class.java),
            if (needed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }
}
