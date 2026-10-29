package com.maxrave.data.repository

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.sonara.HeatBucket
import com.maxrave.domain.sonara.Peak
import com.maxrave.domain.sonara.PeakFusion
import com.maxrave.domain.sonara.PeakProvider
import com.maxrave.domain.sonara.PeakSource
import com.maxrave.kotlinytmusicscraper.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Finds where songs get good, for Drop Mode.
 *
 * Source: YouTube's "Most replayed" graph, fetched lazily for songs that are about to play (never the
 * whole library) and cached for 30 days; "no graph" answers are cached for 7 days so a song without
 * enough views is not asked about again. Requests are throttled so that it cannot look like scraping.
 */
@OptIn(ExperimentalTime::class)
internal class PeakRepository(
    private val youTube: YouTube,
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope,
) : PeakProvider {
    private class Entry(
        val peak: Peak?,
        val fetchedAtSec: Long,
    )

    // Copy-on-write: readers on the audio-control path never see a map that is being modified.
    @Volatile private var memory: Map<String, Entry> = emptyMap()
    private val memoryLock = Mutex()
    private val inflight = HashMap<String, Deferred<Peak?>>()
    private val requestTimesSec = ArrayDeque<Long>()
    private val throttleLock = Mutex()

    @Volatile private var loaded = false

    init {
        scope.launch { ensureLoaded() }
    }

    override fun cachedPeak(videoId: String): Peak? {
        val e = memory[videoId] ?: return null
        return e.peak?.takeIf { nowSec() - e.fetchedAtSec < POSITIVE_TTL_SEC || it.source == PeakSource.MANUAL }
    }

    override suspend fun peakFor(videoId: String): Peak? {
        ensureLoaded()
        memory[videoId]?.let { e ->
            val ttl = if (e.peak == null) NEGATIVE_TTL_SEC else POSITIVE_TTL_SEC
            if (e.peak?.source == PeakSource.MANUAL || nowSec() - e.fetchedAtSec < ttl) return e.peak
        }
        val job =
            memoryLock.withLock {
                inflight.getOrPut(videoId) { scope.async { fetch(videoId) } }
            }
        return try {
            job.await()
        } finally {
            memoryLock.withLock { inflight.remove(videoId) }
        }
    }

    override fun prefetch(videoIds: List<String>) {
        videoIds.take(2).forEach { id ->
            if (cachedPeak(id) == null) scope.launch { runCatching { peakFor(id) } }
        }
    }

    override suspend fun setManualPeak(
        videoId: String,
        peakStartMs: Long,
    ) {
        ensureLoaded()
        store(videoId, Peak(peakStartMs, peakStartMs, 1f, PeakSource.MANUAL))
    }

    private suspend fun fetch(videoId: String): Peak? {
        waitForThrottleSlot()
        val replayed = youTube.getMostReplayed(videoId).getOrNull()
        if (replayed == null) {
            // Distinguish "no graph" from "request failed" is not possible here, and a failed request is
            // rare and cheap to retry after the short negative TTL, so both are treated as "no graph".
            store(videoId, null)
            return null
        }
        val buckets = replayed.markers.map { HeatBucket(it.startMs, it.durationMs, it.score) }
        val heat = PeakFusion.fromHeatmap(buckets, replayed.mostReplayedMs, audioDurationMs = 0L)
        val videoDurationMs = buckets.maxOfOrNull { it.endMs } ?: 0L
        val fused = PeakFusion.fuse(manual = null, heatmap = heat, analysis = null, lyrics = null, durationMs = videoDurationMs)
        store(videoId, fused)
        return fused
    }

    private suspend fun waitForThrottleSlot() {
        while (true) {
            val wait =
                throttleLock.withLock {
                    val now = nowSec()
                    while (requestTimesSec.isNotEmpty() && now - requestTimesSec.first() >= 60) requestTimesSec.removeFirst()
                    if (requestTimesSec.size < MAX_REQUESTS_PER_MINUTE) {
                        requestTimesSec.addLast(now)
                        0L
                    } else {
                        (60 - (now - requestTimesSec.first())).coerceAtLeast(1) * 1000L
                    }
                }
            if (wait == 0L) return
            delay(wait)
        }
    }

    private suspend fun store(
        videoId: String,
        peak: Peak?,
    ) {
        memoryLock.withLock {
            val next = HashMap(memory)
            next[videoId] = Entry(peak, nowSec())
            if (next.size > MAX_ENTRIES) {
                next.entries
                    .sortedBy { it.value.fetchedAtSec }
                    .take(next.size - MAX_ENTRIES)
                    .map { it.key }
                    .forEach { next.remove(it) }
            }
            memory = next
        }
        persist()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        memoryLock.withLock {
            if (loaded) return
            val raw = dataStoreManager.sonaraPeakCache.first()
            val restored = HashMap<String, Entry>(memory)
            raw.lineSequence().forEach { line ->
                parseLine(line)?.let { (id, e) -> if (id !in restored) restored[id] = e }
            }
            memory = restored
            loaded = true
        }
    }

    private suspend fun persist() {
        val text =
            memoryLock.withLock {
                memory.entries.joinToString("\n") { (id, e) ->
                    val p = e.peak
                    if (p == null) {
                        "$id|-|${e.fetchedAtSec}"
                    } else {
                        "$id|${p.peakStartMs}|${p.peakMs}|${p.confidence}|${p.source.name}|${e.fetchedAtSec}"
                    }
                }
            }
        dataStoreManager.setSonaraPeakCache(text)
    }

    private fun parseLine(line: String): Pair<String, Entry>? {
        val f = line.split('|')
        return runCatching {
            when {
                f.size == 3 && f[1] == "-" -> f[0] to Entry(null, f[2].toLong())
                f.size == 6 ->
                    f[0] to
                        Entry(
                            Peak(f[1].toLong(), f[2].toLong(), f[3].toFloat(), PeakSource.valueOf(f[4])),
                            f[5].toLong(),
                        )
                else -> null
            }
        }.getOrNull()
    }

    private fun nowSec(): Long = Clock.System.now().epochSeconds

    private companion object {
        const val POSITIVE_TTL_SEC = 30L * 24 * 3600
        const val NEGATIVE_TTL_SEC = 7L * 24 * 3600
        const val MAX_ENTRIES = 500
        const val MAX_REQUESTS_PER_MINUTE = 6
    }
}
