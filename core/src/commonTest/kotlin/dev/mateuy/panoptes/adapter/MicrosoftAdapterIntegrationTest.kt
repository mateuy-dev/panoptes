package dev.mateuy.panoptes.adapter

import dev.mateuy.panoptes.adapter.microsoft.MicrosoftAdapter
import dev.mateuy.panoptes.infrastructure.HttpClientFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class MicrosoftAdapterIntegrationTest {

    private val enabled = System.getenv("PANOPTES_INTEGRATION_TESTS") == "true"

    @Test
    fun `getVersions returns results from real API`() = runTest {
        if (!enabled) return@runTest

        val adapter = MicrosoftAdapter(HttpClientFactory.create(), IntegrationProject.credentialStore, IntegrationProject.configReader)
        val versions = adapter.getVersions()
        assertTrue(versions.isNotEmpty(), "Expected at least one track version from Microsoft Store")
        println("Microsoft Store versions: $versions")
    }
}
