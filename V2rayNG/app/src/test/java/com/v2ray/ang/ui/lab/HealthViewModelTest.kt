package com.v2ray.ang.ui.lab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.core.health.*
import com.v2ray.ang.core.lab.LabStartRequest
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthViewModelTest {
    private class Source : LabDataSource {
        var data = LabData(listOf(LabGroup("", "all")), emptyList(), emptyList())
        var failSend = false
        val commands = mutableListOf<HealthRequest?>()
        override suspend fun load(groupId: String) = data
        override suspend fun send(request: LabStartRequest?) { error("Unexpected download") }
        override suspend fun sendHealth(request: HealthRequest?) {
            if (failSend) error("fixture")
            commands += request
        }
    }
    private fun model(source: Source = Source(), saved: SavedStateHandle = SavedStateHandle(),
        test: (LabViewModel, Source, (Long) -> Unit) -> Unit) {
        var now = 0L
        val store = ViewModelStore()
        val vm = LabViewModel(saved, source, Dispatchers.Unconfined, elapsedClock = { now },
            reportFailure = { _, _ -> }, computationDispatcher = Dispatchers.Unconfined)
        store.put("health", vm)
        try { test(vm, source) { now = it } } finally { store.clear() }
    }
    @Test fun `defaults are manual recovery and page open never starts a request`() = model { vm, source, _ ->
        assertEquals(60, vm.uiState.value.healthInterval)
        assertFalse(vm.uiState.value.healthRecovery)
        vm.onAction(LabAction.ShowHealth)
        assertTrue(vm.uiState.value.healthPage)
        vm.onAction(LabAction.Preview)
        assertNull(vm.uiState.value.preview)
        assertTrue(source.commands.isEmpty())
    }
    @Test fun `preview freezes explicit settings and only confirmation sends once`() = model { vm, source, _ ->
        vm.onAction(LabAction.HealthInterval(30))
        vm.onAction(LabAction.HealthRecovery(true))
        vm.onAction(LabAction.PreviewHealth)
        val request = vm.uiState.value.healthPreview!!
        assertEquals(30, request.intervalSeconds)
        assertTrue(request.recover)
        assertTrue(source.commands.isEmpty())
        vm.onAction(LabAction.ConfirmHealth); vm.onAction(LabAction.ConfirmHealth)
        assertEquals(listOf(request), source.commands)
        assertTrue(vm.uiState.value.healthStarting) // Enqueue is not daemon acknowledgement.
        vm.onAction(LabAction.StopHealth)
        assertNull(source.commands.last())
    }
    @Test fun `pending start clears only after matching persisted run or timeout`() = model { vm, source, clock ->
        vm.onAction(LabAction.PreviewHealth); vm.onAction(LabAction.ConfirmHealth)
        source.data = source.data.copy(healthRuns = listOf(HealthRun("different", 0, 0, 60, false)))
        vm.onAction(LabAction.ShowHealth)
        assertTrue(vm.uiState.value.healthStarting)
        clock(15_001)
        vm.onAction(LabAction.ShowHealth)
        assertFalse(vm.uiState.value.healthStarting)
        assertEquals(R.string.health_error, vm.uiState.value.healthError)
    }
    @Test fun `matching persisted ID acknowledges start and active run locks options`() = model { vm, source, _ ->
        vm.onAction(LabAction.PreviewHealth); vm.onAction(LabAction.ConfirmHealth)
        val request = source.commands.single()!!
        source.data = source.data.copy(healthRuns = listOf(HealthRun(request.id, 0, 0, 60, false)))
        vm.onAction(LabAction.ShowHealth)
        assertFalse(vm.uiState.value.healthStarting)
        vm.onAction(LabAction.HealthInterval(120)); vm.onAction(LabAction.HealthRecovery(true))
        assertEquals(60, vm.uiState.value.healthInterval)
        assertFalse(vm.uiState.value.healthRecovery)
        vm.onAction(LabAction.PreviewHealth)
        assertNull(vm.uiState.value.healthPreview)
    }
    @Test fun `invalid interval cancellation empty data and corrupt health data do not dispatch`() = model { vm, source, _ ->
        vm.onAction(LabAction.HealthInterval(-1))
        assertEquals(60, vm.uiState.value.healthInterval)
        vm.onAction(LabAction.PreviewHealth); vm.onAction(LabAction.DismissHealth); vm.onAction(LabAction.ConfirmHealth)
        assertTrue(source.commands.isEmpty())
        source.data = source.data.copy(healthUnavailable = true)
        vm.onAction(LabAction.ShowHealth); vm.onAction(LabAction.PreviewHealth)
        assertNull(vm.uiState.value.healthPreview)
        assertEquals(R.string.health_error, vm.uiState.value.healthError)
    }
    @Test fun `send failure resets pending state and publishes error`() = model { vm, source, _ ->
        source.failSend = true
        vm.onAction(LabAction.PreviewHealth); vm.onAction(LabAction.ConfirmHealth)
        assertFalse(vm.uiState.value.healthStarting)
        assertNull(vm.uiState.value.healthPreview)
        assertEquals(R.string.health_error, vm.uiState.value.healthError)
    }
    @Test fun `old report selected and restored by stable ID and missing report closes explicitly`() {
        val source = Source().apply { data = data.copy(healthRuns = listOf(
            HealthRun("new", 2_000, 2_000, 60, false, HealthStatus.ENDED),
            HealthRun("old", 1_000, 1_000, 30, true, HealthStatus.ENDED, checks = 7))) }
        val saved = SavedStateHandle(mapOf("health_page" to true, "health_run_id" to "old", "health_interval" to 120))
        model(source, saved) { vm, _, _ ->
            assertTrue(vm.uiState.value.healthPage)
            assertEquals(7, vm.uiState.value.healthSelected?.checks)
            assertEquals(120, vm.uiState.value.healthInterval)
            vm.onAction(LabAction.OpenHealth("missing"))
            assertEquals("old", vm.uiState.value.healthRunId)
            source.data = source.data.copy(healthRuns = emptyList())
            vm.onAction(LabAction.ShowHealth)
            assertNull(vm.uiState.value.healthRunId)
            assertNull(saved.get<String>("health_run_id"))
            assertTrue(source.commands.isEmpty())
            vm.onAction(LabAction.ShowConfigs)
            assertFalse(vm.uiState.value.healthPage)
        }
    }
}
