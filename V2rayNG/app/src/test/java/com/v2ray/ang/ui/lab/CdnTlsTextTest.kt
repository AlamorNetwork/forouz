package com.v2ray.ang.ui.lab

import com.v2ray.ang.core.lab.LabTlsFailure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CdnTlsTextTest {
    @Test fun `each diagnostic has a distinct actionable resource`() {
        val resources = LabTlsFailure.entries.map(::cdnTlsText)
        assertEquals(LabTlsFailure.entries.size, resources.distinct().size)
        assertTrue(resources.all { it != 0 })
    }
}
