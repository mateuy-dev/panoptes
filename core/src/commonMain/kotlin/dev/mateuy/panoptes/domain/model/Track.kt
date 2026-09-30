package dev.mateuy.panoptes.domain.model

enum class Track {
    INTERNAL, ALPHA, BETA, PRODUCTION;

    fun next(): Track? = when (this) {
        INTERNAL -> ALPHA
        ALPHA -> BETA
        BETA -> PRODUCTION
        PRODUCTION -> null
    }
}
