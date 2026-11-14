package com.v2ray.ang.core.lab

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class LabHttpProbeTest {
    private class Fixture(val size: Int, val code: Int = 200, val encoding: String? = null, val failure: IOException? = null) : Call.Factory {
        val call = mock<Call>()
        var request: Request? = null
        override fun newCall(request: Request): Call {
            this.request = request
            whenever(call.enqueue(any())).thenAnswer {
                val callback = it.getArgument<Callback>(0)
                if (failure != null) callback.onFailure(call, failure)
                else callback.onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").body(ByteArray(size).toResponseBody()).apply {
                        encoding?.let { header("Content-Encoding", it) }
                    }.build())
                null
            }
            return call
        }
    }

    @Test fun `complete download measures response and throughput separately with exactly ten MB`() = runBlocking {
        val f = Fixture(10_000_000)
        var clock = 0L
        val result = LabHttpProbe(f) { clock.also { clock += 100 } }.download("https://file.invalid/data", "nonce")
        assertEquals(LabIssue.NONE, result.issue)
        assertEquals(10_000_000L, result.bytes)
        assertEquals(100L, result.responseMillis)
        assertEquals(200L, result.elapsedMillis)
        assertEquals("identity", f.request!!.header("Accept-Encoding"))
        assertEquals("nonce", f.request!!.url.queryParameter("lab_nonce"))
        verify(f.call).cancel()
    }

    @Test fun `unexpected status wrong byte length and compression cannot succeed`() = runBlocking {
        for ((fixture, expected) in listOf(Fixture(0, 404) to LabIssue.HTTP,
            Fixture(99) to LabIssue.BODY, Fixture(10_000_001) to LabIssue.BODY,
            Fixture(10_000_000, encoding = "gzip") to LabIssue.BODY)) {
            val result = LabHttpProbe(fixture).download("https://file.invalid/data", "n")
            assertEquals(expected, result.issue); assertEquals(0L, result.bytes)
            verify(fixture.call).cancel()
        }
    }

    @Test fun `invalid URL cannot allocate a call and failures retain a neutral category`() = runBlocking {
        val noCalls = Call.Factory { error("Invalid URL reached network") }
        for (url in listOf("http://file.invalid", "https://user:pass@file.invalid", "https://file.invalid/#fragment", "bad"))
            assertEquals(LabIssue.CONFIG, LabHttpProbe(noCalls).download(url, "n").issue)
        val f = Fixture(0, failure = SocketTimeoutException())
        assertEquals(LabIssue.TIMEOUT, LabHttpProbe(f).download("https://file.invalid/data", "n").issue)
    }

    @Test fun `owner cancellation cancels the actual pending HTTP call`() = runBlocking {
        val enqueued = CompletableDeferred<Unit>()
        val call = mock<Call>()
        whenever(call.enqueue(any())).thenAnswer { enqueued.complete(Unit); null }
        val child = launch { LabHttpProbe(Call.Factory { call }).download("https://file.invalid/data", "n") }
        enqueued.await(); child.cancelAndJoin()
        verify(call).cancel()
    }
}
