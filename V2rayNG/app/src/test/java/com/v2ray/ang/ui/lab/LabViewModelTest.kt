package com.v2ray.ang.ui.lab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.core.lab.*
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabViewModelTest {
    @Test fun `CDN tab navigation does not dispatch laboratory tests and restores the selected page`() = withModel { vm, source ->
        vm.onAction(LabAction.ShowCdn)
        assertTrue(vm.uiState.value.cdnPage)
        assertTrue(source.sends.isEmpty())
        vm.onAction(LabAction.ShowConfigs)
        assertFalse(vm.uiState.value.cdnPage)
    }
    private class Source : LabDataSource {
        var fail = false
        var data = LabData(listOf(LabGroup("", "all"), LabGroup("sub", "subscription")), listOf(
            LabProfile(LabCandidate("guid", "a".repeat(64), LabTransport.WS), "name", true)), emptyList())
        val sends = mutableListOf<LabStartRequest?>()
        override suspend fun load(groupId: String): LabData {
            if (fail) throw IllegalStateException("storage fixture")
            return data
        }
        override suspend fun sendHealth(request: com.v2ray.ang.core.health.HealthRequest?) { error("Unexpected health command") }
        override suspend fun send(request: LabStartRequest?) { sends += request }
    }
    private fun withModel(source: Source = Source(), saved: SavedStateHandle = SavedStateHandle(), test: (LabViewModel, Source) -> Unit) {
        val store = ViewModelStore()
        val vm = LabViewModel(saved, source, Dispatchers.Unconfined,
            elapsedClock = { 0 }, reportFailure = { _, _ -> }, computationDispatcher = Dispatchers.Unconfined)
        store.put("lab", vm)
        try { test(vm, source) } finally { store.clear() }
    }

    @Test fun `loading publishes real data and no request starts before confirmation`() = withModel { vm, source ->
        assertFalse(vm.uiState.value.loading)
        assertEquals(1, vm.uiState.value.data!!.profiles.size)
        assertTrue(source.sends.isEmpty())
        vm.onAction(LabAction.Preview)
        assertEquals(1, vm.uiState.value.preview!!.eligible)
        assertTrue(source.sends.isEmpty())
        vm.onAction(LabAction.Dismiss)
        assertNull(vm.uiState.value.preview)
        vm.onAction(LabAction.Confirm)
        assertTrue(source.sends.isEmpty())
    }

    @Test fun `confirmation sends the frozen snapshot once and cancel dispatches separately`() = withModel { vm, source ->
        vm.onAction(LabAction.Preview)
        val expected = vm.uiState.value.preview!!.request
        vm.onAction(LabAction.Confirm); vm.onAction(LabAction.Confirm)
        assertEquals(listOf(expected), source.sends)
        vm.onAction(LabAction.Cancel)
        assertNull(source.sends.last())
    }

    @Test fun `invalid URL empty or unsupported and excessive group return explicit errors`() = withModel { vm, source ->
        vm.onAction(LabAction.SetUrl("http://file.invalid")); vm.onAction(LabAction.Preview)
        assertEquals(R.string.lab_invalid_url, vm.uiState.value.error)
        vm.onAction(LabAction.SetUrl("https://file.invalid/data"))
        source.data = source.data.copy(profiles = emptyList()); vm.onAction(LabAction.Preview)
        assertEquals(R.string.lab_no_supported, vm.uiState.value.error)
        source.data = source.data.copy(profiles = (0..200).map {
            LabProfile(LabCandidate("g-$it", "a".repeat(64), LabTransport.WS), "n", true)
        }); vm.onAction(LabAction.Preview)
        assertEquals(R.string.lab_too_many, vm.uiState.value.error)
        assertTrue(source.sends.isEmpty())
    }

    @Test fun `selection resolves group IDs and storage failure cannot launch a round`() = withModel { vm, source ->
        vm.onAction(LabAction.SelectGroup("missing")); assertEquals("", vm.uiState.value.groupId)
        vm.onAction(LabAction.SelectGroup("sub")); assertEquals("sub", vm.uiState.value.groupId)
        source.fail = true; vm.onAction(LabAction.Preview)
        assertEquals(R.string.lab_history_error, vm.uiState.value.error)
        assertTrue(source.sends.isEmpty())
    }
    private fun runRecord(id: String, group: String = "", delay: Long = 10) = LabRunRecord(
        id, group, 1_000, 2_000, LabRunStatus.COMPLETE,
        listOf(LabRow("guid", "a".repeat(64), LabTransport.WS, LabRowStatus.SUCCESS,
            responseMillis = delay, elapsedMillis = delay + 100, bytes = LabPlan.DOWNLOAD_BYTES, testedAt = 1_500)))

    @Test fun `an older report resolves its own stored rows instead of the latest round`() {
        val source = Source().apply { data = data.copy(runs = listOf(runRecord("new", delay = 20), runRecord("old", delay = 80))) }
        withModel(source) { vm, data ->
            vm.onAction(LabAction.ShowHistory)
            vm.onAction(LabAction.OpenReport("old"))
            assertTrue(vm.uiState.value.historyPage)
            assertEquals("old", vm.uiState.value.historyRun?.id)
            assertEquals(80L, vm.uiState.value.historyRun?.rows?.first()?.responseMillis)
            assertEquals("name", vm.uiState.value.historyNames["guid"])
            assertTrue(data.sends.isEmpty())
        }
    }

    @Test fun `history is global and opening another group neither changes the test group nor starts a download`() {
        val source = Source().apply { data = data.copy(runs = listOf(runRecord("other", "sub"))) }
        withModel(source) { vm, data ->
            vm.onAction(LabAction.OpenReport("other"))
            assertEquals("", vm.uiState.value.groupId)
            assertEquals("other", vm.uiState.value.historyRun?.id)
            vm.onAction(LabAction.Preview)
            assertNull(vm.uiState.value.preview)
            assertTrue(data.sends.isEmpty())
        }
    }

    @Test fun `a report remains historical when its current profile changed or no longer exists`() {
        val source = Source().apply { data = data.copy(profiles = emptyList(), runs = listOf(runRecord("old"))) }
        withModel(source) { vm, _ ->
            vm.onAction(LabAction.OpenReport("old"))
            assertEquals(LabRowStatus.SUCCESS, vm.uiState.value.historyRun?.rows?.first()?.status)
            assertEquals(LabPlan.DOWNLOAD_BYTES, vm.uiState.value.historyRun?.rows?.first()?.bytes)
            assertTrue(vm.uiState.value.historyNames.isEmpty())
            assertEquals(setOf(LabTransport.WS), vm.uiState.value.historyGroups.keys)
        }
    }

    @Test fun `report page and run ID restore while a pruned report returns an explicit missing result`() {
        val source = Source().apply { data = data.copy(runs = listOf(runRecord("old"))) }
        val saved = SavedStateHandle(mapOf("history_page" to true, "history_run_id" to "old"))
        withModel(source, saved) { vm, data ->
            assertEquals("old", vm.uiState.value.historyRun?.id)
            data.data = data.data.copy(runs = emptyList())
            vm.onAction(LabAction.OpenReport("old"))
            assertTrue(vm.uiState.value.historyPage)
            assertNull(vm.uiState.value.historyRunId)
            assertEquals(R.string.lab_history_missing, vm.uiState.value.error)
            assertNull(saved.get<String>("history_run_id"))
        }
    }

    @Test fun `empty history and missing IDs are readable without triggering network commands`() = withModel { vm, source ->
        vm.onAction(LabAction.ShowHistory)
        assertTrue(vm.uiState.value.historyPage)
        assertNull(vm.uiState.value.historyRun)
        vm.onAction(LabAction.OpenReport("missing"))
        assertEquals(R.string.lab_history_missing, vm.uiState.value.error)
        vm.onAction(LabAction.CloseReport)
        assertTrue(vm.uiState.value.historyPage)
        assertNull(vm.uiState.value.historyRunId)
        vm.onAction(LabAction.ShowConfigs)
        assertFalse(vm.uiState.value.historyPage)
        assertTrue(source.sends.isEmpty())
    }

    @Test fun `failed storage refresh does not replace the selected report with another round`() {
        val source = Source().apply { data = data.copy(runs = listOf(runRecord("new"), runRecord("old"))) }
        withModel(source) { vm, data ->
            vm.onAction(LabAction.OpenReport("old"))
            data.fail = true
            vm.onAction(LabAction.OpenReport("old"))
            assertEquals("old", vm.uiState.value.historyRun?.id)
            assertEquals(R.string.lab_history_error, vm.uiState.value.error)
            assertTrue(data.sends.isEmpty())
        }
    }

}
