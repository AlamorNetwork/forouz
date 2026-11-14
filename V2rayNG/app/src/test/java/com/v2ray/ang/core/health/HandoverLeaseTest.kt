package com.v2ray.ang.core.health

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HandoverLeaseTest {
    @Test fun `initial and duplicate callback do not trigger handover`() {
        val s = HandoverLease<String>()
        val first = s.available("wifi")!!
        assertFalse(first.first)
        assertFalse(s.available("wifi")!!.first)
        assertTrue(s.owns("wifi", first.second))
    }
    @Test fun `loss of old upstream cannot clear replacement or authorize stale callback`() {
        val s = HandoverLease<String>()
        val old = s.available("cell")!!
        val next = s.available("wifi")!!
        assertTrue(next.first)
        assertFalse(s.lost("cell"))
        assertFalse(s.owns("cell", old.second))
        assertTrue(s.owns("wifi", next.second))
    }
    @Test fun `restored network gets new epoch and closed monitor cannot reenter`() {
        val s = HandoverLease<String>()
        val old = s.available("wifi")!!
        assertTrue(s.lost("wifi"))
        val restored = s.available("wifi")!!
        assertTrue(restored.first)
        assertFalse(s.owns("wifi", old.second))
        s.close()
        assertFalse(s.owns("wifi", restored.second))
        assertNull(s.available("new"))
        assertFalse(s.lost("wifi"))
    }
}
