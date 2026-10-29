package com.maxrave.domain.sonara

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixLogicTest {
    private fun ctx(
        mixOnNext: Boolean = true,
        pos: Long = 60_000,
        dur: Long = 200_000,
        nextDur: Long = 0,
        configured: Long = 15_000,
        since: Long? = null,
        hard: Boolean = false,
        playing: Boolean = true,
        autoMix: Boolean = true,
    ) = NextPressContext(
        mixOnNext = mixOnNext, autoMixEnabled = autoMix, isPlaying = playing, hasNext = true,
        isAlreadyMixing = false, currentIsVideoOrLive = false, nextIsVideoOrLive = false,
        currentPositionMs = pos, currentDurationMs = dur, nextDurationMs = nextDur,
        configuredDurationMs = configured, msSinceLastNextPress = since, forceHardSkip = hard,
    )

    @Test
    fun curvesKeepConstantPower() {
        for (curve in listOf(MixCurve.SMART, MixCurve.EQUAL_POWER, MixCurve.S_CURVE, MixCurve.DRAMATIC)) {
            for (i in 0..100) {
                val p = i / 100f
                val o = MixGains.outgoing(curve, p, 30_000)
                val n = MixGains.incoming(curve, p, 30_000)
                assertTrue(abs(o * o + n * n - 1f) < 1e-4f, "$curve p=$p power=${o * o + n * n}")
            }
        }
    }

    @Test
    fun curvesHitTheEndpointsExactly() {
        for (curve in MixCurve.entries) {
            for (d in listOf(5_000L, 30_000L)) {
                assertEquals(1f, MixGains.outgoing(curve, 0f, d), 1e-6f)
                assertEquals(0f, MixGains.incoming(curve, 0f, d), 1e-6f)
                assertEquals(0f, MixGains.outgoing(curve, 1f, d), 1e-5f)
                assertEquals(1f, MixGains.incoming(curve, 1f, d), 1e-6f)
            }
        }
    }

    @Test
    fun curvesAreMonotonic() {
        for (curve in MixCurve.entries) {
            var lastIn = -1f
            var lastOut = 2f
            for (i in 0..1000) {
                val p = i / 1000f
                val n = MixGains.incoming(curve, p, 30_000)
                val o = MixGains.outgoing(curve, p, 30_000)
                assertTrue(n >= lastIn - 1e-6f && o <= lastOut + 1e-6f, "$curve p=$p")
                lastIn = n
                lastOut = o
            }
        }
    }

    @Test
    fun noGainStepIsAudibleAtSixteenMs() {
        // A 15 s blend updated every 16 ms: the biggest single gain step must be well under 1 %.
        val steps = 15_000L / MixGains.updateIntervalMs(15_000L)
        var worst = 0f
        var prev = 0f
        for (i in 1..steps) {
            val g = MixGains.incoming(MixCurve.EQUAL_POWER, i.toFloat() / steps)
            worst = maxOf(worst, abs(g - prev))
            prev = g
        }
        assertTrue(worst < 0.01f, "worst step $worst")
    }

    /** Seconds during which BOTH songs are above [level] of full volume. */
    private fun overlapSeconds(durationMs: Long, level: Float, curve: MixCurve = MixCurve.SMART): Float {
        var both = 0
        val n = 3000
        for (i in 0..n) {
            val p = i / n.toFloat()
            if (MixGains.outgoing(curve, p, durationMs) > level && MixGains.incoming(curve, p, durationMs) > level) both++
        }
        return both.toFloat() / n * durationMs / 1000f
    }

    @Test
    fun smartKeepsTheLoudOverlapShortWhateverTheMixLength() {
        // Both songs above 30 % of full volume (-10 dB): the part that sounds like interference.
        assertTrue(overlapSeconds(30_000, 0.3f) < 7f, "30s: ${overlapSeconds(30_000, 0.3f)}")
        assertTrue(overlapSeconds(15_000, 0.3f) < 7f, "15s: ${overlapSeconds(15_000, 0.3f)}")
        // The old equal-power curve held that overlap for most of a 30 s blend.
        assertTrue(overlapSeconds(30_000, 0.3f, MixCurve.EQUAL_POWER) > 15f)
    }

    @Test
    fun smartLeavesTheLongMixMarginsClean() {
        // First fifth of a 30 s mix: outgoing untouched, incoming inaudible. Same at the end, mirrored.
        assertTrue(MixGains.outgoing(MixCurve.SMART, 0.2f, 30_000) > 0.99f)
        assertTrue(MixGains.incoming(MixCurve.SMART, 0.2f, 30_000) < 0.02f)
        assertTrue(MixGains.incoming(MixCurve.SMART, 0.8f, 30_000) > 0.99f)
        assertTrue(MixGains.outgoing(MixCurve.SMART, 0.8f, 30_000) < 0.02f)
    }

    @Test
    fun smartOnAShortMixIsJustAnEasedCurve() {
        for (i in 0..20) {
            val p = i / 20f
            assertEquals(MixGains.shaped(MixCurve.S_CURVE, p), MixGains.shaped(MixCurve.SMART, p, 8_000), 1e-6f)
        }
    }

    @Test
    fun smartIsTheDefault() {
        assertEquals(MixCurve.SMART, MixCurve.fromName(null))
        assertEquals(MixCurve.SMART, MixCurve.fromName("garbage"))
        assertEquals(MixCurve.S_CURVE, MixCurve.fromName("s_curve"))
    }

    @Test
    fun progressFollowsTheClockNotTheStepCount() {
        assertEquals(0.5f, MixGains.progressAt(7_500, 15_000), 1e-6f)
        assertEquals(1f, MixGains.progressAt(99_999, 15_000))
        assertEquals(1f, MixGains.progressAt(0, 0))
    }

    @Test
    fun nextMixesForTheConfiguredDuration() {
        val d = MixPlanner.decideNext(ctx(configured = 15_000))
        assertEquals(NextDecision.Mix(15_000), d)
        assertEquals(NextDecision.Mix(30_000), MixPlanner.decideNext(ctx(configured = 30_000)))
    }

    @Test
    fun nextIsShortenedWhenTheSongIsAboutToEnd() {
        // 8 s left, 15 s configured -> 8 s.
        assertEquals(NextDecision.Mix(8_000), MixPlanner.decideNext(ctx(pos = 192_000, configured = 15_000)))
        // 0.4 s left -> the 1.5 s floor, never zero.
        assertEquals(NextDecision.Mix(1_500), MixPlanner.decideNext(ctx(pos = 199_600, configured = 15_000)))
    }

    @Test
    fun rapidTappingGetsShorterMixes() {
        assertEquals(NextDecision.Mix(9_000), MixPlanner.decideNext(ctx(configured = 15_000, since = 800)))
        assertEquals(NextDecision.Mix(1_200), MixPlanner.decideNext(ctx(configured = 1_000, since = 800)))
        assertEquals(NextDecision.Mix(15_000), MixPlanner.decideNext(ctx(configured = 15_000, since = 2_500)))
    }

    @Test
    fun incomingTrackNeverGetsMoreThanHalfItsLength() {
        assertEquals(NextDecision.Mix(30_000), MixPlanner.decideNext(ctx(configured = 45_000, nextDur = 60_000)))
    }

    @Test
    fun hardSkipCases() {
        assertTrue(MixPlanner.decideNext(ctx(mixOnNext = false)) is NextDecision.HardSkip)
        assertTrue(MixPlanner.decideNext(ctx(hard = true)) is NextDecision.HardSkip)
        assertTrue(MixPlanner.decideNext(ctx(playing = false)) is NextDecision.HardSkip)
        assertTrue(MixPlanner.decideNext(ctx(autoMix = false)) is NextDecision.HardSkip)
        assertTrue(MixPlanner.decideNext(ctx(dur = 45_000, pos = 5_000)) is NextDecision.HardSkip)
        assertTrue(MixPlanner.decideNext(ctx(nextDur = 30_000)) is NextDecision.HardSkip)
    }

    @Test
    fun durationPickerContains15And30() {
        assertTrue(15 in MixPlanner.DURATION_CHOICES_SEC)
        assertTrue(30 in MixPlanner.DURATION_CHOICES_SEC)
    }

    // ---- peak / drop ----

    private fun heat(
        peakAtBucket: Int,
        total: Int = 100,
        durMs: Long = 200_000,
        width: Int = 4,
        base: Float = 0.25f,
        introBoost: Float = 0f,
    ): List<HeatBucket> {
        val size = durMs / total
        return List(total) { i ->
            val s =
                when {
                    i in peakAtBucket until peakAtBucket + width -> 1f
                    i < 6 -> base + introBoost
                    else -> base
                }
            HeatBucket(i * size, size, s)
        }
    }

    @Test
    fun heatmapFindsTheStartOfTheHotRegion() {
        // buckets 40..43 hot (80s..88s of a 200s song)
        val p = assertNotNull(PeakFusion.fromHeatmap(heat(40), decorationTimeMs = 86_000, audioDurationMs = 200_000))
        assertEquals(80_000, p.peakStartMs)
        assertEquals(86_000, p.peakMs)
        assertTrue(p.confidence > 0.8f, "confidence ${p.confidence}")
        assertEquals(PeakSource.HEATMAP, p.source)
    }

    @Test
    fun heatmapIgnoresInflatedIntro() {
        val h = heat(60, introBoost = 0.75f) // first buckets score 1.0, real peak at 120 s
        val p = assertNotNull(PeakFusion.fromHeatmap(h, decorationTimeMs = null, audioDurationMs = 200_000))
        assertTrue(p.peakStartMs >= 110_000, "peak at ${p.peakStartMs}")
    }

    @Test
    fun flatHeatmapIsNotTrusted() {
        val flat = List(100) { HeatBucket(it * 2_000L, 2_000, 0.95f) }
        val p = PeakFusion.fromHeatmap(flat, null, 200_000)
        assertTrue(p == null || p.confidence < PeakFusion.MIN_CONFIDENCE)
    }

    @Test
    fun mismatchedDurationHalvesConfidence() {
        val a = assertNotNull(PeakFusion.fromHeatmap(heat(40), 86_000, 200_000))
        val b = assertNotNull(PeakFusion.fromHeatmap(heat(40), 86_000, 215_000))
        assertEquals(a.confidence * 0.5f, b.confidence, 1e-5f)
    }

    @Test
    fun tooFewBucketsGivesNothing() {
        assertNull(PeakFusion.fromHeatmap(listOf(HeatBucket(0, 1000, 1f)), null, 1000))
    }

    @Test
    fun dropStartMatchesTheSpecExample() {
        // peak at 1:12, lead-in 8 s, mix 8 s -> start at 1:04
        val peak = Peak(72_000, 74_000, 0.9f, PeakSource.HEATMAP)
        assertEquals(64_000, DropStart.startMs(peak, durationMs = 210_000, leadInMs = 8_000, mixMs = 8_000))
        // mix longer than lead-in moves the start earlier so the peak lands after the fade
        assertEquals(57_000, DropStart.startMs(peak, 210_000, leadInMs = 8_000, mixMs = 15_000))
    }

    @Test
    fun dropStartIsClamped() {
        val early = Peak(3_000, 4_000, 0.9f, PeakSource.HEATMAP)
        assertEquals(0, DropStart.startMs(early, 200_000, 8_000, 8_000))
        val late = Peak(190_000, 191_000, 0.9f, PeakSource.HEATMAP)
        assertEquals(150_000, DropStart.startMs(late, 200_000, 8_000, 8_000)) // 75 % of 200 s
        assertEquals(0, DropStart.startMs(late, 25_000, 8_000, 8_000)) // shorter than 30 s: never jump
        assertEquals(10_000, DropStart.startMs(late, 40_000, 8_000, 8_000)) // keeps 30 s of song
    }

    @Test
    fun lowConfidenceNeverSeeks() {
        val weak = Peak(90_000, 91_000, 0.2f, PeakSource.ANALYSIS)
        assertEquals(0, DropStart.startMs(weak, 200_000, 8_000, 8_000))
        assertEquals(0, DropStart.startMs(null, 200_000, 8_000, 8_000))
    }

    @Test
    fun fusionPrefersManualThenTrustedHeatmap() {
        val manual = Peak(10_000, 10_000, 1f, PeakSource.MANUAL)
        val heat = Peak(80_000, 86_000, 0.9f, PeakSource.HEATMAP)
        val ana = Peak(120_000, 125_000, 0.8f, PeakSource.ANALYSIS)
        assertEquals(manual, PeakFusion.fuse(manual, heat, ana, null, 200_000))
        assertEquals(80_000, PeakFusion.fuse(null, heat, ana, null, 200_000)?.peakStartMs)
    }

    @Test
    fun fusionSnapsHeatmapToTheNearestDownbeat() {
        val heat = Peak(80_000, 86_000, 0.9f, PeakSource.HEATMAP)
        assertEquals(81_200, PeakFusion.fuse(null, heat, null, null, 200_000, listOf(70_000, 81_200, 92_000))?.peakStartMs)
        assertEquals(80_000, PeakFusion.fuse(null, heat, null, null, 200_000, listOf(70_000, 95_000))?.peakStartMs)
    }

    @Test
    fun fusionFallsBackToAnalysisThenNothing() {
        val ana = Peak(120_000, 125_000, 0.6f, PeakSource.ANALYSIS)
        assertEquals(120_000, PeakFusion.fuse(null, null, ana, null, 200_000)?.peakStartMs)
        val lyric = Peak(122_000, 122_000, 0.5f, PeakSource.LYRICS)
        assertEquals(0.75f, PeakFusion.fuse(null, null, ana, lyric, 200_000)!!.confidence, 1e-5f)
        assertNull(PeakFusion.fuse(null, null, null, null, 200_000))
        assertNull(PeakFusion.fuse(null, null, Peak(1, 1, 0.1f, PeakSource.ANALYSIS), null, 200_000))
    }
}
