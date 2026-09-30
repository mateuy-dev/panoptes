package dev.mateuy.panoptes.application

import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.StoreAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

data class StoreResult(
    val storeName: String,
    val versions: List<TrackVersion>?,
    val error: String?,
) {
    val isLoading get() = versions == null && error == null
}

class ViewVersionsUseCase(
    private val adapters: List<StoreAdapter>,
) {
    fun execute(): Flow<StoreResult> = channelFlow {
        // Emit loading states first
        adapters.forEach { adapter ->
            send(StoreResult(adapter.storeName, null, null))
        }

        coroutineScope {
            adapters.map { adapter ->
                async {
                    val result = try {
                        StoreResult(adapter.storeName, adapter.getVersions(), null)
                    } catch (e: Exception) {
                        StoreResult(adapter.storeName, emptyList(), e.message ?: "Unknown error")
                    }
                    send(result)
                }
            }.forEach { it.await() }
        }
    }
}
