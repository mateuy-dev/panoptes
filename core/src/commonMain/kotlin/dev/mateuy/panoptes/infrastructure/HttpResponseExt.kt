package dev.mateuy.panoptes.infrastructure

import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.http.*

/** Deserializes the body, or fails with the status and a trimmed body so the dashboard can show why. */
suspend inline fun <reified T> HttpResponse.bodyOrError(context: String): T {
    if (!status.isSuccess()) {
        val text = bodyAsText().replace(Regex("\\s+"), " ").trim()
        val detail = if (text.startsWith("<")) "(HTML response)" else text.take(200)
        error("$context failed: $status $detail")
    }
    return body()
}
