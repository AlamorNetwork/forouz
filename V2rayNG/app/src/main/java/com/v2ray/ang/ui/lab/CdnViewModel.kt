package com.v2ray.ang.ui.lab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.cdn.*
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.handler.cdnFeedUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface CdnAction {
    data class Select(val guid: String) : CdnAction
    data class Download(val enabled: Boolean) : CdnAction
    data class Budget(val count: Int) : CdnAction
    data object Update : CdnAction
    data object Reload : CdnAction
    data object Preview : CdnAction
    data object Confirm : CdnAction
    data object Dismiss : CdnAction
    data object Cancel : CdnAction
    data object Save : CdnAction
    data class Copy(val runId: String, val address: String? = null) : CdnAction
    data class EditFeedUrl(val value: String) : CdnAction
    data object SaveFeedUrl : CdnAction
    data object ResetFeedUrl : CdnAction
    data class ManualIps(val value: String) : CdnAction
}
internal data class CdnUiState(val guid: String = "", val download: Boolean = false, val budget: Int = 64, val data: CdnData? = null,
    val live: CdnLive? = null, val preview: CdnRequest? = null, val busy: Boolean = false, val starting: Boolean = false,
    val error: Int? = null, val errorCode: Int? = null, val saved: Boolean = false, val now: Long = System.currentTimeMillis(),
    val copied: Boolean = false, val feedUrl: String = CdnPolicy.FEED, val manualIps: String = "") {
    val active: Boolean get() = live?.run?.status == CdnStatus.RUNNING || starting
    val canSave: Boolean get() = !busy && !saved && live?.let { it.run?.canSave(now, it.network) } == true
    val stale: Boolean get() = live?.run?.let { it.status == CdnStatus.RUNNING && now - it.updatedAt > 120_000 } == true
}
internal class CdnViewModel(private val saved: SavedStateHandle, private val source: CdnDataSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: ((Exception) -> Unit)? = null) : ViewModel() {
    private val state = MutableStateFlow(CdnUiState(guid = saved["cdn_guid"] ?: "", download = saved["cdn_download"] ?: false,
        budget = saved.get<Int>("cdn_budget")?.takeIf { it in listOf(32, 64, 128, 512) } ?: 64))
    val uiState = state.asStateFlow()
    private val operations = Mutex()
    private var pending: String? = null
    private var pendingAt = 0L
    init {
        viewModelScope.launch(dispatcher) {
            guarded("load") { reload() }
            while (isActive) {
                delay(1_000)
                guarded {
                    val live = source.live()
                    if (live.run?.request?.id == pending) pending = null
                    if (pending != null && (clock() < pendingAt || clock() - pendingAt > 15_000)) {
                        pending = null; state.update { it.copy(error = R.string.lab_start_failed) }
                    }
                    state.update { it.copy(live = live, starting = pending != null, now = clock(),
                        saved = saved.get<String>("cdn_saved_run") == live.run?.request?.id && live.run != null) }
                }
            }
        }
    }
    private suspend fun guarded(phase: String = "poll", block: suspend () -> Unit) = operations.withLock {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (log != null) log.invoke(e) else LogUtil.e(AppConfig.TAG,
                "CDN mode=UI phase=$phase profile=${state.value.guid}", IllegalStateException(e.javaClass.simpleName))
            pending = null
            state.update { it.copy(error = (e as? CdnFailure)?.text ?: R.string.cdn_error, errorCode = (e as? CdnFailure)?.code,
                busy = false, starting = false, preview = null) }
        }
    }
    private suspend fun reload() {
        val data = source.load()
        val guid = state.value.guid.takeIf { id -> data.profiles.any { it.guid == id } }.orEmpty()
        saved["cdn_guid"] = guid
        state.update { it.copy(data = data, live = data.live, guid = guid, now = clock(), busy = false,
            feedUrl = if (it.data != null && it.feedUrl != it.data.feedUrl) it.feedUrl else data.feedUrl,
            saved = saved.get<String>("cdn_saved_run") == data.live.run?.request?.id && data.live.run != null) }
    }
    private suspend fun saveFeedUrl(raw: String) {
        val url = try { cdnFeedUrl(raw) } catch (_: IllegalArgumentException) { throw CdnFailure(R.string.cdn_source_invalid) }
        state.update { it.copy(busy = true, error = null) }
        source.setFeedUrl(url)
        state.update { it.copy(busy = false, feedUrl = url, data = it.data?.copy(feedUrl = url)) }
    }
    fun onAction(action: CdnAction) {
        if (action == CdnAction.Dismiss) { state.update { it.copy(preview = null) }; return }
        viewModelScope.launch(dispatcher) { guarded(action.javaClass.simpleName) {
            val current = state.value
            if (current.busy) return@guarded
            if (action !is CdnAction.Copy) state.update { it.copy(copied = false) }
            when (action) {
                is CdnAction.EditFeedUrl -> if (!current.active) {
                    if (action.value.length > 2048) throw CdnFailure(R.string.cdn_source_invalid)
                    state.update { it.copy(feedUrl = action.value, error = null) }
                }
                CdnAction.SaveFeedUrl -> if (!current.active) saveFeedUrl(current.feedUrl)
                CdnAction.ResetFeedUrl -> if (!current.active) saveFeedUrl(CdnPolicy.FEED)
                is CdnAction.ManualIps -> if (!current.active) {
                    if (action.value.length > 16_384) throw CdnFailure(R.string.cdn_manual_invalid)
                    state.update { it.copy(manualIps = action.value, preview = null, error = null) }
                }
                is CdnAction.Select -> if (!current.active && current.data?.profiles?.any { it.guid == action.guid } == true) {
                    saved["cdn_guid"] = action.guid
                    state.update { it.copy(guid = action.guid, preview = null, error = null) }
                }
                is CdnAction.Download -> if (!current.active) {
                    saved["cdn_download"] = action.enabled
                    state.update { it.copy(download = action.enabled, preview = null, error = null) }
                }
                is CdnAction.Budget -> if (!current.active && action.count in listOf(32, 64, 128, 512)) {
                    saved["cdn_budget"] = action.count
                    state.update { it.copy(budget = action.count, preview = null, error = null) }
                }
                CdnAction.Update, CdnAction.Reload -> if (!current.active) {
                    if (action == CdnAction.Update && current.feedUrl != (current.data?.feedUrl ?: CdnPolicy.FEED))
                        throw CdnFailure(R.string.cdn_source_unsaved)
                    state.update { it.copy(busy = true, error = null, preview = null) }
                    if (action == CdnAction.Update) source.update()
                    reload()
                }
                CdnAction.Preview -> if (!current.active && current.guid.isNotEmpty()) {
                    state.update { it.copy(busy = true, error = null) }
                    val request = source.prepare(current.guid, current.download, current.budget, current.manualIps)
                    state.update { it.copy(busy = false, preview = request) }
                }
                CdnAction.Confirm -> {
                    val request = current.preview ?: return@guarded
                    pending = request.id; pendingAt = clock()
                    state.update { it.copy(preview = null, starting = true, error = null, saved = false) }
                    source.send(request)
                }
                CdnAction.Cancel -> source.send(null)
                is CdnAction.Copy -> {
                    val run = current.live?.run ?: return@guarded
                    if (run.request.id != action.runId || run.edgeAddresses().let { eligible ->
                        eligible.isEmpty() || (action.address != null && action.address !in eligible) }) return@guarded
                    state.update { it.copy(busy = true, error = null, copied = false) }
                    check(source.copy(action.runId, action.address))
                    state.update { it.copy(busy = false, copied = true) }
                }
                CdnAction.Save -> if (current.canSave) {
                    state.update { it.copy(busy = true, error = null) }
                    val id = requireNotNull(current.live?.run?.request?.id)
                    source.save(id)
                    saved["cdn_saved_run"] = id
                    state.update { it.copy(busy = false, saved = true) }
                }
                CdnAction.Dismiss -> Unit
            }
        } }
    }
}
