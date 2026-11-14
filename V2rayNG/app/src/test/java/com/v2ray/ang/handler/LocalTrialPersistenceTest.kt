package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalTrialPersistenceTest {
    @Test fun `publishes only after payload succeeds`() {
        val order = mutableListOf<String>()
        persistLocalTrial({ order.add("payload"); true }, { order.add("index"); true },
            { error("unexpected rollback") }, { error("unexpected cleanup") })
        assertEquals(listOf("payload", "index"), order)
    }

    @Test fun `failed and partial writes restore index and remove new payload`() {
        for (failPayload in listOf(true, false)) {
            var payload: String? = null
            var index = listOf("original")
            assertThrows(ProfileStorageException::class.java) {
                persistLocalTrial(
                    { payload = "trial"; !failPayload },
                    { index = listOf("trial", "original"); false },
                    { index = listOf("original"); true },
                    { payload = null },
                )
            }
            assertNull(payload)
            assertEquals(listOf("original"), index)
        }
    }

    @Test fun `exceptions also compensate both records`() {
        var restored = false
        var removed = false
        assertThrows(IllegalArgumentException::class.java) {
            persistLocalTrial({ true }, { throw IllegalArgumentException("storage failure") },
                { restored = true; true }, { removed = true })
        }
        assertTrue(restored)
        assertTrue(removed)
    }

    @Test fun `rollback failure is reported and does not prevent payload cleanup`() {
        var removed = false
        val error = assertThrows(ProfileStorageException::class.java) {
            persistLocalTrial({ true }, { false }, { false }, { removed = true })
        }
        assertTrue(removed)
        assertEquals(1, error.suppressed.size)
    }
}
