package com.tt.autobluetoothcodec

import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowCompanionDeviceManager

/**
 * Robolectric's companion device manager, which also tracks what is observed. Its own shadow
 * records starting to observe but not stopping. Addresses are kept as passed, ids as "id 5".
 */
@Implements(CompanionDeviceManager::class)
class ObservingCompanionDeviceManager : ShadowCompanionDeviceManager() {
    val observed = LinkedHashSet<String>()

    @Implementation(minSdk = 31)
    override fun startObservingDevicePresence(deviceAddress: String) {
        super.startObservingDevicePresence(deviceAddress)
        observed.add(deviceAddress)
    }

    @Implementation(minSdk = 31)
    protected fun stopObservingDevicePresence(deviceAddress: String) {
        observed.remove(deviceAddress)
    }

    @Implementation(minSdk = 36)
    override fun startObservingDevicePresence(request: ObservingDevicePresenceRequest) {
        super.startObservingDevicePresence(request)
        observed.add("id ${request.associationId}")
    }

    @Implementation(minSdk = 36)
    protected fun stopObservingDevicePresence(request: ObservingDevicePresenceRequest) {
        observed.remove("id ${request.associationId}")
    }
}
