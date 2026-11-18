package com.v2ray.ang.core.cdn

import kotlin.random.Random

/** Adaptive sampling only: a failed sample defers its branch, never declares the prefix dead. */
internal class CdnBranchPlan(prefixes: List<CdnPrefix>, seeds: List<String>, private val budget: Int,
    private val random: Random = Random.Default) {
    private data class Task(val range: CdnPrefix, val retry: Boolean = false, val address: String? = null)
    private val roots = ArrayDeque(prefixes.map { Task(it) })
    private val promising = ArrayDeque<Task>()
    private val initial = ArrayDeque(seeds.map { ip -> Task(requireNotNull(prefixes.firstOrNull { it.contains(ip) }), address = ip) })
    private val tested = mutableSetOf<String>()
    private val expanded = mutableSetOf<CdnPrefix>()
    private val failures = mutableMapOf<CdnPrefix, Int>()
    private var pending: Pair<Task, String>? = null
    init { require(budget in 1..512 && prefixes.isNotEmpty() && prefixes.size <= 2048) }

    fun next(): String? {
        check(pending == null)
        if (tested.size >= budget) return null
        while (initial.isNotEmpty() || roots.isNotEmpty() || promising.isNotEmpty()) {
            val task = when {
                initial.isNotEmpty() -> initial.removeFirst()
                roots.isNotEmpty() && (promising.isEmpty() || tested.size % 3 == 0) -> roots.removeFirst()
                promising.isNotEmpty() -> promising.removeFirst()
                else -> roots.removeFirst()
            }
            if ((failures[task.range] ?: 0) >= 2 && task.range !in expanded) continue
            if (task.address == null && task.range in expanded && !task.retry) continue
            var address = task.address
            if (address == null) repeat(16) {
                val value = CdnPrefix.format(random.nextLong(task.range.first, task.range.last + 1))
                if (address == null && value !in tested) address = value
            }
            if (address == null || !tested.add(address)) continue
            pending = task to address
            return address
        }
        return null
    }
    fun report(address: String, success: Boolean) {
        val (task, expected) = requireNotNull(pending)
        require(expected == address)
        pending = null
        val range = task.range
        if (success && expanded.add(range)) {
            if (range.last - range.first + 1 > 256) {
                val middle = (range.first + range.last) / 2
                promising.addLast(Task(CdnPrefix(range.first, middle)))
                promising.addLast(Task(CdnPrefix(middle + 1, range.last)))
            } else if (range.last > range.first) {
                // Refine a successful /24-or-smaller branch with a few exact addresses, not every host.
                repeat(4) {
                    val ip = random.nextLong(range.first, range.last + 1)
                    promising.addLast(Task(CdnPrefix(ip, ip)))
                }
            }
        } else if (!success && range !in expanded) {
            val count = (failures[range] ?: 0) + 1
            failures[range] = count
            if (count < 2 && range.last > range.first) promising.addLast(Task(range, retry = true))
        }
    }
}
