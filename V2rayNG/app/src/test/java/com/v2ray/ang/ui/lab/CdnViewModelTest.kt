package com.v2ray.ang.ui.lab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.core.cdn.*
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CdnViewModelTest {
    private class Source : CdnDataSource {
        var failure = false
        var updateFailure: CdnFailure? = null
        var data = CdnData(listOf(CdnProfile("guid", "profile")), 1000, CdnLive(null, 7))
        var updates = 0; var copies = 0
        val sends = mutableListOf<CdnRequest?>()
        val clipboard = mutableListOf<Pair<String, String?>>()
        override suspend fun load(): CdnData { if (failure) error("fixture"); return data }
        override suspend fun live() = data.live
        override suspend fun update() { updates++; updateFailure?.let { throw it }; if (failure) error("fixture") }
        override suspend fun prepare(guid: String, download: Boolean, budget: Int, manualIps: String): CdnRequest {
            if (manualIps.isNotBlank()) {
                val ips = CdnManualIps.parse(manualIps)
                return CdnRequest("manual", guid, "a".repeat(64), "manual", ips, false, ips.size, edgeScan = true, manual = true)
            }
            if (failure) throw CdnFailure(R.string.cdn_unknown)
            return CdnRequest("run", guid, "a".repeat(64), "cloudflare", listOf("8.8.8.8"), download, budget)
        }
        override suspend fun setFeedUrl(url: String) {
            if (failure) error("fixture")
            data = data.copy(feedUrl = url)
        }
        override suspend fun send(request: CdnRequest?) { sends += request }
        override suspend fun save(runId: String): String { copies++; return "copy" }
        override suspend fun copy(runId: String, address: String?): Boolean {
            if (failure) error("fixture")
            clipboard += runId to address
            return true
        }
    }
    private fun model(source: Source = Source(), saved: SavedStateHandle = SavedStateHandle(), test: (CdnViewModel, Source) -> Unit) {
        val store = ViewModelStore()
        val vm = CdnViewModel(saved, source, Dispatchers.Unconfined, { 1000 }, {})
        store.put("cdn", vm)
        try { test(vm, source) } finally { store.clear() }
    }
    @Test fun `initial state loads metadata without fetching or scanning and selection uses GUID`() = model { vm, src ->
        assertNotNull(vm.uiState.value.data); assertEquals("", vm.uiState.value.guid)
        assertEquals(0, src.updates); assertTrue(src.sends.isEmpty())
        vm.onAction(CdnAction.Select("missing")); assertEquals("", vm.uiState.value.guid)
        vm.onAction(CdnAction.Select("guid")); assertEquals("guid", vm.uiState.value.guid)
        vm.onAction(CdnAction.Preview); assertNotNull(vm.uiState.value.preview); assertTrue(src.sends.isEmpty())
        vm.onAction(CdnAction.Budget(128)); assertEquals(128, vm.uiState.value.budget); assertNull(vm.uiState.value.preview)
        vm.onAction(CdnAction.Budget(9)); assertEquals(128, vm.uiState.value.budget)
    }
    @Test fun `catalog HTTP error retains status and a successful retry clears the visible error`() = model { vm, src ->
        src.updateFailure = CdnFailure(R.string.cdn_feed_http, 503)
        vm.onAction(CdnAction.Update)
        assertEquals(R.string.cdn_feed_http, vm.uiState.value.error)
        assertEquals(503, vm.uiState.value.errorCode); assertFalse(vm.uiState.value.busy)
        src.updateFailure = null; vm.onAction(CdnAction.Update)
        assertNull(vm.uiState.value.error); assertFalse(vm.uiState.value.busy)
    }
    @Test fun `confirmation dispatches a frozen request only once and cancel is separate`() = model { vm, src ->
        vm.onAction(CdnAction.Select("guid")); vm.onAction(CdnAction.Download(true)); vm.onAction(CdnAction.Preview)
        val expected = vm.uiState.value.preview
        vm.onAction(CdnAction.Confirm); vm.onAction(CdnAction.Confirm)
        assertEquals(listOf(expected), src.sends); assertTrue(vm.uiState.value.starting)
        vm.onAction(CdnAction.Cancel); assertNull(src.sends.last())
    }
    @Test fun `dismiss failure empty and changed selection do not dispatch tests`() = model { vm, src ->
        vm.onAction(CdnAction.Preview); assertNull(vm.uiState.value.preview)
        vm.onAction(CdnAction.Select("guid")); vm.onAction(CdnAction.Preview); vm.onAction(CdnAction.Dismiss)
        vm.onAction(CdnAction.Confirm); assertTrue(src.sends.isEmpty())
        src.failure = true; vm.onAction(CdnAction.Preview)
        assertEquals(R.string.cdn_unknown, vm.uiState.value.error); assertFalse(vm.uiState.value.busy)
        vm.onAction(CdnAction.Update); assertEquals(R.string.cdn_error, vm.uiState.value.error)
        src.failure = false; src.data = src.data.copy(profiles = emptyList()); vm.onAction(CdnAction.Reload)
        assertEquals("", vm.uiState.value.guid)
    }
    @Test fun `fresh candidate is saved once and saved marker survives recreation`() {
        val src = Source()
        val req = CdnRequest("run", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), false)
        src.data = src.data.copy(live = CdnLive(CdnRun(req, 1000, 1000, CdnStatus.COMPLETE,
            listOf(CdnRow("8.8.8.8", listOf(30, 40, 50))), 7), 7))
        val saved = SavedStateHandle()
        model(src, saved) { vm, _ -> assertTrue(vm.uiState.value.canSave); vm.onAction(CdnAction.Save); vm.onAction(CdnAction.Save); assertTrue(vm.uiState.value.saved) }
        model(src, saved) { vm, _ -> assertFalse(vm.uiState.value.canSave); assertTrue(vm.uiState.value.saved) }
        assertEquals(1, src.copies)
    }
    @Test fun `live running result prevents new selections feed refresh and duplicate scans`() {
        val src = Source()
        src.data = src.data.copy(live = CdnLive(CdnRun(CdnRequest("run", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), false),
            1000, 1000, CdnStatus.RUNNING), 7))
        model(src) { vm, _ ->
            vm.onAction(CdnAction.Select("guid")); vm.onAction(CdnAction.Update); vm.onAction(CdnAction.Preview)
            assertEquals("", vm.uiState.value.guid); assertEquals(0, src.updates); assertTrue(src.sends.isEmpty())
        }
    }
    @Test fun `edge copy uses run ID permits only reachable IPs and keeps errors visible`() {
        val src = Source()
        val req = CdnRequest("edge", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), false, edgeScan = true)
        val edge = CdnEdgeResult(CdnEdgeCheck(5), CdnEdgeCheck(issue = com.v2ray.ang.core.lab.LabIssue.IO), CdnEdgeCheck(skipped = true))
        src.data = src.data.copy(live = CdnLive(CdnRun(req, 1000, 1000, CdnStatus.CANCELLED,
            listOf(CdnRow("8.8.8.8", edge = edge)), 7), 7))
        model(src) { vm, _ ->
            vm.onAction(CdnAction.Copy("stale")); vm.onAction(CdnAction.Copy("edge", "1.1.1.1"))
            assertTrue(src.clipboard.isEmpty()); assertFalse(vm.uiState.value.canSave)
            vm.onAction(CdnAction.Copy("edge")); vm.onAction(CdnAction.Copy("edge", "8.8.8.8"))
            assertEquals(listOf("edge" to null, "edge" to "8.8.8.8"), src.clipboard)
            assertTrue(vm.uiState.value.copied)
            src.failure = true; vm.onAction(CdnAction.Copy("edge"))
            assertEquals(R.string.cdn_error, vm.uiState.value.error)
            assertFalse(vm.uiState.value.busy); assertFalse(vm.uiState.value.copied)
        }
    }
    @Test fun `empty and old reports cannot copy edge IPs`() = model { vm, src ->
        vm.onAction(CdnAction.Copy("missing"))
        assertTrue(src.clipboard.isEmpty()); assertFalse(vm.uiState.value.copied)
    }
    @Test fun `catalog source edits require explicit save and refresh and reset does not fetch`() = model { vm, src ->
        vm.onAction(CdnAction.EditFeedUrl("https://mirror.example/feed.json"))
        assertEquals(CdnPolicy.FEED, src.data.feedUrl); assertEquals(0, src.updates)
        vm.onAction(CdnAction.Update); assertEquals(R.string.cdn_source_unsaved, vm.uiState.value.error)
        assertEquals(0, src.updates)
        vm.onAction(CdnAction.SaveFeedUrl)
        assertEquals("https://mirror.example/feed.json", src.data.feedUrl); assertEquals(0, src.updates)
        vm.onAction(CdnAction.Update); assertEquals(1, src.updates)
        vm.onAction(CdnAction.ResetFeedUrl); assertEquals(CdnPolicy.FEED, src.data.feedUrl); assertEquals(1, src.updates)
        vm.onAction(CdnAction.EditFeedUrl("http://mirror.example/")); vm.onAction(CdnAction.SaveFeedUrl)
        assertEquals(R.string.cdn_source_invalid, vm.uiState.value.error); assertEquals(CdnPolicy.FEED, src.data.feedUrl)
    }
    @Test fun `manual list previews exact IPs without refreshing or needing cached catalog`() {
        val src = Source(); src.data = src.data.copy(feedAt = null)
        model(src) { vm, _ ->
            vm.onAction(CdnAction.Select("guid"))
            vm.onAction(CdnAction.ManualIps("1.1.1.1\n8.8.8.8\n1.1.1.1"))
            vm.onAction(CdnAction.Preview)
            val request = requireNotNull(vm.uiState.value.preview)
            assertTrue(request.manual); assertEquals("manual", request.provider)
            assertEquals(listOf("1.1.1.1", "8.8.8.8"), request.addresses); assertEquals(2, request.budget)
            assertEquals(0, src.updates); assertTrue(src.sends.isEmpty())
            vm.onAction(CdnAction.Confirm); assertEquals(listOf(request), src.sends)
        }
    }
}
