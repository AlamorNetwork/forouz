package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AwgLifecycleTest {
    @Test fun `duplicate commands cannot admit a second setup`() {
        val state = AwgLifecycle()
        assertTrue(state.tryStart())
        assertFalse(state.tryStart())
        assertTrue(state.markRunning())
        assertTrue(state.running)
        assertFalse(state.tryStart())
    }
    @Test fun `stop racing setup prevents a late running state`() {
        val state = AwgLifecycle()
        state.tryStart()
        assertTrue(state.beginStop())
        assertFalse(state.markRunning())
        assertFalse(state.running)
        assertTrue(state.stopping)
    }
    @Test fun `teardown is admitted once and restart requires completed teardown`() {
        val state = AwgLifecycle()
        state.tryStart()
        state.markRunning()
        assertTrue(state.beginStop())
        assertFalse(state.beginStop())
        assertFalse(state.tryStart())
        assertThrows(IllegalStateException::class.java) { state.prepareRestart() }
        state.finishStop()
        assertFalse(state.beginStop())
        state.prepareRestart()
        assertTrue(state.tryStart())
    }
    @Test fun `destroy before setup still admits cleanup once`() {
        val state = AwgLifecycle()
        assertTrue(state.beginStop())
        assertFalse(state.tryStart())
        state.finishStop()
        assertFalse(state.markRunning())
    }
}
