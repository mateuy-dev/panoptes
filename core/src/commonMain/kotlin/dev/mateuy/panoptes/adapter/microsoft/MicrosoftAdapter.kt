package dev.mateuy.panoptes.adapter.microsoft

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

class MicrosoftAdapter(
    private val httpClient: HttpClient,
    private val credentialStore: CredentialStore,
    private val configReader: ConfigReader,
) : StoreAdapter {

    override val storeName = "Microsoft Store"
    override val supportedTracks = listOf(Track.BETA, Track.PRODUCTION)

    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private val storeId: String get() = configReader.appId("microsoft")

    private suspend fun getAccessToken(): String {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAt) return cachedToken!!

        val tenantId = credentialStore.get("microsoft", "tenantId")
            ?: error("Microsoft tenant ID not configured")
        val clientId = credentialStore.get("microsoft", "clientId")
            ?: error("Microsoft client ID not configured")
        val clientSecret = credentialStore.get("microsoft", "clientSecret")
            ?: error("Microsoft client secret not configured")

        val response: MsTokenResponse = httpClient.submitForm(
            url = "https://login.microsoftonline.com/$tenantId/oauth2/token",
            formParameters = parameters {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("resource", "https://manage.devcenter.microsoft.com")
            }
        ).bodyOrError("Azure AD token request")

        cachedToken = response.accessToken
        tokenExpiresAt = now + (response.expiresIn.toLongOrNull() ?: 3600L) * 1000L - 60_000L
        return cachedToken!!
    }

    override suspend fun getVersions(): List<TrackVersion> {
        val id = storeId.ifEmpty { error("Product ID not configured — set it in [3] Settings") }
        val token = getAccessToken()
        val versions = mutableListOf<TrackVersion>()

        val base = "https://manage.devcenter.microsoft.com/v1.0/my/applications/$id"

        // Production: the live submission, unless a pending one is in certification/publishing.
        // A failed or uncommitted pending submission is reported as a note instead of hiding the live version.
        val appResponse: MsAppResponse = httpClient.get(base) {
            bearerAuth(token)
        }.bodyOrError("Microsoft Store app lookup")

        suspend fun submission(ref: MsSubmissionRef?): MsSubmissionResponse? = ref?.let {
            httpClient.get("$base/submissions/${it.id}") {
                bearerAuth(token)
            }.bodyOrError("Microsoft Store submission lookup")
        }
        val pending = submission(appResponse.pendingApplicationSubmission)
        val published = submission(appResponse.lastPublishedApplicationSubmission)

        val pendingInProgress = pending != null && mapSubmissionStatus(pending.status) == ReleaseStatus.IN_REVIEW
        val shown = if (pendingInProgress || published == null) pending else published
        shown?.let {
            versions += TrackVersion(
                track = Track.PRODUCTION,
                versionName = highestVersion(it.applicationPackages) ?: "?",
                versionCode = 0L,
                status = mapSubmissionStatus(it.status),
                note = pending?.takeIf { p -> p !== it || p.statusDetails?.errors?.isNotEmpty() == true }
                    ?.let { p -> describePending(p) },
            )
        }

        // Package flight submission (beta)
        try {
            val flightsResponse: MsFlightsResponse = httpClient.get("$base/listflights") {
                bearerAuth(token)
            }.bodyOrError("Microsoft Store flights lookup")

            flightsResponse.value.firstOrNull()?.let { flight ->
                val flightBase = "$base/flights/${flight.flightId}"
                val flightResponse: MsFlightResponse = httpClient.get(flightBase) {
                    bearerAuth(token)
                }.bodyOrError("Microsoft Store flight lookup")

                (flightResponse.pendingFlightSubmission ?: flightResponse.lastPublishedFlightSubmission)?.let { sub ->
                    val submission: MsFlightSubmissionDetail = httpClient.get("$flightBase/submissions/${sub.id}") {
                        bearerAuth(token)
                    }.bodyOrError("Microsoft Store flight submission lookup")

                    versions += TrackVersion(
                        track = Track.BETA,
                        versionName = highestVersion(submission.flightPackages) ?: "?",
                        versionCode = 0L,
                        status = mapSubmissionStatus(submission.status),
                    )
                }
            }
        } catch (_: Exception) { /* No flights configured */ }

        return versions
    }

    // The submission API can't copy a flight's packages into a production submission: packages must be
    // re-uploaded, so promotion has to happen in Partner Center (or the CI pipeline that uploads packages).
    override fun promotionTarget(from: Track): Track? = null

    override suspend fun promote(fromTrack: Track, toTrack: Track) {
        error("Microsoft Store promotion isn't supported via the API — publish the package from Partner Center")
    }

    private fun describePending(submission: MsSubmissionResponse): String {
        val version = highestVersion(submission.applicationPackages)?.let { " for $it" } ?: ""
        val errors = submission.statusDetails?.errors.orEmpty()
            .joinToString("; ") { listOfNotNull(it.code, it.details).joinToString(": ") }
        return "Pending submission$version is ${submission.status ?: "in an unknown state"}" +
            if (errors.isNotEmpty()) " — $errors" else ""
    }

    private fun highestVersion(packages: List<MsPackage>): String? =
        packages.filter { it.fileStatus != "PendingDelete" }
            .mapNotNull { it.version }
            .maxWithOrNull(compareBy<String>(
                { it.split('.').getOrNull(0)?.toIntOrNull() ?: 0 },
                { it.split('.').getOrNull(1)?.toIntOrNull() ?: 0 },
                { it.split('.').getOrNull(2)?.toIntOrNull() ?: 0 },
                { it.split('.').getOrNull(3)?.toIntOrNull() ?: 0 },
            ))

    // https://learn.microsoft.com/windows/uwp/monetize/get-status-for-an-app-submission
    private fun mapSubmissionStatus(status: String?): ReleaseStatus = when (status) {
        "Published", "Release" -> ReleaseStatus.PUBLISHED
        "None", "PendingCommit", "CommitStarted", "Canceled" -> ReleaseStatus.DRAFT
        "PreProcessing", "Certification", "PendingPublication", "Publishing" -> ReleaseStatus.IN_REVIEW
        "CommitFailed", "PreProcessingFailed", "CertificationFailed", "PublishFailed", "ReleaseFailed" -> ReleaseStatus.HALTED
        else -> ReleaseStatus.UNKNOWN
    }

    @Serializable
    private data class MsTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: String,
    )

    @Serializable
    private data class MsAppResponse(
        val pendingApplicationSubmission: MsSubmissionRef? = null,
        val lastPublishedApplicationSubmission: MsSubmissionRef? = null,
    )

    @Serializable
    private data class MsSubmissionRef(
        val id: String,
        val resourceLocation: String? = null,
    )

    @Serializable
    private data class MsSubmissionResponse(
        val id: String,
        val status: String? = null,
        val statusDetails: MsStatusDetails? = null,
        val applicationPackages: List<MsPackage> = emptyList(),
    )

    @Serializable
    private data class MsStatusDetails(val errors: List<MsStatusError> = emptyList())

    @Serializable
    private data class MsStatusError(val code: String? = null, val details: String? = null)

    @Serializable
    private data class MsPackage(
        val version: String? = null,
        val fileStatus: String? = null,
    )

    @Serializable
    private data class MsFlightsResponse(
        val value: List<MsFlightRef> = emptyList(),
    )

    @Serializable
    private data class MsFlightRef(val flightId: String)

    @Serializable
    private data class MsFlightResponse(
        val pendingFlightSubmission: MsSubmissionRef? = null,
        val lastPublishedFlightSubmission: MsSubmissionRef? = null,
    )

    @Serializable
    private data class MsFlightSubmissionDetail(
        val id: String,
        val status: String? = null,
        val flightPackages: List<MsPackage> = emptyList(),
    )

}
