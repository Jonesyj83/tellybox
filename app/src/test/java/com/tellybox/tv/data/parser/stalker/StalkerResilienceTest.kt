package com.tellybox.tv.data.parser.stalker

import com.tellybox.tv.data.repository.StalkerImport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ways real portals misbehave: sessions that expire mid-walk, rate limits, categories that
 * answer with nothing, duplicate ids and portals that send you somewhere else. None of these may
 * hang the import, lose rows or take the app down.
 */
class StalkerResilienceTest {

    private val servers = ArrayList<FakePortal>()

    @After
    fun stopServers() = servers.forEach { it.close() }

    private fun empty() = """{"js":{"total_items":"0","max_page_items":14,"data":[]}}"""

    private fun page(prefix: String, count: Int) = """{"js":{"total_items":"$count","max_page_items":14,"data":[
        ${(1..14).joinToString(",") { """{"id":"$prefix$it","name":"$prefix $it","is_movie":true,"category_id":"5"}""" }}
    ]}}"""

    /** A portal that kills the session after [dropAfter] requests until the client says hello again. */
    @Test
    fun aSessionThatExpiresMidWalkIsRecovered() = runBlocking {
        val seen = AtomicInteger()
        var authorised = true
        val p = FakePortal({ q ->
            when {
                q["action"] == "handshake" -> {
                    authorised = true
                    seen.incrementAndGet()
                    200 to """{"js":{"token":"s"}}"""
                }
                q["action"] == "get_profile" -> 200 to """{"js":{}}"""
                !authorised -> 200 to """{"error":"Authorization failed"}"""
                q["action"] == "get_categories" ->
                    200 to """{"js":[{"id":"5","title":"Films","censored":0}]}"""
                else -> {
                    // Every few pages the portal forgets who you are.
                    if (seen.incrementAndGet() % 3 == 0) {
                        authorised = false
                        200 to """{"error":"Authorization failed"}"""
                    } else {
                        200 to page("m", 200)
                    }
                }
            }
        }).also { servers += it }

        val client = StalkerClient("http://127.0.0.1:${p.port}/portal.php", "00:1A:79:AB:CD:EF", OkHttpClient())
        val result = withTimeout(30_000) {
            StalkerImport.loadVod(1, false, client.vodCategories(), fetch = client::vodItemsResult)
        }
        // It may not have everything, but it must not come back empty or stay stuck.
        assertTrue("no rows survived an expiring session", result.channels.isNotEmpty())
        assertTrue("handshake was never repeated", p.requests.count { it["action"] == "handshake" } > 1)
    }

    /** A busy portal earns a retry, not a lost category. */
    @Test
    fun aRateLimitedCategoryIsRetried() = runBlocking {
        val busy = AtomicInteger()
        val p = FakePortal({ q ->
            when {
                q["action"] == "handshake" -> 200 to """{"js":{"token":"s"}}"""
                q["action"] == "get_profile" -> 200 to """{"js":{}}"""
                q["action"] == "get_categories" ->
                    200 to """{"js":[{"id":"5","title":"Films","censored":0},{"id":"9","title":"Busy","censored":0}]}"""
                // Busy for the first two goes, then it answers properly.
                q["category"] == "9" && busy.incrementAndGet() <= 2 -> 429 to """{"error":"Too many requests"}"""
                q["p"] == "1" -> 200 to page("g$q", 14)
                else -> 200 to empty()
            }
        }).also { servers += it }

        val client = StalkerClient("http://127.0.0.1:${p.port}/portal.php", "00:1A:79:AB:CD:EF", OkHttpClient())
        val result = withTimeout(30_000) {
            StalkerImport.loadVod(1, false, client.vodCategories(), fetch = client::vodItemsResult)
        }
        assertTrue("the healthy category was lost", result.channels.any { it.groupName == "Films" })
        assertEquals("the retried category should be there", LoadCompletion.FULL, result.completion.categories["9"])
        assertTrue(result.channels.any { it.groupName == "Busy" })
        assertNull("a portal that recovers is not an error", result.vodError)
    }

