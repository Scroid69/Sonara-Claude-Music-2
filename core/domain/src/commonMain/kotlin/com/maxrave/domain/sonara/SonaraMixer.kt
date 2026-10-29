package com.maxrave.domain.sonara

import com.maxrave.domain.data.model.browse.album.Track

/** Builds queues for "New to you" and "Create your own Mix". UI code only sees this interface. */
interface SonaraMixer {
    /** Fresh songs related to [seedVideoIds] that the user has never played. Empty when nothing usable came back. */
    suspend fun newToYou(
        seedVideoIds: List<String>,
        limit: Int = 50,
    ): List<Track>

    /**
     * A queue blended from the radios of [seedVideoIds].
     * @param discover 0 = songs you know, 1 = only songs you have not heard.
     */
    suspend fun createMix(
        seedVideoIds: List<String>,
        discover: Float,
        limit: Int = 60,
    ): List<Track>
}
