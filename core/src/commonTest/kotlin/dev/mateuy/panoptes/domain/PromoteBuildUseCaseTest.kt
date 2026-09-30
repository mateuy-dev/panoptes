package dev.mateuy.panoptes.domain

import dev.mateuy.panoptes.application.PromoteBuildUseCase
import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.StoreAdapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromoteBuildUseCaseTest {

    private fun makeAdapter(
        name: String,
        tracks: List<Track> = Track.entries,
    ): StoreAdapter {
        val mock = mockk<StoreAdapter>()
        every { mock.storeName } returns name
        every { mock.supportedTracks } returns tracks
        every { mock.promotionTarget(any()) } answers {
            val from = firstArg<Track>()
            if (from in tracks) tracks.filter { it > from }.minOrNull() else null
        }
        coEvery { mock.promote(any(), any()) } returns Unit
        coEvery { mock.getVersions() } returns emptyList()
        return mock
    }

    @Test
    fun `promotes to next track successfully`() = runTest {
        val adapter = makeAdapter("Test Store")
        val useCase = PromoteBuildUseCase(listOf(adapter))

        val result = useCase.execute("Test Store", Track.BETA)

        assertTrue(result.isSuccess)
        coVerify { adapter.promote(Track.BETA, Track.PRODUCTION) }
    }

    @Test
    fun `fails for unknown store`() = runTest {
        val adapter = makeAdapter("Test Store")
        val useCase = PromoteBuildUseCase(listOf(adapter))

        val result = useCase.execute("Unknown Store", Track.BETA)

        assertFalse(result.isSuccess)
    }

    @Test
    fun `fails when promoting from PRODUCTION`() = runTest {
        val adapter = makeAdapter("Test Store")
        val useCase = PromoteBuildUseCase(listOf(adapter))

        val result = useCase.execute("Test Store", Track.PRODUCTION)

        assertFalse(result.isSuccess)
    }

    @Test
    fun `promotes to next supported track, skipping unsupported ones`() = runTest {
        val adapter = makeAdapter("App Store", tracks = listOf(Track.INTERNAL, Track.PRODUCTION))
        val useCase = PromoteBuildUseCase(listOf(adapter))

        val result = useCase.execute("App Store", Track.INTERNAL)

        assertTrue(result.isSuccess)
        coVerify { adapter.promote(Track.INTERNAL, Track.PRODUCTION) }
    }

    @Test
    fun `fails when store can't promote from track`() = runTest {
        val adapter = makeAdapter("No Promote Store", tracks = listOf(Track.BETA, Track.PRODUCTION))
        every { adapter.promotionTarget(any()) } returns null
        val useCase = PromoteBuildUseCase(listOf(adapter))

        val result = useCase.execute("No Promote Store", Track.BETA)

        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { adapter.promote(any(), any()) }
    }

    @Test
    fun `wraps adapter exception in failure`() = runTest {
        val mock = mockk<StoreAdapter>()
        every { mock.storeName } returns "Flaky Store"
        every { mock.supportedTracks } returns Track.entries
        every { mock.promotionTarget(Track.ALPHA) } returns Track.BETA
        coEvery { mock.promote(any(), any()) } throws RuntimeException("Network error")

        val useCase = PromoteBuildUseCase(listOf(mock))
        val result = useCase.execute("Flaky Store", Track.ALPHA)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }
}
