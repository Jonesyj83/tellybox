package com.tellybox.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns background imports independently of the screen requesting a refresh. */
internal class StalkerRefresh(
    private val scope: CoroutineScope,
    private val loadVod: suspend (Long) -> Unit,
) {
    private val jobs = HashMap<Long, Job>()
    private val deleting = HashSet<Long>()

    suspend fun <T> refresh(id: Long, storeLive: suspend () -> T): T {
        val summary = storeLive()
        start(id)
        return summary
    }

    private fun start(id: Long): Job? = synchronized(jobs) {
        if (id in deleting) return@synchronized null
        jobs[id]?.takeUnless { it.isCompleted } ?: scope.launch(start = CoroutineStart.LAZY) {
            loadVod(id)
        }.also { job ->
            jobs[id] = job
            job.invokeOnCompletion { synchronized(jobs) { if (jobs[id] === job) jobs.remove(id) } }
            job.start()
        }
    }

    suspend fun vod(id: Long) { start(id)?.join() }

    suspend fun delete(id: Long, deleteRows: suspend () -> Unit) {
        val job = synchronized(jobs) {
            deleting.add(id)
            jobs.remove(id)
        }
        try {
            job?.cancel()
            job?.join()
            deleteRows()
        } finally {
            synchronized(jobs) { deleting.remove(id) }
        }
    }
}
