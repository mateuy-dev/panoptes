package dev.mateuy.panoptes.domain.port

import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion

interface StoreAdapter {
    val storeName: String
    val supportedTracks: List<Track>
    suspend fun getVersions(): List<TrackVersion>
    suspend fun promote(fromTrack: Track, toTrack: Track)

    /** Track a build on [from] is promoted to: the next track this store supports, or null if it can't be promoted. */
    fun promotionTarget(from: Track): Track? =
        if (from in supportedTracks) supportedTracks.filter { it > from }.minOrNull() else null
}