    /** Total says thousands, page one says nothing. That is not an invitation to delete rows. */
    @Test
    fun anEmptyFirstPageIsNotTreatedAsAnEmptyCategory() = runBlocking {
        val p = FakePortal({ q ->
            when {
                q["action"] == "handshake" -> 200 to """{"js":{"token":"s"}}"""
                q["action"] == "get_profile" -> 200 to """{"js":{}}"""
                q["action"] == "get_categories" -> 200 to """{"js":[{"id":"5","title":"Films","censored":0}]}"""
                else -> 200 to """{"js":{"total_items":"3759","max_page_items":14,"data":[]}}"""
            }
        }).also { servers += it }

        val client = StalkerClient("http://127.0.0.1:${p.port}/portal.php", "00:1A:79:AB:CD:EF", OkHttpClient())
        val result = withTimeout(20_000) {
            StalkerImport.loadVod(1, false, client.vodCategories(), fetch = client::vodItemsResult)
        }
        assertEquals(0, result.channels.size)
        // PARTIAL keeps whatever is already stored instead of pruning it away.
        assertEquals(LoadCompletion.PARTIAL, result.completion.categories["5"])
    }

    /** Portals do send the same id twice, and the second copy must not overwrite the first. */
    @Test
    fun duplicateIdsBecomeSeparateRows() = runBlocking {
        val p = FakePortal({ q ->
            when {
                q["action"] == "handshake" -> 200 to """{"js":{"token":"s"}}"""
                q["action"] == "get_profile" -> 200 to """{"js":{}}"""
                q["action"] == "get_categories" -> 200 to """{"js":[{"id":"5","title":"Films","censored":0}]}"""
                q["p"] == "1" -> 200 to """{"js":{"total_items":"2","data":[
                    {"id":"900","name":"Same","is_movie":true},
                    {"id":"900","name":"Same again","is_movie":true}]}}"""
                else -> 200 to empty()
            }
        }).also { servers += it }

        val client = StalkerClient("http://127.0.0.1:${p.port}/portal.php", "00:1A:79:AB:CD:EF", OkHttpClient())
        val result = withTimeout(20_000) {
            StalkerImport.loadVod(1, false, client.vodCategories(), fetch = client::vodItemsResult)
        }
        // Both titles survive; the store is what gives the second copy its own key.
        assertEquals(2, result.channels.size)
        assertEquals(listOf("Same", "Same again"), result.channels.map { it.channel.name })
    }

    /** Some portals bounce you to another path, and the session has to survive the hop. */
    @Test
    fun aRedirectIsFollowed() = runBlocking {
        val p = FakePortal { q ->
            when {
                q["action"] == "handshake" -> 200 to """{"js":{"token":"s"}}"""
                q["action"] == "get_profile" -> 200 to """{"js":{}}"""
                q["action"] == "get_categories" ->
                    200 to """{"js":[{"id":"5","title":"Films","censored":0}]}"""
                q["p"] == "1" -> 200 to page("r", 14)
                else -> 200 to empty()
            }
        }.also { servers += it }
        // The first call to the portal is answered with a move.
        p.headers = { q ->
            if (q["action"] == "handshake" && !moved.getAndSet(true)) {
                mapOf("Location" to "/stalker_portal/c/portal.php")
            } else emptyMap()
        }
        moved.set(false)

        val client = StalkerClient("http://127.0.0.1:${p.port}/portal.php", "00:1A:79:AB:CD:EF", OkHttpClient())
        val result = withTimeout(20_000) {
            StalkerImport.loadVod(1, false, client.vodCategories(), fetch = client::vodItemsResult)
        }
        assertTrue(result.channels.isNotEmpty())
    }

    private val moved = java.util.concurrent.atomic.AtomicBoolean(true)
}
