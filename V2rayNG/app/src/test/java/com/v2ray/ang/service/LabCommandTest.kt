package com.v2ray.ang.service

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabCommandTest {
    @Test fun `cancelled job retains ownership until native and network cleanup completes`() = runBlocking {
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val worker = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
        }
        yield()
        worker.cancel()
        cleaning.await()
        try {
            assertFalse(worker.isActive)
            assertFalse(worker.isCompleted)
            assertEquals(LabCommand.KEEP, labCommand("lab.start", true, labHasWork(worker)))
        } finally { release.complete(Unit); worker.join() }
        assertEquals(LabCommand.START, labCommand("lab.start", true, labHasWork(worker)))
        assertFalse(labHasWork(null))
    }
    @Test fun `cold valid start is accepted and missing unknown restart intents stop`() {
        assertEquals(LabCommand.START, labCommand("lab.start", true, false))
        assertEquals(LabCommand.STOP, labCommand("lab.start", false, false))
        assertEquals(LabCommand.STOP, labCommand(null, false, false))
        assertEquals(LabCommand.STOP, labCommand("unknown", true, false))
    }
    @Test fun `duplicate commands keep one running owner and explicit cancel always wins`() {
        assertEquals(LabCommand.KEEP, labCommand("lab.start", true, true))
        assertEquals(LabCommand.KEEP, labCommand(null, false, true))
        assertEquals(LabCommand.CANCEL, labCommand("lab.cancel", false, true))
        assertEquals(LabCommand.CANCEL, labCommand("lab.cancel", false, false))
    }
}
