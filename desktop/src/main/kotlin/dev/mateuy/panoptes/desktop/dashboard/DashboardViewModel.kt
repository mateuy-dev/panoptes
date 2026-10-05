package dev.mateuy.panoptes.desktop.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mateuy.panoptes.application.PromoteBuildUseCase
import dev.mateuy.panoptes.application.StoreResult
import dev.mateuy.panoptes.application.ViewVersionsUseCase
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.StoreAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

data class StoreState(
    val storeName: String,
    val supportedTracks: List<Track>,
    /** Source track → track it can be promoted to. */
    val promotions: Map<Track, Track>,
    val isLoading: Boolean = true,
    val error: String? = null,
    val versions: Map<Track, TrackVersion> = emptyMap(),
) {
    val notes: List<Pair<Track, String>>
        get() = versions.values.mapNotNull { v -> v.note?.let { v.track to it } }
}

data class PendingPromotion(
    val storeName: String,
    val from: Track,
    val to: Track,
    val versionName: String?,
)

data class DashboardUiState(
    val stores: List<StoreState>,
    val pendingPromotion: PendingPromotion? = null,
    val isPromoting: Boolean = false,
    /** One-off message for the snackbar. */
    val message: String? = null,
    /** When the last refresh of all stores finished. */
    val lastSync: LocalTime? = null,
) {
    val isRefreshing get() = stores.any { it.isLoading }

    /**
     * Version every store should be shipping: the newest one live in Production anywhere,
     * or the newest one on any track if nothing is in Production yet.
     */
    val target: TrackVersion? by lazy {
        val loaded = stores.flatMap { it.versions.values }.filter { it.versionKey() != null }
        (loaded.filter { it.track == Track.PRODUCTION }.ifEmpty { loaded })
            .maxWithOrNull(compareBy(VERSION_ORDER) { it.versionKey()!! })
    }

    /** Stores with versions loaded, and how many of them ship [target] on their most advanced track. */
    val syncCount: Pair<Int, Int> by lazy {
        val loaded = stores.filter { it.headline != null }
        loaded.count { behindTarget(it) == null } to loaded.size
    }

    /** How far [store]'s most advanced track lags behind [target], or null if it's in sync. */
    fun behindTarget(store: StoreState): Behind? {
        val targetKey = target?.versionKey() ?: return null
        val key = store.headline?.versionKey() ?: return null
        if (VERSION_ORDER.compare(key, targetKey) >= 0) return null
        // Count patch releases when only the last component differs, e.g. 4.2.60 → 4.2.63
        val sameBase = key.size == targetKey.size && key.dropLast(1) == targetKey.dropLast(1)
        return Behind(store.headline!!.track, if (sameBase) targetKey.last() - key.last() else null)
    }
}

/** A store lagging behind the target on [track]; [releases] is null when the gap can't be counted. */
data class Behind(val track: Track, val releases: Int?)

/** Release on the most advanced track the store has one on. */
val StoreState.headline: TrackVersion?
    get() = versions.values.maxByOrNull { it.track }

/** Numeric components of a version like "4.2.62 (451)" or "4.2.60.0", without trailing zeros; null if unparseable. */
fun TrackVersion.versionKey(): List<Int>? {
    val parts = versionName.substringBefore(' ').split('.').map { it.toIntOrNull() ?: return null }
    return parts.dropLastWhile { it == 0 }.ifEmpty { null }
}

private val VERSION_ORDER = Comparator<List<Int>> { a, b ->
    (0 until maxOf(a.size, b.size))
        .map { (a.getOrNull(it) ?: 0).compareTo(b.getOrNull(it) ?: 0) }
        .firstOrNull { it != 0 } ?: 0
}

class DashboardViewModel(
    adapters: List<StoreAdapter>,
    private val viewVersions: ViewVersionsUseCase,
    private val promoteBuild: PromoteBuildUseCase,
) : ViewModel() {

    private val initialStores = adapters.map { adapter ->
        StoreState(
            storeName = adapter.storeName,
            supportedTracks = adapter.supportedTracks,
            promotions = adapter.supportedTracks
                .mapNotNull { from -> adapter.promotionTarget(from)?.let { from to it } }
                .toMap(),
        )
    }

    private val _state = MutableStateFlow(DashboardUiState(initialStores))
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private val storeRefreshJobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        storeRefreshJobs.values.forEach { it.cancel() }
        storeRefreshJobs.clear()
        refreshJob = viewModelScope.launch {
            viewVersions.execute().collect(::applyResult)
            _state.update { it.copy(lastSync = LocalTime.now()) }
        }
    }

    /** Reloads only [storeName], leaving the other stores as they are. */
    private fun refreshStore(storeName: String) {
        // A full refresh still in flight may have read this store before the change, so start it over
        if (refreshJob?.isActive == true) return refresh()

        storeRefreshJobs[storeName]?.cancel()
        storeRefreshJobs[storeName] = viewModelScope.launch {
            viewVersions.execute(storeName).collect(::applyResult)
        }
    }

    private fun applyResult(result: StoreResult) = updateStore(result.storeName) {
        it.copy(
            isLoading = result.isLoading,
            error = result.error,
            versions = result.versions.orEmpty().associateBy(TrackVersion::track),
        )
    }

    fun requestPromotion(storeName: String, from: Track) {
        val store = _state.value.stores.find { it.storeName == storeName } ?: return
        val to = store.promotions[from] ?: return
        _state.update {
            it.copy(pendingPromotion = PendingPromotion(storeName, from, to, store.versions[from]?.versionName))
        }
    }

    fun dismissPromotion() {
        if (!_state.value.isPromoting) _state.update { it.copy(pendingPromotion = null) }
    }

    fun confirmPromotion() {
        val promotion = _state.value.pendingPromotion ?: return
        if (_state.value.isPromoting) return

        _state.update { it.copy(isPromoting = true) }
        viewModelScope.launch {
            val result = promoteBuild.execute(promotion.storeName, promotion.from)
            val target = "${promotion.storeName} ${promotion.to.label}"
            _state.update {
                it.copy(
                    isPromoting = false,
                    pendingPromotion = null,
                    message = result.fold(
                        onSuccess = { "Promoted ${promotion.versionName ?: "build"} to $target" },
                        onFailure = { e -> "Promotion to $target failed: ${e.message}" },
                    ),
                )
            }
            if (result.isSuccess) refreshStore(promotion.storeName)
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun updateStore(storeName: String, transform: (StoreState) -> StoreState) = _state.update { state ->
        state.copy(stores = state.stores.map { if (it.storeName == storeName) transform(it) else it })
    }
}

val Track.label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
