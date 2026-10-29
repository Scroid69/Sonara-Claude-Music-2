package com.maxrave.data.repository

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.sonara.Candidate
import com.maxrave.domain.sonara.Heard
import com.maxrave.domain.sonara.MixBuilder
import com.maxrave.domain.sonara.SonaraMixer
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Orchestrates the radios; the ranking itself is [MixBuilder] (pure, unit-tested).
 *
 * "Heard" is everything the app has ever stored locally: every song that was played, liked or saved is
 * written to the songs table, so the table is the listening record. Disliked songs are the ones whose
 * like status says so.
 */
internal class SonaraMixerImpl(
    private val songRepository: SongRepository,
) : SonaraMixer {
    override suspend fun newToYou(
        seedVideoIds: List<String>,
        limit: Int,
    ): List<Track> {
        val (tracksById, perSeed) = radios(seedVideoIds.take(MAX_SEEDS))
        val heard = loadHeard()
        val picked = MixBuilder.newToYou(perSeed.flatten(), heard, limit = limit)
        return picked.mapNotNull { tracksById[it.videoId] }
    }

    override suspend fun createMix(
        seedVideoIds: List<String>,
        discover: Float,
        limit: Int,
    ): List<Track> {
        val (tracksById, perSeed) = radios(seedVideoIds.take(MAX_SEEDS))
        val heard = loadHeard()
        val picked = MixBuilder.mix(perSeed, heard, discover, limit)
        return picked.mapNotNull { tracksById[it.videoId] }
    }

    private suspend fun radios(seeds: List<String>): Pair<Map<String, Track>, List<List<Candidate>>> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val results =
                    seeds
                        .mapIndexed { index, id ->
                            async {
                                val res =
                                    songRepository
                                        .getRadioFromEndpoint(YouTubeWatchEndpoint(videoId = id, playlistId = "RDAMVM$id"))
                                        .firstOrNull { it is Resource.Success }
                                index to (res?.data?.first.orEmpty())
                            }
                        }.map { it.await() }
                val byId = LinkedHashMap<String, Track>()
                val lists =
                    results.map { (index, tracks) ->
                        tracks.map { t ->
                            byId.putIfAbsent(t.videoId, t)
                            Candidate(t.videoId, artistKey(t), t.title, index)
                        }
                    }
                byId to lists
            }
        }

    private suspend fun loadHeard(): Heard {
        val songs = songRepository.getAllSongs(HEARD_LIMIT).firstOrNull().orEmpty()
        val plays = HashMap<String, Int>()
        val disliked = HashSet<String>()
        songs.forEach { s ->
            val keys = (s.artistId ?: s.artistName).orEmpty().map { it.lowercase() }
            keys.forEach { plays[it] = (plays[it] ?: 0) + 1 }
            if (s.likeStatus.equals("DISLIKE", ignoreCase = true)) disliked += s.videoId
        }
        return Heard(videoIds = songs.map { it.videoId }.toSet(), artistPlays = plays, dislikedVideoIds = disliked)
    }

    private fun artistKey(t: Track): String {
        val a = t.artists?.firstOrNull()
        return (a?.id ?: a?.name ?: "").lowercase()
    }

    private companion object {
        const val MAX_SEEDS = 8
        const val HEARD_LIMIT = 20_000
    }
}
