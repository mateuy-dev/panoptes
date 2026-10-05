package dev.mateuy.panoptes.adapter.dmg

import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.StoreAdapter
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.bodyOrError
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/** A self-hosted macOS DMG: a public endpoint reports the release currently offered for download. */
class DmgAdapter(
    private val httpClient: HttpClient,
    private val configReader: ConfigReader,
) : StoreAdapter {

    override val storeName = "macOS DMG"
    override val supportedTracks = listOf(Track.PRODUCTION)

    private val releaseUrl: String get() = configReader.appId("dmg")

    override suspend fun getVersions(): List<TrackVersion> {
        val url = releaseUrl.ifEmpty { error("DMG release URL not configured — set it in [3] Settings") }
        val release: DmgRelease = httpClient.get(url) {
            accept(ContentType.Application.Json)
        }.bodyOrError("DMG release lookup")

        return listOf(
            TrackVersion(
                track = Track.PRODUCTION,
                versionName = release.version,
                versionCode = 0L,
                status = ReleaseStatus.PUBLISHED,
            )
        )
    }

    // The DMG is published by uploading it to the server, so there's nothing to promote from.
    override suspend fun promote(fromTrack: Track, toTrack: Track) {
        error("DMG releases can't be promoted — upload the new DMG to the server")
    }

    @Serializable
    private data class DmgRelease(val version: String)
}
