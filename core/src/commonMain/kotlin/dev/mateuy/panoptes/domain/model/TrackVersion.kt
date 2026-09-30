package dev.mateuy.panoptes.domain.model

data class TrackVersion(
    val track: Track,
    val versionName: String,
    val versionCode: Long,
    val status: ReleaseStatus,
    /** Extra context shown below the dashboard, e.g. why a pending submission failed. */
    val note: String? = null,
)
