package com.audiology2.stream.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleMathTest {

    @Test
    fun positionToValue_clampsAndMaps() {
        assertEquals(0.0, ConsoleMath.positionToValue(0f, 0.0, 100.0), 1e-9)
        assertEquals(100.0, ConsoleMath.positionToValue(1f, 0.0, 100.0), 1e-9)
        assertEquals(50.0, ConsoleMath.positionToValue(0.5f, 0.0, 100.0), 1e-9)
        // out of range clamps
        assertEquals(100.0, ConsoleMath.positionToValue(2f, 0.0, 100.0), 1e-9)
        assertEquals(-12.0, ConsoleMath.positionToValue(-1f, -12.0, 12.0), 1e-9)
    }

    @Test
    fun positionToValue_snapsToStep() {
        // -12..12 in 1-semitone steps
        assertEquals(0.0, ConsoleMath.positionToValue(0.5f, -12.0, 12.0, 1.0), 1e-9)   // midpoint -> 0
        assertEquals(3.0, ConsoleMath.positionToValue(0.625f, -12.0, 12.0, 1.0), 1e-9) // +15/4 -> +3
        assertEquals(-3.0, ConsoleMath.positionToValue(0.375f, -12.0, 12.0, 1.0), 1e-9)
        assertEquals(1.0, ConsoleMath.positionToValue(0.523f, -12.0, 12.0, 1.0), 1e-9) // rounds to +13? no: +1
    }

    @Test
    fun valueToPosition_inverse() {
        assertEquals(0.5f, ConsoleMath.valueToPosition(5.0, 0.0, 10.0), 1e-6f)
        assertEquals(0f, ConsoleMath.valueToPosition(0.0, 0.0, 10.0), 1e-6f)
        assertEquals(1f, ConsoleMath.valueToPosition(10.0, 0.0, 10.0), 1e-6f)
    }

    @Test
    fun dbToMeterFraction_endpoints() {
        assertEquals(0f, ConsoleMath.dbToMeterFraction(-60.0), 1e-6f)
        assertEquals(0f, ConsoleMath.dbToMeterFraction(-80.0), 1e-6f)
        assertEquals(1f, ConsoleMath.dbToMeterFraction(0.0), 1e-6f)
        assertEquals(1f, ConsoleMath.dbToMeterFraction(3.0), 1e-6f)
        // -30 dB is halfway between -60 and 0
        assertEquals(0.5f, ConsoleMath.dbToMeterFraction(-30.0), 1e-4f)
    }

    @Test
    fun dbAndLinear_roundTrip() {
        assertEquals(0.0, ConsoleMath.linearToDb(1.0), 1e-6)
        assertEquals(-6.0, ConsoleMath.linearToDb(ConsoleMath.dbToLinear(-6.0)), 1e-6)
        assertEquals(0.5, ConsoleMath.dbToLinear(-6.0206), 1e-3)
    }

    @Test
    fun isClipping_detectsFullScale() {
        assertTrue(ConsoleMath.isClipping(1.0))
        assertTrue(ConsoleMath.isClipping(-0.999))
        assertTrue(!ConsoleMath.isClipping(0.5))
    }
}
