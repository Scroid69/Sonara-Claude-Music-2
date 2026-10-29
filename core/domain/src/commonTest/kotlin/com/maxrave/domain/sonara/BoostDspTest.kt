package com.maxrave.domain.sonara

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoostDspTest {
    private val sr = 44_100

    private fun rms(x: DoubleArray, from: Int): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i] * x[i]
        return sqrt(s / (x.size - from))
    }

    private fun shelfGainDb(freq: Double, gainDb: Double): Double {
        val c = BiquadCoeffs.lowShelf(sr, BoostSettings.BASS_CORNER_HZ, gainDb)
        val st = BiquadState()
        val n = sr
        val out = DoubleArray(n) { st.process(c, 0.1 * sin(2 * PI * freq * it / sr)) }
        val inp = DoubleArray(n) { 0.1 * sin(2 * PI * freq * it / sr) }
        return 20 * log10(rms(out, n / 2) / rms(inp, n / 2))
    }

    @Test
    fun lowShelfBoostsBassAndLeavesTrebleAlone() {
        assertEquals(9.0, shelfGainDb(30.0, 9.0), 0.5)
        assertEquals(0.0, shelfGainDb(4_000.0, 9.0), 0.3)
        assertEquals(0.0, shelfGainDb(10_000.0, 12.0), 0.3)
    }

    @Test
    fun zeroGainShelfIsTransparent() {
        assertEquals(0.0, shelfGainDb(60.0, 0.0), 0.01)
        assertEquals(0.0, shelfGainDb(1_000.0, 0.0), 0.01)
    }

    @Test
    fun limiterIsTransparentBelowThresholdAndNeverClips() {
        for (i in -70..70) {
            val x = i / 100.0
            assertEquals(x, SoftLimiter.process(x), 1e-12)
        }
        for (i in 0..2000) {
            val x = i / 100.0 // up to 20x full scale
            val y = SoftLimiter.process(x)
            assertTrue(y <= 1.0, "x=$x y=$y")
            assertEquals(-y, SoftLimiter.process(-x), 1e-12)
        }
    }

    @Test
    fun limiterIsMonotonicAndContinuous() {
        var prev = 0.0
        for (i in 0..5000) {
            val y = SoftLimiter.process(i / 1000.0)
            assertTrue(y >= prev - 1e-12)
            assertTrue(abs(y - prev) < 0.01)
            prev = y
        }
    }

    @Test
    fun settingsOffDetection() {
        assertTrue(BoostSettings().isOff)
        assertTrue(!BoostSettings(bassDb = 3f).isOff)
        assertTrue(!BoostSettings(volumeDb = 6f).isOff)
        assertEquals(1.9953, BoostMath.dbToGain(6.0), 0.001)
    }
}
