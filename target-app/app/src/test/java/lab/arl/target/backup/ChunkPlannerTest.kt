package lab.arl.target.backup

import kotlin.test.assertEquals
import org.junit.Test

class ChunkPlannerTest {
    @Test
    fun plansAlignedChunks() {
        val plans = ChunkPlanner.plan(sizeBytes = 3_000_000, chunkSize = 1_000_000)
        assertEquals(3, plans.size)
        assertEquals(0L, plans[0].offset)
        assertEquals(1_000_000L, plans[1].offset)
        assertEquals(1_000_000, plans[2].size)
    }

    @Test
    fun resumesFromOffset() {
        val plans = ChunkPlanner.plan(sizeBytes = 2500, chunkSize = 1000, alreadyUploaded = 1000)
        assertEquals(2, plans.size)
        assertEquals(1000L, plans[0].offset)
        assertEquals(500, plans[1].size)
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0))
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray())
        )
    }
}
