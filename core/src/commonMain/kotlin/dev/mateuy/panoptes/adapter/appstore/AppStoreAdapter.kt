package dev.mateuy.panoptes.adapter.appstore

import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.domain.port.StoreAdapter
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.bodyOrError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class AppStoreAdapter(
    private val httpClient: HttpClient,
    private val credentialStore: CredentialStore,
    private val configReader: ConfigReader,
) : StoreAdapter {

    override val storeName = "App Store"
    override val supportedTracks = listOf(Track.INTERNAL, Track.PRODUCTION)

    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private val bundleId: String get() = configReader.appId("appstore")

    private fun getJwt(): String {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAt) return cachedToken!!

        val issuerId = credentialStore.get("appstore", "issuerId")
            ?: error("App Store issuer ID not configured")
        val keyId = credentialStore.get("appstore", "keyId")
            ?: error("App Store key ID not configured")
        val privateKey = credentialStore.get("appstore", "privateKey")
            ?: error("App Store private key not configured")

        cachedToken = AppStoreJwtHelper.createJwt(issuerId, keyId, privateKey)
        tokenExpiresAt = now + 18 * 60 * 1000L // refresh 2 min before 20-min expiry
        return cachedToken!!
    }

    private val api = "https://api.appstoreconnect.apple.com/v1"

    private suspend fun findAppId(jwt: String): String {
        val bundle = bundleId.ifEmpty { error("Bundle ID not configured — set it in [3] Settings") }
        return httpClient.get("$api/apps") {
            bearerAuth(jwt)
            parameter("filter[bundleId]", bundle)
        }.bodyOrError<AppsResponse>("App Store app lookup").data.firstOrNull()?.id
            ?: error("No App Store Connect app with bundle ID $bundle")
    }

    /** Latest processed, non-expired TestFlight build, with its marketing version. */
    private suspend fun latestBuild(jwt: String, appId: String): LatestBuild? {
        val response: BuildsResponse = httpClient.get("$api/builds") {
            bearerAuth(jwt)
            parameter("filter[app]", appId)
            parameter("filter[processingState]", "VALID")
            parameter("filter[expired]", "false")
            parameter("filter[preReleaseVersion.platform]", PLATFORM)
            parameter("sort", "-uploadedDate")
            parameter("limit", "1")
            parameter("include", "preReleaseVersion")
        }.bodyOrError("App Store builds request")

        val build = response.data.firstOrNull() ?: return null
        val preReleaseId = build.relationships?.preReleaseVersion?.data?.id
        val versionString = response.included.find { it.type == "preReleaseVersions" && it.id == preReleaseId }
            ?.attributes?.version
        return LatestBuild(build.id, build.attributes.version, versionString)
    }

    override suspend fun getVersions(): List<TrackVersion> {
        val jwt = getJwt()
        val appId = findAppId(jwt)
        val versions = mutableListOf<TrackVersion>()

        // Internal: latest TestFlight build
        latestBuild(jwt, appId)?.let { build ->
            versions += TrackVersion(
                track = Track.INTERNAL,
                versionName = build.versionString?.let { "$it (${build.buildNumber})" } ?: build.buildNumber,
                versionCode = build.buildNumber.toLongOrNull() ?: 0L,
                status = ReleaseStatus.PUBLISHED,
            )
        }

        // Production: most recent App Store version
        val appVersionResponse: AppStoreVersionsResponse = httpClient.get("$api/apps/$appId/appStoreVersions") {
            bearerAuth(jwt)
            parameter("filter[platform]", PLATFORM)
            parameter("limit", "1")
        }.bodyOrError("App Store versions request")

        appVersionResponse.data.firstOrNull()?.let { version ->
            versions += TrackVersion(
                track = Track.PRODUCTION,
                versionName = version.attributes.versionString,
                versionCode = 0L,
                status = mapAppStoreState(version.attributes.appStoreState),
            )
        }

        return versions
    }

    override suspend fun promote(fromTrack: Track, toTrack: Track) {
        require(fromTrack == Track.INTERNAL && toTrack == Track.PRODUCTION) {
            "App Store only supports internal→production promotion"
        }
        val jwt = getJwt()
        val appId = findAppId(jwt)
        val build = latestBuild(jwt, appId) ?: error("No valid TestFlight build found")
        val versionString = build.versionString ?: error("Could not determine version of build ${build.buildNumber}")

        // 1. Reuse the editable App Store version, or create one for the build's version
        val editable = httpClient.get("$api/apps/$appId/appStoreVersions") {
            bearerAuth(jwt)
            parameter("filter[platform]", PLATFORM)
            parameter("filter[appStoreState]", EDITABLE_STATES.joinToString(","))
            parameter("limit", "1")
        }.bodyOrError<AppStoreVersionsResponse>("App Store editable version lookup").data.firstOrNull()

        val versionId = if (editable != null) {
            if (editable.attributes.versionString != versionString) {
                httpClient.patch("$api/appStoreVersions/${editable.id}") {
                    bearerAuth(jwt)
                    contentType(ContentType.Application.Json)
                    setBody("""{"data":{"type":"appStoreVersions","id":"${editable.id}","attributes":{"versionString":"$versionString"}}}""")
                }.bodyOrError<String>("App Store version update")
            }
            editable.id
        } else {
            httpClient.post("$api/appStoreVersions") {
                bearerAuth(jwt)
                contentType(ContentType.Application.Json)
                setBody("""{"data":{"type":"appStoreVersions","attributes":{"platform":"$PLATFORM","versionString":"$versionString"},"relationships":{"app":{"data":{"type":"apps","id":"$appId"}}}}}""")
            }.bodyOrError<SingleResource>("App Store version creation").data.id
        }

        // 2. Attach the build
        httpClient.patch("$api/appStoreVersions/$versionId/relationships/build") {
            bearerAuth(jwt)
            contentType(ContentType.Application.Json)
            setBody("""{"data":{"type":"builds","id":"${build.id}"}}""")
        }.bodyOrError<String>("Attaching build ${build.buildNumber} to version $versionString")

        // 3. Fill empty "What's New" (required for updates) from the previous version, or a default
        fillWhatsNew(jwt, appId, versionId)

        // 4. Submit for review: open (or reuse) a review submission, add the version, submit it
        val submissionId = httpClient.get("$api/reviewSubmissions") {
            bearerAuth(jwt)
            parameter("filter[app]", appId)
            parameter("filter[platform]", PLATFORM)
            parameter("filter[state]", "READY_FOR_REVIEW")
            parameter("limit", "1")
        }.bodyOrError<ResourceList>("App Store review submission lookup").data.firstOrNull()?.id
            ?: httpClient.post("$api/reviewSubmissions") {
                bearerAuth(jwt)
                contentType(ContentType.Application.Json)
                setBody("""{"data":{"type":"reviewSubmissions","attributes":{"platform":"$PLATFORM"},"relationships":{"app":{"data":{"type":"apps","id":"$appId"}}}}}""")
            }.bodyOrError<SingleResource>("App Store review submission creation").data.id

        httpClient.post("$api/reviewSubmissionItems") {
            bearerAuth(jwt)
            contentType(ContentType.Application.Json)
            setBody("""{"data":{"type":"reviewSubmissionItems","relationships":{"reviewSubmission":{"data":{"type":"reviewSubmissions","id":"$submissionId"}},"appStoreVersion":{"data":{"type":"appStoreVersions","id":"$versionId"}}}}}""")
        }.bodyOrError<String>("Adding version $versionString to review submission")

        httpClient.patch("$api/reviewSubmissions/$submissionId") {
            bearerAuth(jwt)
            contentType(ContentType.Application.Json)
            setBody("""{"data":{"type":"reviewSubmissions","id":"$submissionId","attributes":{"submitted":true}}}""")
        }.bodyOrError<String>("Submitting version $versionString for review")
    }

    private suspend fun localizations(jwt: String, versionId: String): List<LocalizationData> =
        httpClient.get("$api/appStoreVersions/$versionId/appStoreVersionLocalizations") {
            bearerAuth(jwt)
            parameter("limit", "200")
        }.bodyOrError<LocalizationsResponse>("App Store version localizations lookup").data

    /**
     * Sets `whatsNew` on every localization of [versionId] that lacks it, copying the previous version's
     * text for the same locale (or its primary text) and falling back to [DEFAULT_WHATS_NEW].
     * Skipped for an app's first version, where Apple does not allow `whatsNew`.
     */
    private suspend fun fillWhatsNew(jwt: String, appId: String, versionId: String) {
        val empty = localizations(jwt, versionId).filter { it.attributes.whatsNew.isNullOrBlank() }
        if (empty.isEmpty()) return

        val previousId = httpClient.get("$api/apps/$appId/appStoreVersions") {
            bearerAuth(jwt)
            parameter("filter[platform]", PLATFORM)
            parameter("limit", "10")
        }.bodyOrError<AppStoreVersionsResponse>("App Store previous version lookup").data
            .firstOrNull { it.id != versionId }?.id
            ?: return

        val previousNotes = localizations(jwt, previousId)
            .mapNotNull { loc -> loc.attributes.whatsNew?.takeIf { it.isNotBlank() }?.let { loc.attributes.locale to it } }
            .toMap()
        val anyPreviousNote = previousNotes.values.firstOrNull()

        for (loc in empty) {
            val text = previousNotes[loc.attributes.locale] ?: anyPreviousNote ?: DEFAULT_WHATS_NEW
            val body = buildJsonObject {
                putJsonObject("data") {
                    put("type", "appStoreVersionLocalizations")
                    put("id", loc.id)
                    putJsonObject("attributes") { put("whatsNew", text) }
                }
            }
            httpClient.patch("$api/appStoreVersionLocalizations/${loc.id}") {
                bearerAuth(jwt)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.bodyOrError<String>("Setting What's New for ${loc.attributes.locale}")
        }
    }

    private fun mapAppStoreState(state: String?): ReleaseStatus = when (state) {
        "READY_FOR_SALE", "READY_FOR_DISTRIBUTION", "REPLACED_WITH_NEW_VERSION" -> ReleaseStatus.PUBLISHED
        "WAITING_FOR_REVIEW", "IN_REVIEW", "READY_FOR_REVIEW", "ACCEPTED", "PROCESSING_FOR_APP_STORE",
        "PROCESSING_FOR_DISTRIBUTION", "PENDING_DEVELOPER_RELEASE", "PENDING_APPLE_RELEASE",
        "WAITING_FOR_EXPORT_COMPLIANCE", "PENDING_CONTRACT" -> ReleaseStatus.IN_REVIEW
        "PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED" -> ReleaseStatus.DRAFT
        "REJECTED", "METADATA_REJECTED", "INVALID_BINARY", "REMOVED_FROM_SALE",
        "DEVELOPER_REMOVED_FROM_SALE", "NOT_APPLICABLE" -> ReleaseStatus.HALTED
        else -> ReleaseStatus.UNKNOWN
    }

    private data class LatestBuild(val id: String, val buildNumber: String, val versionString: String?)

    private companion object {
        const val PLATFORM = "IOS"
        const val DEFAULT_WHATS_NEW = "Bug fixes and improvements."
        val EDITABLE_STATES = listOf(
            "PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY",
        )
    }

    @Serializable
    private data class AppsResponse(val data: List<ResourceRef> = emptyList())

    @Serializable
    private data class ResourceRef(val id: String)

    @Serializable
    private data class ResourceList(val data: List<ResourceRef> = emptyList())

    @Serializable
    private data class SingleResource(val data: ResourceRef)

    @Serializable
    private data class BuildsResponse(
        val data: List<BuildData> = emptyList(),
        val included: List<IncludedResource> = emptyList(),
    )

    @Serializable
    private data class BuildData(
        val id: String,
        val attributes: BuildAttributes,
        val relationships: BuildRelationships? = null,
    )

    @Serializable
    private data class BuildAttributes(val version: String)

    @Serializable
    private data class BuildRelationships(val preReleaseVersion: Relationship? = null)

    @Serializable
    private data class Relationship(val data: IncludedRef? = null)

    @Serializable
    private data class IncludedRef(val type: String, val id: String)

    @Serializable
    private data class IncludedResource(
        val type: String,
        val id: String,
        val attributes: IncludedAttributes? = null,
    )

    @Serializable
    private data class IncludedAttributes(val version: String? = null)

    @Serializable
    private data class LocalizationsResponse(val data: List<LocalizationData> = emptyList())

    @Serializable
    private data class LocalizationData(val id: String, val attributes: LocalizationAttributes)

    @Serializable
    private data class LocalizationAttributes(val locale: String, val whatsNew: String? = null)

    @Serializable
    private data class AppStoreVersionsResponse(val data: List<AppStoreVersionData> = emptyList())

    @Serializable
    private data class AppStoreVersionData(
        val id: String,
        val attributes: AppStoreVersionAttributes,
    )

    @Serializable
    private data class AppStoreVersionAttributes(
        val versionString: String,
        val appStoreState: String? = null,
    )
}
