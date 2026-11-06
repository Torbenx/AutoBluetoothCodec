package com.tt.autobluetoothcodec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The sniffing dog's motion, which is computed from the time alone. Robolectric provides the art's paths. */
@RunWith(AndroidJUnit4::class)
class SniffPoseTest {
    @Test
    fun theLoopHasNoSeam() {
        val start = sniffPose(0f)
        val end = sniffPose(11.9999f)
        assertEquals(start.x, end.x, 0.01f)
        assertEquals(start.facing, end.facing, 0.01f)
        assertEquals(start.neck, end.neck, 0.01f)
        assertEquals(start.head, end.head, 0.01f)
        assertEquals(start.tail, end.tail, 0.05f)
    }

    @Test
    fun theDogFacesTheWayItWalks() {
        assertTrue(sniffPose(1f).x > sniffPose(2f).x)
        assertEquals(1f, sniffPose(1f).facing, 0f)
        assertTrue(sniffPose(7f).x < sniffPose(8f).x)
        assertEquals(-1f, sniffPose(7f).facing, 0f)
    }

    @Test
    fun theNoseStaysAboveTheFloorAtBothEnds() {
        // The nose's tip at rest, and the flip's middle, in the art's units
        fun noseAt(seconds: Float) = sniffPose(seconds).let { it.x + 90 + (14.5f - 90) * it.facing }
        assertEquals(10f, noseAt(4f), 1f)
        assertEquals(231f, noseAt(10f), 1f)
    }

    @Test
    fun itOnlySniffsWhileStanding() {
        assertEquals(listOf(0f, 0f, 0f), sniffPose(2f).scent)
        assertTrue(generateSequence(4f) { it + 0.1f }.take(10).any { time -> sniffPose(time).scent.any { it > 0.5f } })
    }

    @Test
    fun theLegsOnlySwingWhileWalking() {
        assertEquals(0f, sniffPose(4f).diagonalLegs, 0f)
        assertTrue(sniffPose(2.25f - 0.375f).diagonalLegs != 0f)
    }
}
