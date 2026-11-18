package com.v2ray.ang.core.cdn

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CdnBranchPlanTest {
    @Test fun `successful branches refine beyond eight without enumerating all addresses`() {
        val range = CdnPrefix.parse("8.0.0.0/8")
        val plan = CdnBranchPlan(listOf(range), listOf("8.8.8.8"), 128, Random(4))
        val seen = mutableSetOf<String>()
        while (true) {
            val ip = plan.next() ?: break
            assertTrue(seen.add(ip)); assertTrue(range.contains(ip))
            plan.report(ip, true)
        }
        assertEquals(128, seen.size)
    }
    @Test fun `failed branch gets one alternate sample then is deferred without a subnet verdict`() {
        val plan = CdnBranchPlan(listOf(CdnPrefix.parse("8.8.8.0/24")), emptyList(), 128, Random(3))
        val first = requireNotNull(plan.next()); plan.report(first, false)
        val second = requireNotNull(plan.next()); plan.report(second, false)
        assertNotEquals(first, second); assertNull(plan.next())
    }
    @Test fun `exploration visits other roots and rejects overlapping pending requests`() {
        val ranges = listOf(CdnPrefix.parse("8.0.0.0/8"), CdnPrefix.parse("9.0.0.0/8"))
        val plan = CdnBranchPlan(ranges, listOf("8.8.8.8"), 32, Random(2))
        val first = requireNotNull(plan.next())
        assertThrows(IllegalStateException::class.java) { plan.next() }
        plan.report(first, true)
        val values = mutableListOf(first)
        while (true) { val ip = plan.next() ?: break; values += ip; plan.report(ip, true) }
        assertTrue(values.any { it.startsWith("9.") })
    }
}
