package com.maxrave.kotlinytmusicscraper.sonara

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Fixtures are hand-built from the documented shapes, not captured from live traffic, so they pin the
 * parser's behaviour and its tolerance for layout changes, not YouTube's current output.
 */
class MostReplayedParserTest {
    private fun heatMarkers(wrapped: Boolean): String {
        val items =
            (0 until 10).joinToString(",") { i ->
                val body =
                    """"timeRangeStartMillis": ${i * 20000}, "markerDurationMillis": 20000, "heatMarkerIntensityScoreNormalized": ${if (i == 4) 1.0 else 0.3}"""
                if (wrapped) """{"heatMarkerRenderer": {$body}}""" else "{$body}"
            }
        return items
    }

    @Test
    fun parsesWrappedHeatMarkersDeepInsideTheResponse() {
        val json =
            """{"a":{"b":[{"c":{"heatMarkers":[${heatMarkers(true)}],"x":1}}]},
               "timedMarkerDecorations":[{"visibleTimeRangeStartMillis":0,"decorationTimeMillis":88000,"label":{"runs":[{"text":"Most replayed"}]}}]}"""
        val r = assertNotNull(MostReplayedParser.parse(json))
        assertEquals(10, r.markers.size)
        assertEquals(1.0f, r.markers[4].score)
        assertEquals(80_000, r.markers[4].startMs)
        assertEquals(88_000L, r.mostReplayedMs)
    }

    @Test
    fun parsesUnwrappedMarkersAndStringNumbers() {
        val items =
            (0 until 8).joinToString(",") { i ->
                """{"startMillis":"${i * 5000}","durationMillis":"5000","intensityScoreNormalized":${if (i == 2) 0.9 else 0.2}}"""
            }
        val r = assertNotNull(MostReplayedParser.parse("""{"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"payload":{"macroMarkersListEntity":{"markersList":{"markers":[$items]}}}}]}}}"""))
        assertEquals(8, r.markers.size)
        assertNull(r.mostReplayedMs)
    }

    @Test
    fun returnsNullWhenThereIsNoGraph() {
        assertNull(MostReplayedParser.parse("""{"contents":{"x":[]}}"""))
        assertNull(MostReplayedParser.parse("not json at all"))
        assertNull(MostReplayedParser.parse("""{"heatMarkers":[{"timeRangeStartMillis":0,"markerDurationMillis":1000,"heatMarkerIntensityScoreNormalized":1}]}"""))
    }

    @Test
    fun findsTheSongVideoCounterpart() {
        val json =
            """{"contents":{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"videoId":"AAAAAAAAAAA",
               "counterpart":[{"counterpartRenderer":{"playlistPanelVideoRenderer":{"videoId":"BBBBBBBBBBB"}}}]}}]}}}"""
        assertEquals("BBBBBBBBBBB", MostReplayedParser.findCounterpartVideoId(json, "AAAAAAAAAAA"))
        assertNull(MostReplayedParser.findCounterpartVideoId("""{"contents":{}}""", "AAAAAAAAAAA"))
    }
}
