package com.v2ray.ang.core

import java.util.concurrent.atomic.AtomicReference

/** Serializes admission independently of blocking native calls; stop wins over late setup success. */
internal class AwgLifecycle {
    private enum class State { IDLE, STARTING, RUNNING, STOPPING, STOPPED }
    private val state = AtomicReference(State.IDLE)
    val running: Boolean get() = state.get() == State.RUNNING
    val stopping: Boolean get() = state.get() in setOf(State.STOPPING, State.STOPPED)
    fun tryStart(): Boolean = state.compareAndSet(State.IDLE, State.STARTING)
    fun markRunning(): Boolean = state.compareAndSet(State.STARTING, State.RUNNING)
    fun beginStop(): Boolean {
        while (true) {
            val current = state.get()
            if (current == State.STOPPING || current == State.STOPPED) return false
            if (state.compareAndSet(current, State.STOPPING)) return true
        }
    }
    fun finishStop() { check(state.compareAndSet(State.STOPPING, State.STOPPED)) }
    fun prepareRestart() { check(state.compareAndSet(State.STOPPED, State.IDLE)) }
}
