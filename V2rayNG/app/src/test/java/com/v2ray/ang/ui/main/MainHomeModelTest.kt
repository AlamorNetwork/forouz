package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.ConnectionSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainHomeModelTest {
    @Test fun `initial and deleted selection have no connection target`() {
        assertNull(MainUiState().homeProfile)
        val profile = homeProfile("one", ProfileItem.create(EConfigType.VLESS), 12)
        val deleted = MainUiState(selectedGuid = null, homeProfile = profile).withHomeProfile(null, null)
        assertNull(deleted.homeProfile)
    }

    @Test fun `home summary includes identity and measured latency but omits credentials`() {
        val profile = ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            remarks = "My tunnel"
            secretKey = "private-secret-value"
        }
        val summary = homeProfile("stable-guid", profile, 42)
        assertEquals("stable-guid", summary.guid)
        assertEquals("AmneziaWG", summary.protocol)
        assertEquals(42L, summary.delayMillis)
        assertTrue(summary.isAmnezia)
        assertFalse(summary.toString().contains("private-secret-value"))
        assertEquals("My tunnel", summary.name)
    }

    @Test fun `unmeasured failed and empty latency are not displayed as measurements`() {
        for (delay in listOf(null, -1L, 0L)) {
            val summary = homeProfile("one", ProfileItem.create(EConfigType.VLESS), delay)
            assertNull(summary.delayMillis)
            assertFalse(summary.isAmnezia)
        }
    }

    @Test fun `late profile load cannot replace a more recent selection`() {
        val current = MainUiState(selectedGuid = "new")
        val stale = homeProfile("old", ProfileItem.create(EConfigType.VLESS), 12)
        assertSame(current, current.withHomeProfile("old", stale))
        val selected = homeProfile("new", ProfileItem.create(EConfigType.VLESS), 14)
        assertEquals(selected, current.withHomeProfile("new", selected).homeProfile)
        assertNull(current.withHomeProfile("new", null).homeProfile)
    }

    @Test fun `clipboard action exports native AWG only for the selected AWG profile`() {
        assertEquals(MainAction.CopyAmneziaNative("awg-guid"), clipboardAction("awg-guid", EConfigType.AMNEZIAWG))
        assertEquals(MainAction.ShareClipboard("vless-guid"), clipboardAction("vless-guid", EConfigType.VLESS))
    }

    @Test fun `both engines test the actual active connection independently of list selection`() {
        for (type in listOf(EConfigType.AMNEZIAWG, EConfigType.VLESS)) {
            val active = MainUiState(selectedGuid = "active", isRunning = true,
                homeProfile = homeProfile("active", ProfileItem.create(type), null),
                connection = MainConnectionState(ConnectionSnapshot("session", "active", 1_000, 1_000)))
            assertTrue(active.canTestConnection())
            assertFalse(active.copy(isTesting = true).canTestConnection())
            assertEquals(MainAction.CancelTesting, active.copy(isTesting = true).connectionTestAction())
            assertEquals(MainAction.TestCurrentServer, active.connectionTestAction())
            assertTrue(active.copy(selectedGuid = "different").canTestConnection())
            assertEquals("active", active.copy(selectedGuid = "different").activeTestProfileGuid())
            assertTrue(active.copy(selectedGuid = null).canTestConnection())
            assertFalse(active.copy(isRunning = false).canTestConnection())
            assertFalse(active.copy(connection = MainConnectionState()).canTestConnection())
            assertFalse(active.copy(connection = MainConnectionState(active.connection.snapshot!!.copy(running = false))).canTestConnection())
        }
        assertFalse(MainUiState().canTestConnection())
    }
}
