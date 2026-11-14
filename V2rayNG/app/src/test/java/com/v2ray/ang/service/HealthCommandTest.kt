package com.v2ray.ang.service

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthCommandTest {
    @Test fun `cold start requires explicit valid request and restart intents never recreate work`() {
        assertEquals(HealthCommand.START, healthCommand("health.start", true, false))
        assertEquals(HealthCommand.REJECT, healthCommand(null, true, false))
        assertEquals(HealthCommand.REJECT, healthCommand("unknown", true, false))
        assertEquals(HealthCommand.REJECT, healthCommand("health.start", false, false))
    }
    @Test fun `duplicate start remains one owner and stop takes precedence`() {
        assertEquals(HealthCommand.KEEP, healthCommand("health.start", true, true))
        assertEquals(HealthCommand.KEEP, healthCommand(null, false, true))
        assertEquals(HealthCommand.STOP, healthCommand("health.stop", false, true))
        assertEquals(HealthCommand.STOP, healthCommand("health.stop", false, false))
    }
    @Test fun `cancelled native cleanup still owns run until completion`() = runBlocking {
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        }
        yield(); job.cancel(); cleaning.await()
        try { assertEquals(HealthCommand.KEEP, healthCommand("health.start", true, labHasWork(job))) }
        finally { release.complete(Unit); job.join() }
        assertEquals(HealthCommand.START, healthCommand("health.start", true, labHasWork(job)))
    }
}
