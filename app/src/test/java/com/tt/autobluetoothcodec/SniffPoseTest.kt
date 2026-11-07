package com.tt.autobluetoothcodec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The sniffing dog's motion, which is computed from the time alone. Robolectric provides the art's paths. */
@RunWith(AndroidJUnit4::class)
class SniffPoseTest {
    // When Codec stops at the left end and at the right end
    private val stops = listOf(3.5f, 9.1f)

    @Test
    fun theLoopHasNoSeam() {
        val start = sniffPose(0f)
        val end = sniffPose(11.9999f)
        assertEquals(start.x, end.x, 0.01f)
        assertEquals(start.facing, end.facing, 0.01f)
        assertEquals(start.neck, end.neck, 0.01f)
        assertEquals(start.head, end.head, 0.01f)
        assertEquals(start.tail, end.tail, 0.05f)
        start.scent.zip(end.scent).forEach { (first, last) -> assertEquals(first, last, 0.01f) }
        assertEquals(sniffMoment(0f).sinceStop, sniffMoment(11.9999f).sinceStop, 0.001f)
    }

    @Test
    fun theLegsAreARoundTripFillingTheLoop() {
        // Otherwise the loop's end cuts off the last turn, or Codec stands around turned until it ends
        assertEquals(LOOP_SECONDS, SNIFF_LEGS.sumOf { it.seconds.toDouble() }.toFloat(), 0.001f)
        SNIFF_LEGS.forEachIndexed { index, leg ->
            val next = SNIFF_LEGS[(index + 1) % SNIFF_LEGS.size]
            // Codec doesn't jump, and walks back the way the turn at the stop left it facing
            assertEquals("Leg $index ends where the next starts", leg.to, next.from, 0f)
            assertEquals("Leg $index turns to walk the next", -leg.facing, next.facing, 0f)
            assertTrue("Leg $index has a walk", leg.walkSeconds > 0)
            assertTrue("Leg $index has a sniff", leg.sniffSeconds >= 0)
        }
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
    fun theStopClockStartsWhenTheDogStops() {
        for (stop in stops) {
            assertEquals(0f, sniffMoment(stop).sinceStop, 0.001f)
            assertEquals(0f, sniffMoment(stop).walking, 0.001f)
            assertEquals(sniffMoment(stop).x, sniffMoment(stop + 1).x, 0.01f)
            assertTrue(sniffMoment(stop - 0.1f).sinceStop < 0)
            assertTrue(sniffMoment(stop - 0.1f).walking > 0)
        }
    }

    @Test
    fun itOnlySniffsWhileStanding() {
        assertEquals(listOf(0f, 0f, 0f), sniffPose(2f).scent)
        assertTrue(generateSequence(4f) { it + 0.1f }.take(10).any { time -> sniffPose(time).scent.any { it > 0.5f } })
    }

    @Test
    fun thePuffsStartAtTheStop() {
        for (stop in stops) {
            assertEquals(listOf(0f, 0f, 0f), sniffPose(stop - 0.05f).scent)
            assertEquals(0f, sniffPose(stop).scent[0], 0.001f)
            assertTrue(sniffPose(stop + 0.1f).scent[0] > 0.5f)
        }
    }

    @Test
    fun itSniffsAlikeAtBothEnds() {
        val (left, right) = stops
        // Until the left end's turn, which comes first
        for (since in generateSequence(0f) { it + 0.05f }.takeWhile { it < 1.5f }) {
            val atLeft = sniffPose(left + since)
            val atRight = sniffPose(right + since)
            assertEquals(atLeft.head, atRight.head, 0.001f)
            atLeft.scent.zip(atRight.scent).forEach { (first, second) -> assertEquals(first, second, 0.001f) }
        }
    }

    @Test
    fun theLegsOnlySwingWhileWalking() {
        assertEquals(0f, sniffPose(4f).diagonalLegs, 0f)
        assertTrue(sniffPose(2.25f - 0.375f).diagonalLegs != 0f)
    }
}
