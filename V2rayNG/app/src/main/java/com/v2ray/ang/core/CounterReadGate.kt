package com.v2ray.ang.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Invalidation never waits on native I/O. Only the IO teardown waits for a reader to finish. */
internal class CounterReadGate {
    private val nativeLock = ReentrantLock()
    @Volatile private var current: Any? = null

    fun activate(): Any = Any().also { current = it }
    fun invalidate() { current = null }

    fun <T> read(token: Any, action: () -> T): T? = nativeLock.withLock {
        if (token !== current) return@withLock null
        val value = action()
        if (token === current) value else null
    }

    /** Call only from native-operation IO workers, never a framework callback. */
    fun <T> exclusive(action: () -> T): T = nativeLock.withLock(action)
}
