package com.maxrave.domain.sonara

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixBuilderTest {
    private fun c(id: String, artist: String, seed: Int = 0) = Candidate(id, artist, id, seed)

    @Test
    fun newToYouNeverReturnsAHeardOrDislikedSong() {
        val cands = (1..40).map { c("s$it", "a$it") }
        val heard = Heard(videoIds = setOf("s1", "s2", "s3"), dislikedVideoIds = setOf("s4"), dislikedArtistKeys = setOf("a5"))
        val out = MixBuilder.newToYou(cands, heard, limit = 30, minResults = 5)
        assertTrue(out.none { it.videoId in setOf("s1", "s2", "s3", "s4", "s5") })
        assertEquals(30, out.size)
    }

    @Test
    fun newToYouPrefersUnheardArtists() {
        val cands = listOf(c("x1", "known"), c("y1", "stranger1"), c("y2", "stranger2"), c("y3", "stranger3"))
        val out = MixBuilder.newToYou(cands, Heard(artistPlays = mapOf("known" to 50)), minResults = 3)
        assertEquals(listOf("y1", "y2", "y3"), out.map { it.videoId })
    }

    @Test
    fun newToYouRelaxesWhenTooFewFreshArtists() {
        val cands = listOf(c("x1", "light"), c("x2", "heavy"), c("y1", "stranger"))
        val heard = Heard(artistPlays = mapOf("light" to 2, "heavy" to 80))
        val out = MixBuilder.newToYou(cands, heard, minResults = 2)
        assertEquals(listOf("x1", "y1").sorted(), out.map { it.videoId }.sorted())
    }

    @Test
    fun capAndNoBackToBackArtist() {
        val cands = listOf(c("a1", "A"), c("a2", "A"), c("a3", "A"), c("b1", "B"), c("b2", "B"), c("c1", "C"))
        val out = MixBuilder.spread(cands, 10)
        assertTrue(out.groupBy { it.artistKey }.values.all { it.size <= MixBuilder.MAX_PER_ARTIST })
        assertTrue(out.zipWithNext().none { (x, y) -> x.artistKey == y.artistKey })
    }

    @Test
    fun mixDiscoverZeroIsAllFamiliarAndOneIsAllNew() {
        val seedA = (1..10).map { c("k$it", "ka$it") } + (1..10).map { c("n$it", "na$it") }
        val heard = Heard(videoIds = (1..10).map { "k$it" }.toSet())
        val familiar = MixBuilder.mix(listOf(seedA), heard, discover = 0f, limit = 8)
        assertTrue(familiar.take(8).all { it.videoId.startsWith("k") })
        val novel = MixBuilder.mix(listOf(seedA), heard, discover = 1f, limit = 8)
        assertTrue(novel.all { it.videoId.startsWith("n") })
    }

    @Test
    fun mixHalfAlternates() {
        val s = (1..10).map { c("k$it", "ka$it") } + (1..10).map { c("n$it", "na$it") }
        val heard = Heard(videoIds = (1..10).map { "k$it" }.toSet())
        val out = MixBuilder.mix(listOf(s), heard, 0.5f, limit = 10)
        val novelCount = out.count { it.videoId.startsWith("n") }
        assertTrue(novelCount in 4..6, "novel=$novelCount")
    }

    @Test
    fun mixInterleavesSeedsAndDedupes() {
        val a = listOf(c("1", "x"), c("2", "y"), c("3", "z"))
        val b = listOf(c("1", "x", 1), c("4", "w", 1), c("5", "v", 1))
        val out = MixBuilder.mix(listOf(a, b), Heard(), 1f, limit = 10)
        assertEquals(out.map { it.videoId }.distinct(), out.map { it.videoId })
        assertEquals(setOf("1", "2", "3", "4", "5"), out.map { it.videoId }.toSet())
    }

    @Test
    fun mixDropsDisliked() {
        val a = listOf(c("1", "x"), c("2", "bad"), c("3", "z"))
        val out = MixBuilder.mix(listOf(a), Heard(dislikedArtistKeys = setOf("bad")), 0.5f)
        assertFalse(out.any { it.artistKey == "bad" })
    }
}
