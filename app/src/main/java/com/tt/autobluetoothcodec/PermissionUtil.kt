package com.tt.autobluetoothcodec

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

object PermissionUtil {
    /**
     * Below API 31 the legacy BLUETOOTH permission is an install time permission declared in the
     * manifest, so only BLUETOOTH_CONNECT has to be requested at runtime.
     */
    fun testPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Companion device associations can only be requested through the API 33 callbacks. Before
     * API 33 the Bluetooth stack doesn't check for an association, and it isn't an updatable
     * module there, so there is never a need to request one.
     */
    fun canAssociate(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 33
                && context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    fun hasAssociation(context: Context, device: BluetoothDevice): Boolean {
        return canAssociate(context) && findAssociations(context, device).isNotEmpty()
    }

    /** Removes all associations with [device], returns false if there were none. */
    fun removeAssociation(context: Context, device: BluetoothDevice): Boolean {
        if (!canAssociate(context))
            return false
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        val associations = findAssociations(context, device)
        for (association in associations) {
            companionManager.disassociate(association.id)
        }
        return associations.isNotEmpty()
    }

    @RequiresApi(33)
    private fun findAssociations(context: Context, device: BluetoothDevice): List<AssociationInfo> {
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        // Self managed associations have no MAC address, so they never match
        return companionManager.myAssociations.filter {
            it.deviceMacAddress?.toString().equals(device.address, ignoreCase = true)
        }
    }

    /**
     * Since API 34 setCodecConfigPreference and since API 36 also getCodecStatus require a
     * companion device association (or BLUETOOTH_PRIVILEGED). The Bluetooth stack is an updatable
     * module, so callers must still handle a SecurityException on older versions.
     */
    fun isAssociationRequired(context: Context, device: BluetoothDevice): Boolean {
        return Build.VERSION.SDK_INT >= 34 && !hasAssociation(context, device)
    }
}
