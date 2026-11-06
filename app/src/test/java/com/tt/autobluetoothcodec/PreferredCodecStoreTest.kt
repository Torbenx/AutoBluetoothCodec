package com.tt.autobluetoothcodec

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tt.autobluetoothcodec.TestSupport.context
import com.tt.autobluetoothcodec.TestSupport.device
import com.tt.autobluetoothcodec.TestSupport.sbc
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What decides whether the Saved list shows its empty state. */
@RunWith(AndroidJUnit4::class)
class PreferredCodecStoreTest {
    @Before
    fun setUp() = PreferredCodecStore.clearForTest(context)

    @Test
    fun hasAnyFollowsTheSavedCodecs() {
        assertFalse(PreferredCodecStore.hasAny(context))
        PreferredCodecStore.set(context, device(), sbc())
        assertTrue(PreferredCodecStore.hasAny(context))
        PreferredCodecStore.remove(context, device())
        assertFalse(PreferredCodecStore.hasAny(context))
    }
}
