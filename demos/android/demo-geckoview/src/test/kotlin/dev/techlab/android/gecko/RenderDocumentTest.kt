package dev.techlab.android.gecko

import dev.techlab.android.gecko.engine.RenderDocument
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RenderDocumentTest {
    private var time = 100L
    private var timeout: Runnable? = null
    private val doc = RenderDocument({ time }, { timeout = it }, {})
    private val commands = mutableListOf<JSONObject>()
    private fun input(token: String) = JSONObject().put("token", token)

    @Test fun coldAndWarmingRequestsUseTheirOwnOrigins() {
        time = 150
        doc.navigating()
        assertEquals(150L, doc.renderStart(90))
        assertEquals(125L, doc.renderStart(125))
        time = 200
        doc.ready(commands::add)
        assertEquals(100L, doc.preparationMs)
        assertEquals(230L, doc.renderStart(230))
        assertNull(timeout)
    }

    @Test fun duplicateReadyOrInputDoesNotSendTwiceAndClearIgnoresLateResults() {
        var readyCalls = 0
        val delivered = mutableListOf<String>()
        doc.onState = {
            readyCalls++
            doc.setQuestions(input("one"), false)
        }
        doc.receive = { delivered += it.getString("token") }
        doc.navigating()
        doc.ready(commands::add)
        doc.ready { error("duplicate ready") }
        doc.setQuestions(input("one"), false)
        doc.setQuestions(input("two"), false)
        doc.message(input("one"))
        doc.message(input("two"))
        doc.detach()
        doc.message(input("two"))
        assertEquals(1, readyCalls)
        assertEquals(listOf("setQuestions", "setQuestions", "clear"), commands.map { it.getString("type") })
        assertEquals("two", commands.last().getString("token"))
        assertEquals(listOf("two"), delivered)
        assertTrue(doc.isReady)
    }

    @Test fun incompatibleDocumentRefetchesOnceAndIgnoresEarlierGeneration() {
        doc.navigating()
        val first = doc.url
        var reloads = 0
        val navigate = {
            reloads++
            time = 165
            doc.navigating()
        }
        doc.checkCompatibility(first, navigate)
        assertNotEquals(first, doc.url)
        time = 250
        assertEquals(100L, doc.renderStart(95))
        doc.checkCompatibility(first, navigate)
        doc.checkCompatibility(doc.url, navigate)
        doc.ready(commands::add)
        assertEquals(1, reloads)
        assertTrue(doc.incompatible)
        assertFalse(doc.isReady)
        assertFalse(doc.reusable)
        assertNull(timeout)
    }

    @Test fun failureAfterReadyAndLateCallbacksAfterCloseCannotResurrectBridge() {
        doc.navigating()
        doc.ready(commands::add)
        doc.setQuestions(input("one"), false)
        doc.receive = { fail("stale callback") }
        doc.fail("lost")
        doc.message(input("one"))
        assertNull(doc.preparationMs)
        assertFalse(doc.isReady)
        assertEquals("lost", doc.failure)
        doc.close()
        doc.ready(commands::add)
        doc.fail("late")
        assertFalse(doc.reusable)
        assertNull(timeout)
    }

    @Test fun handshakeTimeoutFiresBeforeInput() {
        timeout!!.run()
        doc.ready(commands::add)
        assertFalse(doc.reusable)
        assertNotNull(doc.failure)
        assertNull(timeout)
    }
}
