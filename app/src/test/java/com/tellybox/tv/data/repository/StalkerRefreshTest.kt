package com.tellybox.tv.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background VOD pass must never hold up the live store, must not run twice for the same
 * playlist, and has to stop when the playlist is deleted.
 */
class StalkerRefreshTest {

    @Test
    fun liveStoreReturnsBeforeTheVodPassFinishes() = runBlocking {
        val vodStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val refresh = StalkerRefresh(scope) { id ->
            vodStarted.complete(Unit)
            release.await()
            assertEquals(7L, id)
        }

        var liveStored = false
        val summary = refresh.refresh(7L) {
            liveStored = true
            "stored"
        }

        assertEquals("stored", summary)
        assertTrue(liveStored)
        withTimeout(1_000) { vodStarted.await() }
        // The pass is still parked here, and the caller already has its answer.
        assertFalse(release.isCompleted)
        release.complete(Unit)
        scope.cancel()
    }

    @Test
    fun theVodPassRunsOncePerPlaylist() = runBlocking {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val refresh = StalkerRefresh(scope) { _ ->
            runs++
            gate.await()
        }

        refresh.refresh(1L) {}
        refresh.refresh(1L) {}
        refresh.refresh(1L) {}
        assertEquals(1, runs)

        // Another playlist gets its own pass.
        refresh.refresh(2L) {}
        assertEquals(2, runs)

        gate.complete(Unit)
        scope.cancel()
    }

    @Test
    fun aFinishedPassCanRunAgain() = runBlocking {
        var runs = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val refresh = StalkerRefresh(scope) { _ -> runs++ }

        refresh.refresh(1L) {}
        refresh.refresh(1L) {}
        assertEquals(2, runs)
        scope.cancel()
    }

    @Test
    fun deleteStopsThePassBeforeTheRowsGo() = runBlocking {
        var cancelled = false
        val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val refresh = StalkerRefresh(scope) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        refresh.refresh(3L) {}
        withTimeout(1_000) { started.await() }

        var rowsDeleted = false
        refresh.delete(3L) {
            // Rows go only after the pass has stopped, never while it is writing.
            assertTrue(cancelled)
            rowsDeleted = true
        }

        assertTrue(cancelled)
        assertTrue(rowsDeleted)
        scope.cancel()
    }
}
