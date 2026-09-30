package dev.mateuy.panoptes.adapter.snap

import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.domain.port.StoreAdapter
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.bodyOrError
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class SnapAdapter(
    private val httpClient: HttpClient,
    private val credentialStore: CredentialStore,
    private val configReader: ConfigReader,
) : StoreAdapter {

    override val storeName = "Snap Store"
    override val supportedTracks = listOf(
        Track.INTERNAL, Track.ALPHA, Track.BETA, Track.PRODUCTION
    )

    private val api = "https://dashboard.snapcraft.io"

    private val snapName: String get() = configReader.appId("snap")

    private fun authorization(): String {
        val raw = credentialStore.get("snap", "macaroon")?.ifEmpty { null }
            ?: error("Snapcraft credentials not configured")
        return SnapCredentials.parse(raw).authorizationHeader()
    }

    private suspend fun channelMap(name: String, auth: String): SnapChannelMapResponse {
        val response = httpClient.get("$api/api/v2/snaps/$name/channel-map") {
            header(HttpHeaders.Authorization, auth)
            accept(ContentType.Application.Json)
        }
        if (response.status == HttpStatusCode.Unauthorized && "Expired macaroon" in response.bodyAsText()) {
            error("Snapcraft credentials expired — regenerate them with `snapcraft export-login` and re-import")
        }
        return response.bodyOrError("Snap channel-map request")
    }

    override suspend fun getVersions(): List<TrackVersion> {
        val name = snapName.ifEmpty { error("Snap name not configured — set it in [3] Settings") }
        val response = channelMap(name, authorization())
        val versionByRevision = response.revisions.associate { it.revision to it.version }

        // One entry per architecture; show the newest revision in each channel
        return response.channelMap
            .groupBy { mapChannel(it.channel) }
            .mapNotNull { (track, entries) ->
                val entry = entries.maxBy { it.revision }
                TrackVersion(
                    track = track ?: return@mapNotNull null,
                    versionName = versionByRevision[entry.revision] ?: "r${entry.revision}",
                    versionCode = entry.revision.toLong(),
                    status = ReleaseStatus.PUBLISHED,
                )
            }
    }

    override suspend fun promote(fromTrack: Track, toTrack: Track) {
        val name = snapName.ifEmpty { error("Snap name not configured") }
        val auth = authorization()

        // Release each architecture's revision from the source channel to the target channel
        val sourceEntries = channelMap(name, auth).channelMap.filter { mapChannel(it.channel) == fromTrack }
        if (sourceEntries.isEmpty()) error("No revision found on track $fromTrack")

        val targetChannel = mapTrackToChannel(toTrack)
        sourceEntries.map { it.revision }.distinct().forEach { revision ->
            httpClient.post("$api/dev/api/snap-release/") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"$name","revision":"$revision","channels":["$targetChannel"]}""")
            }.bodyOrError<String>("Snap release of revision $revision to $targetChannel")
        }
    }

    private fun mapChannel(channel: String): Track? = when (channel) {
        "latest/edge", "edge" -> Track.INTERNAL
        "latest/beta", "beta" -> Track.ALPHA
        "latest/candidate", "candidate" -> Track.BETA
        "latest/stable", "stable" -> Track.PRODUCTION
        else -> null
    }

    private fun mapTrackToChannel(track: Track): String = when (track) {
        Track.INTERNAL -> "latest/edge"
        Track.ALPHA -> "latest/beta"
        Track.BETA -> "latest/candidate"
        Track.PRODUCTION -> "latest/stable"
    }

    @Serializable
    private data class SnapChannelMapResponse(
        @SerialName("channel-map") val channelMap: List<SnapChannelEntry> = emptyList(),
        val revisions: List<SnapRevision> = emptyList(),
    )

    @Serializable
    private data class SnapChannelEntry(
        val architecture: String,
        val channel: String,
        val revision: Int,
    )

    @Serializable
    private data class SnapRevision(
        val revision: Int,
        val version: String,
    )
}
