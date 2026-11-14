package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AwgProbeRequestsTest {
    @Test fun `cancelling an old request never cancels the newer probe or stops admission`() {
        val requests = AwgProbeRequests().apply { activate("profile") }
        val old = requests.begin("old")!!
        val current = requests.begin("current")!!
        assertFalse(requests.cancel("old"))
        assertTrue(requests.finish(current))
        assertFalse(requests.finish(old))
        val next = requests.begin("next")!!
        assertTrue(requests.cancel("next"))
        assertFalse(requests.finish(next))
        assertNotNull(requests.begin("still-connected"))
    }
    @Test fun `stopped and starting engines cannot admit probes`() {
        val requests = AwgProbeRequests()
        assertNull(requests.begin("one"))
        requests.activate("profile")
        assertNull(requests.begin(""))
        assertNull(requests.begin("  "))
        assertNotNull(requests.begin("one"))
    }

    @Test fun `duplicate command does not supersede the active request and completion is consumed once`() {
        val requests = AwgProbeRequests().apply { activate("profile") }
        val first = requests.begin("one")!!
        assertNull(requests.begin("one"))
        assertEquals("profile", first.profileGuid)
        assertTrue(requests.finish(first))
        assertFalse(requests.finish(first))
    }

    @Test fun `a newer request rejects a late prior completion`() {
        val requests = AwgProbeRequests().apply { activate("profile") }
        val old = requests.begin("old")!!
        val current = requests.begin("new")!!
        assertFalse(requests.finish(old))
        assertTrue(requests.finish(current))
    }

    @Test fun `stop repeated stop and restart prevent a previous session from reporting success`() {
        val requests = AwgProbeRequests().apply { activate("profile") }
        val previous = requests.begin("same-id")!!
        requests.stop()
        requests.stop()
        assertFalse(requests.finish(previous))
        assertNull(requests.begin("after-stop"))
        requests.activate("profile")
        val current = requests.begin("same-id")!!
        assertFalse(requests.finish(previous))
        assertTrue(requests.finish(current))
    }

    @Test fun `only a known VPN owned by the app is eligible`() {
        assertTrue(isOwnedAwgVpn(true, 12_345, 12_345))
        assertFalse(isOwnedAwgVpn(false, 12_345, 12_345))
        assertFalse(isOwnedAwgVpn(true, 55_555, 12_345))
        assertFalse(isOwnedAwgVpn(true, -1, 12_345))
        assertFalse(isOwnedAwgVpn(true, -1, -1))
    }
}
