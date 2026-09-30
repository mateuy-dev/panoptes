package dev.mateuy.panoptes.domain

import dev.mateuy.panoptes.application.ViewVersionsUseCase
import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.StoreAdapter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ViewVersionsUseCaseTest {

    private fun makeAdapter(name: String, versions: List<TrackVersion> = emptyList()): StoreAdapter {
        val mock = mockk<StoreAdapter>()
        io.mockk.every { mock.storeName } returns name
        io.mockk.every { mock.supportedTracks } returns Track.entries
        coEvery { mock.getVersions() } returns versions
        return mock
    }

    @Test
    fun `emits loading state then results for each store`() = runTest {
        val versions = listOf(
            TrackVersion(Track.PRODUCTION, "1.0.0", 100L, ReleaseStatus.PUBLISHED),
        )
        val adapter = makeAdapter("Test Store", versions)
        val useCase = ViewVersionsUseCase(listOf(adapter))

        val results = useCase.execute().toList()

        // Should have loading + result = 2 emissions per store
        assertEquals(2, results.size)
        val loading = results.first { it.storeName == "Test Store" && it.isLoading }
        assertNotNull(loading)
        val result = results.first { it.storeName == "Test Store" && !it.isLoading }
        assertEquals(versions, result.versions)
        assertNull(result.error)
    }

    @Test
    fun `emits error state when adapter throws`() = runTest {
        val mock = mockk<StoreAdapter>()
        io.mockk.every { mock.storeName } returns "Failing Store"
        io.mockk.every { mock.supportedTracks } returns Track.entries
        coEvery { mock.getVersions() } throws RuntimeException("API error")

        val useCase = ViewVersionsUseCase(listOf(mock))
        val results = useCase.execute().toList()

        val errorResult = results.first { !it.isLoading }
        assertEquals("API error", errorResult.error)
    }

    @Test
    fun `handles multiple adapters`() = runTest {
        val adapters = listOf(
            makeAdapter("Store A"),
            makeAdapter("Store B"),
            makeAdapter("Store C"),
        )
        val useCase = ViewVersionsUseCase(adapters)
        val results = useCase.execute().toList()

        // 3 loading + 3 results = 6 emissions
        assertEquals(6, results.size)
    }
}
