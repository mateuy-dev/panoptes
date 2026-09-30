package dev.mateuy.panoptes.infrastructure

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object HttpClientFactory {
    fun create(enableLogging: Boolean = false): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        if (enableLogging) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }
        engine {
            requestTimeout = 30_000
        }
    }
}
