package dev.mateuy.panoptes.adapter

import dev.mateuy.panoptes.adapter.snap.SnapAdapter
import dev.mateuy.panoptes.infrastructure.HttpClientFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SnapAdapterIntegrationTest {

    private val enabled = System.getenv("PANOPTES_INTEGRATION_TESTS") == "true"

    @Test
    fun `getVersions returns results from real API`() = runTest {
        if (!enabled) return@runTest

        val adapter = SnapAdapter(HttpClientFactory.create(), IntegrationProject.credentialStore, IntegrationProject.configReader)
        val versions = adapter.getVersions()
        assertTrue(versions.isNotEmpty(), "Expected at least one track version from Snap Store")
        println("Snap Store versions: $versions")
    }
}
