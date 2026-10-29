package com.maxrave.domain.sonara

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gain curves for a two-track blend.
 *
 * Every curve except [LINEAR] maps progress to an angle and returns cos/sin of that angle, so
 * `out² + in² == 1` at every instant: total acoustic power stays constant and there is no dip in the
 * middle of the blend. [LINEAR] is kept because some people prefer it, it dips about 3 dB at the
 * midpoint.
 */
enum class MixCurve {
    /**
     * Recommended. Constant power, but the audible hand-over is limited to about [MixGains.SMART_FADE_MS]
     * in the middle of the blend, however long the blend is. A 30 s mix therefore keeps the outgoing
     * song at full level for its first ~9 s and has the incoming song at full level for the last ~9 s;
     * the two only really overlap for ~12 s. Long overlaps of two full songs are what sounds like
     * interference (vocals on vocals, two kicks), so the length setting decides *when* the blend begins,
     * not how long two songs fight.
     */
    SMART,

    /** Plain constant-power fade across the whole blend. Long blends overlap for the full length. */
    EQUAL_POWER,

    /** Constant power, but eased: slow start, quick middle, slow finish (smootherstep on the angle). */
    S_CURVE,

    /** Straight volume ramp. */
    LINEAR,

    /** Constant power with a hold: the outgoing track stays near full for longer, then drops fast. */
    DRAMATIC,
    ;

    companion object {
        fun fromName(name: String?): MixCurve = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SMART
    }
}

object MixGains {
    /** Length of the audible hand-over in [MixCurve.SMART]. */
    const val SMART_FADE_MS = 12_000L

    /**
     * Where the blend "really is", 0..1, after the curve's shaping. Gains use it and so does the
     * bass-swap filter, which keeps the filter movement locked to what the listener actually hears.
     */
    fun shaped(
        curve: MixCurve,
        progress: Float,
        durationMs: Long = 0L,
    ): Float {
        val p = progress.coerceIn(0f, 1f)
        return when (curve) {
            MixCurve.SMART -> {
                val window = if (durationMs <= SMART_FADE_MS) 1f else SMART_FADE_MS.toFloat() / durationMs.toFloat()
                smootherstep(((p - 0.5f) / window + 0.5f).coerceIn(0f, 1f))
            }

            MixCurve.EQUAL_POWER, MixCurve.LINEAR -> p

            MixCurve.S_CURVE -> smootherstep(p)

            MixCurve.DRAMATIC -> if (p < 0.5f) 2f * p * p else 1f - 2f * (1f - p) * (1f - p)
        }
    }

    private fun smootherstep(p: Float): Float = p * p * p * (p * (p * 6f - 15f) + 10f)

    /** Gain for the track that is leaving, `progress` in 0..1. */
    fun outgoing(
        curve: MixCurve,
        progress: Float,
        durationMs: Long = 0L,
    ): Float =
        if (curve == MixCurve.LINEAR) {
            1f - progress.coerceIn(0f, 1f)
        } else {
            cos((shaped(curve, progress, durationMs) * PI / 2.0).toFloat())
        }

    /** Gain for the track that is arriving, `progress` in 0..1. */
    fun incoming(
        curve: MixCurve,
        progress: Float,
        durationMs: Long = 0L,
    ): Float =
        if (curve == MixCurve.LINEAR) {
            progress.coerceIn(0f, 1f)
        } else {
            sin((shaped(curve, progress, durationMs) * PI / 2.0).toFloat())
        }

    /**
     * Progress of a blend from wall-clock time. Driving the fade from the clock, not from "step N of
     * 50 with a delay() between", is what removes drift and the audible stair-stepping: the gain is
     * always exactly where the clock says it should be, however late the coroutine woke up.
     */
    fun progressAt(
        elapsedMs: Long,
        durationMs: Long,
    ): Float = if (durationMs <= 0L) 1f else (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /**
     * Interval between gain updates. 16 ms is one display frame; short blends get the finest steps
     * and long ones never go coarser than 25 ms, which at 0.1 % gain change per step is inaudible.
     */
    fun updateIntervalMs(durationMs: Long): Long = if (durationMs <= 4_000L) 10L else 16L
}
