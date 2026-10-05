package dev.mateuy.panoptes.adapter

import dev.mateuy.panoptes.adapter.dmg.DmgAdapter
import dev.mateuy.panoptes.infrastructure.HttpClientFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class DmgAdapterIntegrationTest {

    private val enabled = System.getenv("PANOPTES_INTEGRATION_TESTS") == "true"

    @Test
    fun `getVersions returns results from real API`() = runTest {
        if (!enabled) return@runTest

        val adapter = DmgAdapter(HttpClientFactory.create(), IntegrationProject.configReader)
        val versions = adapter.getVersions()
        assertTrue(versions.isNotEmpty(), "Expected at least one track version from macOS DMG")
        println("macOS DMG versions: $versions")
    }
}
