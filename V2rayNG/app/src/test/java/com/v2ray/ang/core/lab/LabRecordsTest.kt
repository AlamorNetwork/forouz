package com.v2ray.ang.core.lab

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabRecordsTest {
    private fun record(id: String = "run", status: LabRunStatus = LabRunStatus.RUNNING) = LabRunRecord(
        id, "group", 1_000, 1_000, status, listOf(LabRow("guid", "a".repeat(64), LabTransport.WS)))

    @Test fun `history round trips and old version one network field gets an explicit unknown default`() {
        val source = listOf(record())
        assertEquals(source, LabRecords.decode(LabRecords.encode(source)))
        val legacy = LabRecords.encode(source).replace(",\"networkKind\":\"unknown\"", "")
        assertEquals("unknown", LabRecords.decode(legacy).first().networkKind)
        assertTrue(LabRecords.decode(null).isEmpty())
    }

    @Test fun `unsupported versions corrupt fields and impossible success do not overwrite history`() {
        val good = LabRecords.encode(listOf(record()))
        for (bad in listOf("", "{}", "null", good.replace("\"version\":1", "\"version\":99"),
            good.replace("RUNNING", "FUTURE"), good.replace("\"bytes\":0", "\"bytes\":-1"),
            good.replace("WAITING", "SUCCESS"), good.replace("\"runs\":[", "\"runs\":null,\"other\":["))) {
            assertThrows(Exception::class.java) { LabRecords.decode(bad) }
        }
        assertEquals(good, LabRecords.encode(LabRecords.decode(good)))
    }

    @Test fun `bounded atomic replacement interrupts only an orphan when a distinct run starts`() {
        var runs = listOf(record())
        runs = LabRecords.replace(runs, record("next"))
        assertEquals(LabRunStatus.INTERRUPTED, runs[1].status)
        assertEquals(LabRowStatus.CANCELLED, runs[1].rows.first().status)
        runs = LabRecords.replace(runs, record("next", LabRunStatus.COMPLETE))
        assertEquals(2, runs.size)
        repeat(25) { runs = LabRecords.replace(runs, record("id-$it", LabRunStatus.COMPLETE)) }
        assertEquals(LabRecords.MAX_RUNS, runs.size)
        assertEquals("id-24", runs.first().id)
    }

    @Test fun `probe revision excludes presentation but detects connection edits and unsupported capabilities`() {
        val p = ProfileItem(configType = EConfigType.VLESS, password = "private-credential", server = "host.invalid", network = "ws")
        assertEquals(LabRecords.revision(p), LabRecords.revision(p.copy(remarks = "renamed", subscriptionId = "moved", addedTime = 8)))
        assertNotEquals(LabRecords.revision(p), LabRecords.revision(p.copy(path = "/changed")))
        assertTrue(LabRecords.supported(p))
        for (type in listOf(EConfigType.AMNEZIAWG, EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN))
            assertFalse(LabRecords.supported(p.copy(configType = type)))
        assertFalse(LabRecords.supported(p.copy(insecure = true)))
        assertFalse(LabRecords.supported(p.copy(browserDialerMode = "browser")))
        val text = LabRecords.encode(listOf(record()))
        assertFalse(text.contains("private-credential")); assertFalse(text.contains("host.invalid"))
    }
}
