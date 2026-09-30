package dev.mateuy.panoptes.application

import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.port.StoreAdapter

class PromoteBuildUseCase(
    private val adapters: List<StoreAdapter>,
) {
    suspend fun execute(storeName: String, fromTrack: Track): Result<Unit> {
        val adapter = adapters.find { it.storeName == storeName }
            ?: return Result.failure(IllegalArgumentException("Unknown store: $storeName"))

        if (fromTrack !in adapter.supportedTracks) {
            return Result.failure(IllegalArgumentException("Track $fromTrack not supported by $storeName"))
        }

        val toTrack = adapter.promotionTarget(fromTrack)
            ?: return Result.failure(IllegalArgumentException("$storeName can't promote from $fromTrack"))

        return try {
            adapter.promote(fromTrack, toTrack)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
