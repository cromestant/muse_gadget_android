package com.cromestant.musegadget.link

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test
    fun delayDoublesFromTwoSeconds() {
        assertEquals(2.0, backoffDelay(0, 0.0), 1e-9)
        assertEquals(4.0, backoffDelay(1, 0.0), 1e-9)
        assertEquals(8.0, backoffDelay(2, 0.0), 1e-9)
    }

    @Test
    fun delayCapsAtSixtySeconds() {
        assertEquals(60.0, backoffDelay(16, 0.0), 1e-9)
        assertEquals(60.0, backoffDelay(100, 0.0), 1e-9)
    }

    @Test
    fun floorRespected() {
        assertEquals(15.0, backoffDelay(0, 15.0), 1e-9)
        assertEquals(60.0, backoffDelay(16, 15.0), 1e-9)
    }
}
