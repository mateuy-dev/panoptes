package dev.mateuy.panoptes.adapter.googleplay

import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.domain.port.StoreAdapter
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.bodyOrError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class GooglePlayAdapter(
    private val httpClient: HttpClient,
    private val credentialStore: CredentialStore,
    private val configReader: ConfigReader,
) : StoreAdapter {

    override val storeName = "Google Play"

    override val supportedTracks = listOf(
        Track.INTERNAL, Track.ALPHA, Track.BETA, Track.PRODUCTION
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private val packageName: String
        get() = configReader.appId("googleplay")

    private suspend fun getAccessToken(): String {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAt) return cachedToken!!

        val serviceAccountJson = credentialStore.get("googleplay", "serviceAccountJson")
            ?: error("Google Play service account JSON not configured")

        val serviceAccount = json.decodeFromString<ServiceAccountJson>(serviceAccountJson)
        val jwt = GooglePlayJwtHelper.createJwt(serviceAccount.clientEmail, serviceAccount.privateKey)

        val response = json.decodeFromString<TokenResponse>(
            httpClient.submitForm(
                url = "https://oauth2.googleapis.com/token",
                formParameters = parameters {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    append("assertion", jwt)
                }
            ).bodyOrError<String>("Google OAuth token request")
        )

        cachedToken = response.accessToken
        tokenExpiresAt = now + (response.expiresIn - 60) * 1000L
        return cachedToken!!
    }

    private val api = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"

    /** Tracks can only be read or changed inside an edit; the edit is deleted unless [block] commits it. */
    private suspend fun <T> withEdit(pkg: String, block: suspend (editUrl: String, token: String) -> T): T {
        val token = getAccessToken()
        val edit: EditResponse = httpClient.post("$api/$pkg/edits") {
            bearerAuth(token)
        }.bodyOrError("Google Play edit creation (check package name and service account permissions)")
        val editUrl = "$api/$pkg/edits/${edit.id}"
        return try {
            block(editUrl, token)
        } finally {
            // No-op after a successful commit; otherwise discards the edit
            runCatching { httpClient.delete(editUrl) { bearerAuth(token) } }
        }
    }

    private suspend fun fetchTracks(editUrl: String, token: String): List<TrackDto> =
        httpClient.get("$editUrl/tracks") {
            bearerAuth(token)
        }.bodyOrError<TracksResponse>("Google Play tracks request").tracks

    override suspend fun getVersions(): List<TrackVersion> {
        val pkg = packageName.ifEmpty { error("Package name not configured — set it in [3] Settings") }
        val tracks = withEdit(pkg) { editUrl, token -> fetchTracks(editUrl, token) }

        val versions = mutableListOf<TrackVersion>()
        for (track in tracks) {
            val panoptesTrack = mapTrack(track.track) ?: continue
            // A track can hold several releases (e.g. a completed one plus a staged rollout); show the newest
            val release = track.releases.maxByOrNull { it.highestVersionCode() ?: -1 } ?: continue
            val versionCode = release.highestVersionCode() ?: continue
            val lifecycle = fetchLifecycleState(pkg, track.track, versionCode)
            versions += TrackVersion(
                track = panoptesTrack,
                versionName = release.name ?: versionCode.toString(),
                versionCode = versionCode,
                status = mapLifecycle(lifecycle) ?: mapStatus(release.status),
                note = describeLifecycle(lifecycle),
            )
        }
        return versions
    }

    /**
     * An edit reports a release as "completed" as soon as it is committed, even while Google is still reviewing it;
     * only the release summaries say where it is in review. Null if they can't be read or don't list the release.
     */
    private suspend fun fetchLifecycleState(pkg: String, playTrack: String, versionCode: Long): String? = runCatching {
        httpClient.get("$api/$pkg/tracks/$playTrack/releases") {
            bearerAuth(getAccessToken())
        }.bodyOrError<ReleaseSummariesResponse>("Google Play release summaries request").releases
            .find { summary -> summary.activeArtifacts.any { it.versionCode == versionCode } }
            ?.releaseLifecycleState
    }.getOrNull()

    override suspend fun promote(fromTrack: Track, toTrack: Track) {
        val pkg = packageName.ifEmpty { error("Package name not configured") }

        withEdit(pkg) { editUrl, token ->
            val sourceRelease = fetchTracks(editUrl, token)
                .find { it.track == mapTrackToPlay(fromTrack) }
                ?.releases
                ?.filter { it.status != "draft" }
                ?.maxByOrNull { it.highestVersionCode() ?: -1 }
                ?: error("No release found on track $fromTrack")

            val targetTrackName = mapTrackToPlay(toTrack)
            val release = ReleaseDto(
                name = sourceRelease.name,
                versionCodes = sourceRelease.versionCodes,
                status = "completed",
                releaseNotes = sourceRelease.releaseNotes,
            )
            httpClient.put("$editUrl/tracks/$targetTrackName") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(TrackDto.serializer(), TrackDto(targetTrackName, listOf(release))))
            }.bodyOrError<String>("Google Play track update")

            httpClient.post("$editUrl:commit") {
                bearerAuth(token)
            }.bodyOrError<String>("Google Play edit commit")
        }
    }

    private fun mapTrack(playTrack: String): Track? = when (playTrack) {
        "internal" -> Track.INTERNAL
        "alpha" -> Track.ALPHA
        "beta" -> Track.BETA
        "production" -> Track.PRODUCTION
        else -> null
    }

    private fun mapTrackToPlay(track: Track): String = when (track) {
        Track.INTERNAL -> "internal"
        Track.ALPHA -> "alpha"
        Track.BETA -> "beta"
        Track.PRODUCTION -> "production"
    }

    private fun mapStatus(status: String?): ReleaseStatus = when (status) {
        "completed" -> ReleaseStatus.PUBLISHED
        "inProgress" -> ReleaseStatus.PUBLISHED
        "draft" -> ReleaseStatus.DRAFT
        "halted" -> ReleaseStatus.HALTED
        else -> ReleaseStatus.UNKNOWN
    }

    /** Null when the release is live (or its state is unknown), so the edit's own status applies. */
    private fun mapLifecycle(state: String?): ReleaseStatus? = when (state) {
        "RELEASE_LIFECYCLE_STATE_DRAFT", "RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW" -> ReleaseStatus.DRAFT
        "RELEASE_LIFECYCLE_STATE_IN_REVIEW", "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED" -> ReleaseStatus.IN_REVIEW
        "RELEASE_LIFECYCLE_STATE_NOT_APPROVED" -> ReleaseStatus.HALTED
        else -> null
    }

    private fun describeLifecycle(state: String?): String? = when (state) {
        "RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW" -> "ready, but not sent for review yet"
        "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED" -> "approved, waiting to be published manually"
        "RELEASE_LIFECYCLE_STATE_NOT_APPROVED" -> "rejected in review"
        else -> null
    }

    @Serializable
    private data class ServiceAccountJson(
        @SerialName("client_email") val clientEmail: String,
        @SerialName("private_key") val privateKey: String,
        @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class EditResponse(val id: String)

    @Serializable
    private data class TracksResponse(
        val tracks: List<TrackDto> = emptyList(),
    )

    @Serializable
    private data class TrackDto(
        val track: String,
        val releases: List<ReleaseDto> = emptyList(),
    )

    @Serializable
    private data class ReleaseDto(
        val name: String? = null,
        val versionCodes: List<String>? = null,
        val status: String? = null,
        val releaseNotes: JsonElement? = null,
    ) {
        fun highestVersionCode(): Long? = versionCodes?.mapNotNull { it.toLongOrNull() }?.maxOrNull()
    }

    @Serializable
    private data class ReleaseSummariesResponse(
        val releases: List<ReleaseSummaryDto> = emptyList(),
    )

    @Serializable
    private data class ReleaseSummaryDto(
        val releaseLifecycleState: String? = null,
        val activeArtifacts: List<ArtifactSummaryDto> = emptyList(),
    )

    @Serializable
    private data class ArtifactSummaryDto(val versionCode: Long? = null)
}
