package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals

class ColdStartSyncReplayTest {
    @Test fun `late registration in foreground replays once`() {
        val replay = ColdStartSyncReplay()
        assertTrue(replay.claimReplay(true))
        assertFalse(replay.claimReplay(true))
    }

    @Test fun `normal initial native start prevents duplicate scan`() {
        val replay = ColdStartSyncReplay()
        replay.onNativeStart()
        assertFalse(replay.claimReplay(true))
    }

    @Test fun `native start arriving before posted recovery prevents duplicate scan`() {
        val replay = ColdStartSyncReplay()
        assertFalse(replay.claimReplay(false))
        replay.onNativeStart()
        assertFalse(replay.claimReplay(true))
    }

    @Test fun `registration in background does not consume the replay opportunity`() {
        val replay = ColdStartSyncReplay()
        assertFalse(replay.claimReplay(false))
        assertTrue(replay.claimReplay(true))
    }

    @Test fun `a different listener has an independent lifetime`() {
        val previous = ColdStartSyncReplay()
        previous.onNativeStart()
        assertTrue(ColdStartSyncReplay().claimReplay(true))
        assertFalse(previous.claimReplay(true))
    }

    @Test fun `concurrent registration completions can claim only one scan`() {
        val replay = ColdStartSyncReplay()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(32) { Callable { replay.claimReplay(true) } })
            assertEquals(1, results.count { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }
}
