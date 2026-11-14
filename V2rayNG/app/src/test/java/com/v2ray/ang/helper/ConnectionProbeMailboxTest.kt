package com.v2ray.ang.helper

import com.v2ray.ang.dto.AwgConnectionTestRequest
import com.v2ray.ang.dto.ConnectionTestResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionProbeMailboxTest {
    @Test fun `only the matching request completes once and a late cancel cannot replace the result`() = runBlocking {
        val mailbox = ConnectionProbeMailbox("current")
        assertFalse(mailbox.receive("old", ConnectionTestResult(12), false))
        assertFalse(mailbox.receive("current", null, false))
        assertTrue(mailbox.receive("current", ConnectionTestResult(47), false))
        assertFalse(mailbox.receive("current", null, true))
        assertEquals(ConnectionProbeReply.Result(ConnectionTestResult(47)), mailbox.await(1_000))
        mailbox.close()
    }

    @Test fun `missing daemon reply is bounded and cancellation is explicit unavailability`() = runBlocking {
        val missing = ConnectionProbeMailbox("missing")
        assertEquals(ConnectionProbeReply.TimedOut, missing.await(40))
        missing.close()
        val cancelled = ConnectionProbeMailbox("cancelled")
        cancelled.receive("cancelled", null, true)
        assertEquals(ConnectionProbeReply.Unavailable, cancelled.await(1_000))
        cancelled.close()
    }

    @Test fun `caller cancellation ends the wait without inventing a measurement`() = runBlocking {
        val mailbox = ConnectionProbeMailbox("one")
        var result: ConnectionProbeReply? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { result = mailbox.await(5_000) }
        job.cancelAndJoin()
        mailbox.close()
        assertNull(result)
        assertFalse(mailbox.receive("one", ConnectionTestResult(42), false))
    }

    @Test fun `recent managed IDs suppress late raw replies and the history remains bounded`() {
        val window = ProbeReplyWindow(2)
        window.track("one"); window.track("two")
        assertTrue("one" in window)
        window.track("three")
        assertFalse("one" in window)
        assertTrue("two" in window)
        assertTrue("three" in window)
    }

    @Test fun `AWG request can only address its own running GUID`() {
        val request = AwgConnectionTestRequest("request", "native-profile")
        assertTrue(request.matches("native-profile", true))
        assertFalse(request.matches("other-profile", true))
        assertFalse(request.matches("native-profile", false))
        assertFalse(request.matches(null, true))
        assertFalse(AwgConnectionTestRequest("", "native-profile").matches("native-profile", true))
    }
}
