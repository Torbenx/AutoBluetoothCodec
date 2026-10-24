package com.tt.autobluetoothcodec

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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
     * Companion device associations are only used since API 31, where they allow observing the
     * presence of a device. Before API 31 the Bluetooth stack doesn't check for an association,
     * and it isn't an updatable module there, so there is never a need to request one.
     */
    fun canAssociate(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 31
                && context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    fun hasAssociation(context: Context, device: BluetoothDevice): Boolean {
        return findAssociatedAddresses(context, device).isNotEmpty()
    }

    /** Removes all associations with [device], returns false if there were none. */
    fun removeAssociation(context: Context, device: BluetoothDevice): Boolean {
        if (!canAssociate(context))
            return false
        CompanionPresence.stopObserving(context, device)
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) {
            val associations = companionManager.myAssociations.filter { matches(it.deviceMacAddress?.toString(), device) }
            for (association in associations) {
                companionManager.disassociate(association.id)
            }
            return associations.isNotEmpty()
        }
        val addresses = findAssociatedAddresses(context, device)
        for (address in addresses) {
            @Suppress("DEPRECATION")
            companionManager.disassociate(address)
        }
        return addresses.isNotEmpty()
    }

    /**
     * Returns the associated addresses as the companion device manager stores them. Before API 33
     * it matches them by exact string comparison, so they have to be passed back unchanged.
     */
    fun findAssociatedAddresses(context: Context, device: BluetoothDevice): List<String> {
        return getAssociatedAddresses(context).filter { matches(it, device) }
    }

    fun getAssociatedAddresses(context: Context): List<String> {
        if (!canAssociate(context))
            return emptyList()
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) {
            // Self managed associations have no MAC address
            return companionManager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        }
        @Suppress("DEPRECATION")
        return companionManager.associations
    }

    private fun matches(address: String?, device: BluetoothDevice): Boolean {
        return address.equals(device.address, ignoreCase = true)
    }

    /**
     * Since API 34 setCodecConfigPreference and since API 36 also getCodecStatus require a
     * companion device association (or BLUETOOTH_PRIVILEGED). The Bluetooth stack is an updatable
     * module, so callers must still handle a SecurityException on older versions.
     */
    fun isAssociationRequired(context: Context, device: BluetoothDevice): Boolean {
        return Build.VERSION.SDK_INT >= 34 && !hasAssociation(context, device)
    }

    /**
     * Since API 31 the preferred codec is applied by [CodecCompanionService], which the system only
     * binds for associated devices. Without the companion device feature [A2dpConnectReceiver]
     * applies it instead.
     */
    fun isAssociationRequiredForAutoApply(context: Context, device: BluetoothDevice): Boolean {
        return canAssociate(context) && !hasAssociation(context, device)
    }
}
