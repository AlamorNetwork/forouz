package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CdnCommandTest {
    @Test fun `cold start duplicate starts invalid commands and cancellation are distinct`() {
        assertEquals(CdnCommand.START, cdnCommand("cdn.start", true, false))
        assertEquals(CdnCommand.KEEP, cdnCommand("cdn.start", true, true))
        assertEquals(CdnCommand.CANCEL, cdnCommand("cdn.cancel", false, true))
        assertEquals(CdnCommand.CANCEL, cdnCommand("cdn.cancel", false, false))
        assertEquals(CdnCommand.STOP, cdnCommand(null, false, false))
        assertEquals(CdnCommand.STOP, cdnCommand("cdn.start", false, false))
    }
}
