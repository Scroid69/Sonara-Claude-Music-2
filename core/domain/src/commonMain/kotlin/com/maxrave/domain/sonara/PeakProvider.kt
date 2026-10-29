package com.maxrave.domain.sonara

/**
 * Source of "where does this song get good" answers, used by Drop Mode.
 *
 * The playback engine only talks to this interface, so it stays free of networking and database code.
 * Implementations must be cheap to call: [cachedPeak] never blocks, [peakFor] answers from cache when
 * it can and otherwise does one bounded lookup.
 */
interface PeakProvider {
    /** In-memory answer only. Null means "not known (yet)". Safe to call from the audio-control path. */
    fun cachedPeak(videoId: String): Peak?

    /** Cache first, then one bounded network lookup. Returns null when no usable peak exists. */
    suspend fun peakFor(videoId: String): Peak?

    /** Fire-and-forget warm-up for the next few queue items so the Next press can answer instantly. */
    fun prefetch(videoIds: List<String>)

    /** Lets the user pin a start point for one song (always beats every automatic source). */
    suspend fun setManualPeak(
        videoId: String,
        peakStartMs: Long,
    )
}
