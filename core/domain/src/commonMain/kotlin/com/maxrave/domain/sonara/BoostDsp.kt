package com.maxrave.domain.sonara

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Bass boost and volume boost settings, in dB. Both 0 means "off" and the stage is bypassed. */
data class BoostSettings(
    val bassDb: Float = 0f,
    val volumeDb: Float = 0f,
) {
    val isOff: Boolean get() = bassDb <= 0f && volumeDb <= 0f

    companion object {
        const val MAX_BASS_DB = 12f
        const val MAX_VOLUME_DB = 12f
        const val BASS_CORNER_HZ = 110.0
    }
}

/** Direct-form-I biquad coefficients, already divided by a0. */
class BiquadCoeffs(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {
        /** RBJ cookbook low shelf. Gain [gainDb] below [cornerHz], flat above, slope S = 1. */
        fun lowShelf(
            sampleRate: Int,
            cornerHz: Double,
            gainDb: Double,
        ): BiquadCoeffs {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * cornerHz / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt(2.0) // S = 1
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            val b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
            val b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
            val b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
            val a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
            val a1 = -2 * ((a - 1) + (a + 1) * cosW)
            val a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
            return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/** One channel's biquad state. */
class BiquadState {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(
        c: BiquadCoeffs,
        x: Double,
    ): Double {
        val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}

/**
 * Soft limiter. Transparent up to [THRESHOLD], then a tanh knee that approaches (never reaches) full
 * scale. A raised volume therefore gets denser instead of clipping into harsh distortion.
 */
object SoftLimiter {
    const val THRESHOLD = 0.7

    fun process(x: Double): Double {
        val a = abs(x)
        if (a <= THRESHOLD) return x
        val knee = 1.0 - THRESHOLD
        val limited = THRESHOLD + knee * tanh((a - THRESHOLD) / knee)
        return if (x < 0) -limited else limited
    }
}

object BoostMath {
    fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)

    /**
     * Makeup for the bass shelf: boosting lows by X dB raises peak level, so part of that is taken
     * back to leave the limiter headroom instead of making it work on every kick drum.
     */
    fun bassHeadroomDb(bassDb: Float): Double = -(bassDb * 0.35)
}
