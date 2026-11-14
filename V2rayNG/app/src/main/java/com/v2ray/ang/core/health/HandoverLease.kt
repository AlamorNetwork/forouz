package com.v2ray.ang.core.health

/** Callback epoch: an old network loss or delayed task cannot replace the current upstream. */
internal class HandoverLease<T> {
    private var key: T? = null
    private var epoch = 0L
    private var seen = false
    private var closed = false
    @Synchronized fun available(value: T): Pair<Boolean, Long>? {
        if (closed) return null
        val changed = seen && key != value
        if (key != value) epoch++
        key = value; seen = true
        return changed to epoch
    }
    @Synchronized fun owns(value: T, token: Long? = null): Boolean = !closed && key == value && (token == null || token == epoch)
    @Synchronized fun lost(value: T): Boolean {
        if (closed || key != value) return false
        key = null; epoch++; return true
    }
    @Synchronized fun close() { closed = true; key = null; epoch++ }
}
