package com.maxrave.domain.sonara

/** A song a radio or search returned, reduced to what ranking needs. */
data class Candidate(
    val videoId: String,
    /** Stable key for the main artist (browse id if known, else lower-cased name). */
    val artistKey: String,
    val title: String = "",
    /** Which seed produced it (index into the seed list), so a mix can spread across seeds. */
    val seedIndex: Int = 0,
)

/** Everything the user has already heard, liked, or rejected. */
data class Heard(
    val videoIds: Set<String> = emptySet(),
    /** How many times each artist has been played, by [Candidate.artistKey]. */
    val artistPlays: Map<String, Int> = emptyMap(),
    val dislikedVideoIds: Set<String> = emptySet(),
    val dislikedArtistKeys: Set<String> = emptySet(),
)

object MixBuilder {
    /** At most this many songs by one artist in a generated queue. */
    const val MAX_PER_ARTIST = 2

    /**
     * "New to you": songs that are related to what you play but that you have never played.
     *
     * - drops everything heard or disliked, and anything by a disliked artist,
     * - prefers artists you have barely or never heard (fresh) over ones you play constantly,
     * - at most [MAX_PER_ARTIST] per artist, no artist back to back,
     * - keeps the radios' own relevance order within each artist tier.
     *
     * @param allowKnownArtists false (default) restricts the result to artists you have never played.
     * If that leaves fewer than [minResults], the rule relaxes to artists you have played at most 2 times.
     */
    fun newToYou(
        candidates: List<Candidate>,
        heard: Heard,
        limit: Int = 50,
        allowKnownArtists: Boolean = false,
        minResults: Int = 15,
    ): List<Candidate> {
        val fresh =
            candidates
                .asSequence()
                .distinctBy { it.videoId }
                .filter { it.videoId !in heard.videoIds && it.videoId !in heard.dislikedVideoIds }
                .filter { it.artistKey !in heard.dislikedArtistKeys }
                .toList()

        fun pick(maxArtistPlays: Int): List<Candidate> =
            spread(fresh.filter { (heard.artistPlays[it.artistKey] ?: 0) <= maxArtistPlays }, limit)

        if (allowKnownArtists) return spread(fresh.sortedBy { heard.artistPlays[it.artistKey] ?: 0 }, limit)
        val strict = pick(0)
        return if (strict.size >= minResults) strict else pick(2)
    }

    /**
     * "Create your own Mix": blends the radios of several seeds into one queue.
     *
     * @param perSeed candidates grouped by seed, each list in the radio's relevance order
     * @param discover 0f = only songs you know (familiar), 1f = only songs you have not heard.
     * Familiar songs are those in [Heard.videoIds]; at 0.5 the queue alternates evenly.
     */
    fun mix(
        perSeed: List<List<Candidate>>,
        heard: Heard,
        discover: Float,
        limit: Int = 60,
    ): List<Candidate> {
        val d = discover.coerceIn(0f, 1f)
        val seen = HashSet<String>()
        val pooled = interleave(perSeed).filter { it.videoId !in heard.dislikedVideoIds && it.artistKey !in heard.dislikedArtistKeys && seen.add(it.videoId) }
        val familiar = pooled.filter { it.videoId in heard.videoIds }.toMutableList()
        val novel = pooled.filter { it.videoId !in heard.videoIds }.toMutableList()

        // Error-diffusion scheduling: every step, whichever pile is furthest behind its quota goes next.
        val out = ArrayList<Candidate>()
        var wantNovel = 0.0
        while (out.size < limit && (familiar.isNotEmpty() || novel.isNotEmpty())) {
            wantNovel += d
            val takeNovel =
                when {
                    novel.isEmpty() -> false
                    familiar.isEmpty() -> true
                    else -> d >= 1f || (d > 0f && wantNovel >= 0.5)
                }
            if (takeNovel) {
                out += novel.removeAt(0)
                wantNovel -= 1.0
            } else {
                out += familiar.removeAt(0)
            }
        }
        return spread(out, limit)
    }

    /** Round-robin across seeds so no seed dominates the start of the queue. */
    fun interleave(lists: List<List<Candidate>>): List<Candidate> {
        val out = ArrayList<Candidate>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out += it }
        return out
    }

    /** Enforces the per-artist cap and "no same artist back to back" while keeping order as much as possible. */
    fun spread(
        input: List<Candidate>,
        limit: Int,
    ): List<Candidate> {
        val counts = HashMap<String, Int>()
        val pending = ArrayList(input)
        val out = ArrayList<Candidate>()
        while (out.size < limit && pending.isNotEmpty()) {
            val last = out.lastOrNull()?.artistKey
            val idx = pending.indexOfFirst { (counts[it.artistKey] ?: 0) < MAX_PER_ARTIST && it.artistKey != last }
            // Only same-artist songs are left: stop rather than break the back-to-back rule.
            if (idx < 0) break
            val c = pending.removeAt(idx)
            counts[c.artistKey] = (counts[c.artistKey] ?: 0) + 1
            out += c
        }
        return out
    }
}
