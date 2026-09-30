package dev.mateuy.panoptes.adapter

import dev.mateuy.panoptes.adapter.googleplay.GooglePlayAdapter
import dev.mateuy.panoptes.infrastructure.HttpClientFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration test — only runs when PANOPTES_INTEGRATION_TESTS=true.
 * Requires a real project: see [IntegrationProject].
 */
class GooglePlayAdapterIntegrationTest {

    private val enabled = System.getenv("PANOPTES_INTEGRATION_TESTS") == "true"

    @Test
    fun `getVersions returns results from real API`() = runTest {
        if (!enabled) return@runTest

        val adapter = GooglePlayAdapter(HttpClientFactory.create(), IntegrationProject.credentialStore, IntegrationProject.configReader)
        val versions = adapter.getVersions()
        assertTrue(versions.isNotEmpty(), "Expected at least one track version from Google Play")
        println("Google Play versions: $versions")
    }
}
