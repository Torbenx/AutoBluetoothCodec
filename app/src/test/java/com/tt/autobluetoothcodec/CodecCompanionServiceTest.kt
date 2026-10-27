package com.tt.autobluetoothcodec

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.ADDRESS
import com.tt.autobluetoothcodec.TestSupport.OTHER_ADDRESS
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.events
import com.tt.autobluetoothcodec.TestSupport.grantPermission
import com.tt.autobluetoothcodec.TestSupport.resetApp
import com.tt.autobluetoothcodec.TestSupport.resolution
import com.tt.autobluetoothcodec.TestSupport.runFor
import com.tt.autobluetoothcodec.TestSupport.runPending
import com.tt.autobluetoothcodec.TestSupport.sbc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** K5 of the test concept: the service the system binds while an observed device is present. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CodecCompanionServiceTest {
    private val a2dp = FakeA2dp()
    private lateinit var controller: ServiceController<CodecCompanionService>
    private val service get() = controller.get()

    @Before
    fun setUp() {
        grantPermission()
        resetApp(a2dp)
        a2dp.connected.add(ADDRESS)
        a2dp.connected.add(OTHER_ADDRESS)
        controller = Robolectric.buildService(CodecCompanionService::class.java).create()
    }

    private val connectionIntent get() = Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)

    private fun receiverCount() = shadowOf(context).registeredReceivers.count {
        it.intentFilter.hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
    }

    private fun a2dpConnected(address: String) {
        context.sendBroadcast(Intent(connectionIntent)
            .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device(address)))
        runPending()
    }

    /** The operations the service started, by trigger. */
    private fun operations(type: EventType) = events.filter { it.type == type }

    @Test
    fun noPreferredCodecResolvesWithoutReceiver() {
        val before = receiverCount()
        service.onDeviceAppeared(ADDRESS)
        val uid = operations(EventType.DEVICE_APPEARED).single().opUID!!
        assertEquals(EventType.NO_PREFERRED_CODEC, resolution(uid)!!.type)
        assertEquals(before, receiverCount())
        assertEquals(0, a2dp.connectCount)
    }

    @Test
    fun appearingAppliesAndRegistersTheReceiverOnce() {
        PreferredCodecStore.set(context, device(), sbc())
        val before = receiverCount()
        service.onDeviceAppeared(ADDRESS)
        runFor(600)
        assertEquals(before + 1, receiverCount())
        service.onDeviceAppeared(ADDRESS)
        runFor(600)
        assertEquals(before + 1, receiverCount())
        val appeared = operations(EventType.DEVICE_APPEARED)
        assertEquals(2, appeared.size)
        for (trigger in appeared)
            assertEquals(EventType.CODEC_VERIFIED, resolution(trigger.opUID!!)!!.type)
    }

    @Test
    fun a2dpConnectingAppliesOnlyForPresentDevices() {
        PreferredCodecStore.set(context, device(ADDRESS), sbc())
        PreferredCodecStore.set(context, device(OTHER_ADDRESS), sbc())
        service.onDeviceAppeared(ADDRESS)
        runFor(600)
        a2dpConnected(ADDRESS)
        a2dpConnected(OTHER_ADDRESS)
        runFor(600)
        val connected = operations(EventType.A2DP_CONNECTED).filter { it.message == "companion service" }
        assertEquals(listOf(ADDRESS), connected.map { it.device!!.address })
        assertEquals(EventType.CODEC_VERIFIED, resolution(connected.single().opUID!!)!!.type)
    }

    @Test
    fun disappearingCancelsThePendingRequest() {
        PreferredCodecStore.set(context, device(), sbc())
        a2dp.onSet = { _, _ -> }
        service.onDeviceAppeared(ADDRESS)
        runPending()
        service.onDeviceDisappeared(ADDRESS.lowercase())
        val uid = operations(EventType.DEVICE_APPEARED).single().opUID!!
        assertEquals(EventType.CANCELLED, resolution(uid)!!.type)
        assertEquals("device disappeared", resolution(uid)!!.message)
        assertEquals(ADDRESS, operations(EventType.DEVICE_DISAPPEARED).single().device!!.address)
        // No longer present, so A2DP connecting doesn't apply
        a2dpConnected(ADDRESS)
        assertTrue(operations(EventType.A2DP_CONNECTED).none { it.message == "companion service" })
    }

    @Test
    fun destroyingUnregistersTheReceiver() {
        PreferredCodecStore.set(context, device(), sbc())
        val before = receiverCount()
        service.onDeviceAppeared(ADDRESS)
        runFor(600)
        controller.destroy()
        assertEquals(before, receiverCount())
    }

    @Test
    fun aRestartedServiceKnowsNoPresentDevices() {
        PreferredCodecStore.set(context, device(), sbc())
        service.onDeviceAppeared(ADDRESS)
        runFor(600)
        // Like the system restarting the process: a new instance, without onDeviceAppeared
        controller.destroy()
        controller = Robolectric.buildService(CodecCompanionService::class.java).create()
        a2dpConnected(ADDRESS)
        runFor(600)
        assertTrue(operations(EventType.A2DP_CONNECTED).none { it.message == "companion service" })
    }
}
