package com.maxrave.kotlinytmusicscraper.sonara

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One slice of the "Most replayed" graph. [score] is YouTube's normalised 0..1 intensity. */
data class ReplayMarker(
    val startMs: Long,
    val durationMs: Long,
    val score: Float,
)

data class MostReplayed(
    val markers: List<ReplayMarker>,
    /** The instant behind the "Most replayed" label, when YouTube sent one. */
    val mostReplayedMs: Long?,
)

/**
 * Pulls the replay graph out of a `next` response.
 *
 * The layout of that response has moved several times, so this scans the whole JSON tree for the three
 * shapes known to carry the data instead of trusting one path:
 *  1. `heatMarkers[]` with `timeRangeStartMillis`, `markerDurationMillis`, `heatMarkerIntensityScoreNormalized`
 *     (each element is either that object or wraps it in `heatMarkerRenderer`),
 *  2. `markers[]` with `startMillis`, `durationMillis`, `intensityScoreNormalized` (entity updates),
 *  3. `timedMarkerDecorations[]` with `decorationTimeMillis` for the "Most replayed" instant.
 */
object MostReplayedParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(responseText: String): MostReplayed? =
        runCatching { parse(json.parseToJsonElement(responseText)) }.getOrNull()

    fun parse(root: JsonElement): MostReplayed? {
        val markers = LinkedHashMap<Long, ReplayMarker>()
        var decoration: Long? = null
        walk(root) { obj ->
            obj["heatMarkers"]?.let { arr ->
                (arr as? JsonArray)?.forEach { item ->
                    val o = item as? JsonObject ?: return@forEach
                    val inner = (o["heatMarkerRenderer"] as? JsonObject) ?: o
                    toMarker(inner, "timeRangeStartMillis", "markerDurationMillis", "heatMarkerIntensityScoreNormalized")
                        ?.let { markers[it.startMs] = it }
                }
            }
            obj["markers"]?.let { arr ->
                (arr as? JsonArray)?.forEach { item ->
                    val o = item as? JsonObject ?: return@forEach
                    toMarker(o, "startMillis", "durationMillis", "intensityScoreNormalized")?.let { markers[it.startMs] = it }
                }
            }
            obj["timedMarkerDecorations"]?.let { arr ->
                (arr as? JsonArray)?.forEach { item ->
                    val o = item as? JsonObject ?: return@forEach
                    val t = o.long("decorationTimeMillis")
                    if (t != null && decoration == null) decoration = t
                }
            }
        }
        if (markers.size < 5) return null
        return MostReplayed(markers.values.sortedBy { it.startMs }, decoration)
    }

    /**
     * The other form of a song (Song <-> Video switcher) from a music.youtube.com `next` response.
     * The audio-only "Song" item often has no replay graph, but its music video usually does.
     */
    fun findCounterpartVideoId(
        responseText: String,
        ownVideoId: String,
    ): String? {
        val root = runCatching { json.parseToJsonElement(responseText) }.getOrNull() ?: return null
        var found: String? = null
        walk(root) { obj ->
            if (found != null) return@walk
            val counterpart = obj["counterpart"] as? JsonArray ?: return@walk
            counterpart.forEach { item ->
                var id: String? = null
                walk(item) { inner ->
                    if (id == null) {
                        (inner["videoId"] as? JsonPrimitive)?.content?.let { if (it.length == 11 && it != ownVideoId) id = it }
                    }
                }
                if (found == null) found = id
            }
        }
        return found
    }

    private fun toMarker(
        o: JsonObject,
        startKey: String,
        durationKey: String,
        scoreKey: String,
    ): ReplayMarker? {
        val start = o.long(startKey) ?: return null
        val duration = o.long(durationKey) ?: return null
        val score = o.double(scoreKey) ?: return null
        if (duration <= 0) return null
        return ReplayMarker(start, duration, score.toFloat().coerceIn(0f, 1f))
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun walk(
        node: JsonElement,
        visit: (JsonObject) -> Unit,
    ) {
        when (node) {
            is JsonObject -> {
                visit(node)
                node.values.forEach { walk(it, visit) }
            }

            is JsonArray -> node.forEach { walk(it, visit) }

            else -> Unit
        }
    }
}
