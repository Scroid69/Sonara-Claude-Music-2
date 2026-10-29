package com.maxrave.domain.sonara

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One slice of YouTube's "Most replayed" graph. [score] is normalised: 1 is the hottest slice. */
data class HeatBucket(
    val startMs: Long,
    val durationMs: Long,
    val score: Float,
) {
    val endMs: Long get() = startMs + durationMs
}

enum class PeakSource { MANUAL, HEATMAP, ANALYSIS, LYRICS }

/**
 * Where the energetic part of a song begins.
 *
 * [peakStartMs] is the start of the hot region (where a drop or chorus begins), [peakMs] is its
 * crest. [confidence] is 0..1; below [PeakFusion.MIN_CONFIDENCE] nobody should seek.
 */
data class Peak(
    val peakStartMs: Long,
    val peakMs: Long,
    val confidence: Float,
    val source: PeakSource,
)

object PeakFusion {
    const val MIN_CONFIDENCE = 0.35f
    const val HEATMAP_TRUSTED_CONFIDENCE = 0.5f

    /** Intro replays and autoplay starts inflate the first buckets, so the first 6 % is distrusted. */
    private const val INTRO_FRACTION = 0.06f

    /** A region counts as part of the peak while it scores at least this share of the crest. */
    private const val REGION_THRESHOLD = 0.8f

    /** How far left of the crest the hot region may reach. */
    private const val MAX_WALK_LEFT_MS = 25_000L

    /**
     * Turns YouTube's replay graph into a [Peak].
     *
     * @param decorationTimeMs the instant behind the "Most replayed" marker, when YouTube sent one
     * @param audioDurationMs duration of the audio track we are about to play (0 when unknown)
     */
    fun fromHeatmap(
        buckets: List<HeatBucket>,
        decorationTimeMs: Long?,
        audioDurationMs: Long,
    ): Peak? {
        val sorted = buckets.filter { it.durationMs > 0 }.sortedBy { it.startMs }
        if (sorted.size < 5) return null
        val videoDurationMs = sorted.last().endMs
        val introEnd = (videoDurationMs * INTRO_FRACTION).toLong()

        val outsideIntro = sorted.filter { it.endMs > introEnd }
        // Use the intro buckets only when nothing else is hot, otherwise a boring song whose
        // listeners all restarted it would "peak" at 0:00.
        val pool = if (outsideIntro.any { it.score >= 0.6f }) outsideIntro else sorted

        val decorated =
            decorationTimeMs
                ?.takeIf { it > introEnd || pool === sorted }
                ?.let { t -> sorted.firstOrNull { t >= it.startMs && t < it.endMs } }
        val crestBucket = decorated ?: pool.maxByOrNull { it.score } ?: return null
        val crestMs = decorationTimeMs?.takeIf { decorated != null } ?: (crestBucket.startMs + crestBucket.durationMs / 2)
        val crestScore = max(crestBucket.score, 0.0001f)

        // Walk left while the region stays hot.
        var idx = sorted.indexOf(crestBucket)
        var startMs = crestBucket.startMs
        while (idx > 0) {
            val prev = sorted[idx - 1]
            if (prev.score < REGION_THRESHOLD * crestScore) break
            if (crestMs - prev.startMs > MAX_WALK_LEFT_MS) break
            startMs = prev.startMs
            idx--
        }

        val median = sorted.map { it.score }.sorted().let { it[it.size / 2] }
        val contrast = ((crestScore - median) / 0.6f).coerceIn(0f, 1f)
        var confidence = (0.3f * crestScore.coerceIn(0f, 1f) + 0.7f * contrast).coerceIn(0f, 1f)
        if (sorted.size < 10) confidence *= 0.7f
        if (audioDurationMs > 0 && abs(videoDurationMs - audioDurationMs) > 2_000L) confidence *= 0.5f

        return Peak(peakStartMs = startMs, peakMs = crestMs, confidence = confidence, source = PeakSource.HEATMAP)
    }

    /**
     * Combines whatever signals exist into one answer (spec 9.4.2). Returns null when nothing is
     * trustworthy enough to justify jumping into the middle of a song.
     *
     * @param downbeatsMs bar lines found by audio analysis, used to nudge a heatmap peak onto the beat
     */
    fun fuse(
        manual: Peak?,
        heatmap: Peak?,
        analysis: Peak?,
        lyrics: Peak?,
        durationMs: Long,
        downbeatsMs: List<Long> = emptyList(),
    ): Peak? {
        if (manual != null) return manual

        val introEnd = (durationMs * INTRO_FRACTION).toLong()
        val result: Peak? =
            when {
                heatmap != null && heatmap.confidence >= HEATMAP_TRUSTED_CONFIDENCE -> {
                    val snapped = snapToDownbeat(heatmap.peakStartMs, downbeatsMs, 3_000L)
                    heatmap.copy(peakStartMs = snapped)
                }

                analysis != null -> {
                    var best = analysis
                    if (heatmap != null &&
                        abs(heatmap.peakStartMs - analysis.peakStartMs) > 20_000L &&
                        heatmap.peakStartMs > introEnd &&
                        heatmap.confidence >= MIN_CONFIDENCE
                    ) {
                        best = heatmap
                    }
                    if (lyrics != null && abs(lyrics.peakStartMs - best.peakStartMs) <= 6_000L) {
                        best = best.copy(confidence = min(1f, best.confidence + 0.15f))
                    }
                    best
                }

                heatmap != null -> heatmap

                else -> lyrics
            }
        return result?.takeIf { it.confidence >= MIN_CONFIDENCE }
    }

    private fun snapToDownbeat(
        startMs: Long,
        downbeatsMs: List<Long>,
        windowMs: Long,
    ): Long {
        val nearest = downbeatsMs.minByOrNull { abs(it - startMs) } ?: return startMs
        return if (abs(nearest - startMs) <= windowMs) nearest else startMs
    }
}

/** Where to start the incoming song so its peak lands as the blend finishes. */
object DropStart {
    /** Never start inside the last quarter of a track (spec 9.4.5). */
    private const val MAX_START_FRACTION = 0.75f

    /** A song started at the drop still has to play for a while. */
    private const val MIN_REMAINING_MS = 30_000L

    /**
     * startMs = max(0, peakStartMs - max(leadIn, mix)), then clamped so the song keeps at least
     * [MIN_REMAINING_MS] and is not started in its last quarter. A track shorter than that
     * starts at 0.
     */
    fun startMs(
        peak: Peak?,
        durationMs: Long,
        leadInMs: Long,
        mixMs: Long,
    ): Long {
        if (peak == null || peak.confidence < PeakFusion.MIN_CONFIDENCE) return 0L
        val raw = peak.peakStartMs - max(leadInMs, mixMs)
        var start = max(0L, raw)
        if (durationMs > 0L) {
            val ceiling = min((durationMs * MAX_START_FRACTION).toLong(), durationMs - MIN_REMAINING_MS)
            start = min(start, max(0L, ceiling))
        }
        return start
    }
}
