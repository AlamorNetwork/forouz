package com.v2ray.ang.ui.lab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.lab.*
import com.v2ray.ang.core.health.*
import com.v2ray.ang.util.LogUtil
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface LabAction {
    data class SelectGroup(val id: String) : LabAction
    data class SetUrl(val value: String) : LabAction
    data object Preview : LabAction
    data object Confirm : LabAction
    data object Dismiss : LabAction
    data object Cancel : LabAction
    data object ShowHistory : LabAction
    data object ShowConfigs : LabAction
    data object ShowCdn : LabAction
    data class OpenReport(val id: String) : LabAction
    data object CloseReport : LabAction
    data object ShowHealth : LabAction
    data class HealthInterval(val seconds: Int) : LabAction
    data class HealthRecovery(val enabled: Boolean) : LabAction
    data object PreviewHealth : LabAction
    data object ConfirmHealth : LabAction
    data object DismissHealth : LabAction
    data object StopHealth : LabAction
    data class OpenHealth(val id: String?) : LabAction
}

internal data class LabPreview(val request: LabStartRequest, val eligible: Int)
internal data class LabUiState(val groupId: String = "", val url: String = LabHttpProbe.DEFAULT_URL,
    val data: LabData? = null, val preview: LabPreview? = null, val error: Int? = null,
    val healthPage: Boolean = false, val healthRunId: String? = null, val cdnPage: Boolean = false,
    val healthInterval: Int = 60, val healthRecovery: Boolean = false,
    val healthPreview: HealthRequest? = null, val healthStarting: Boolean = false, val healthError: Int? = null,
    val loading: Boolean = true, val starting: Boolean = false, val now: Long = System.currentTimeMillis(),
    val historyPage: Boolean = false, val historyRunId: String? = null, val historyNames: Map<String, String> = emptyMap(),
    val historyGroups: Map<LabTransport, List<LabRow>> = emptyMap()) {
    val healthActive: HealthRun? get() = data?.healthRuns?.firstOrNull { it.status == HealthStatus.RUNNING }
    val healthSelected: HealthRun? get() = healthRunId?.let { id -> data?.healthRuns?.firstOrNull { it.id == id } }
    val healthStale: Boolean get() = healthActive?.let { now - it.updatedAt > it.intervalSeconds * 2_000L + 30_000 || now < it.updatedAt } == true
    val active: LabRunRecord? get() = data?.runs?.firstOrNull { it.status == LabRunStatus.RUNNING }
    val latest: LabRunRecord? get() = data?.runs?.firstOrNull { it.groupId == groupId }
    val historyRun: LabRunRecord? get() = historyRunId?.let { id -> data?.runs?.firstOrNull { it.id == id } }
    val stale: Boolean get() = active?.let { now - it.updatedAt > 120_000 } == true
}

internal class LabViewModel(private val saved: SavedStateHandle, private val repository: LabDataSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val reportFailure: (String, Exception) -> Unit = { operation, error ->
        LogUtil.e(AppConfig.TAG, "Lab UI phase=$operation", IllegalStateException(error.javaClass.simpleName))
    }, private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private val state = MutableStateFlow(LabUiState(groupId = saved["group_id"] ?: "", url = saved["url"] ?: LabHttpProbe.DEFAULT_URL,
        historyPage = saved["history_page"] ?: false, historyRunId = saved["history_run_id"], cdnPage = saved["cdn_page"] ?: false,
        healthPage = saved["health_page"] ?: false, healthRunId = saved["health_run_id"],
        healthInterval = saved.get<Int>("health_interval")?.takeIf { it in listOf(30, 60, 120) } ?: 60,
        healthRecovery = saved["health_recovery"] ?: false))
    val uiState = state.asStateFlow()
    private val operations = Mutex()
    private var healthPendingId: String? = null
    private var healthPendingAt = 0L
    private var pendingId: String? = null
    private var pendingAt = 0L
    private var historyNamesFor: String? = null

    init {
        viewModelScope.launch(dispatcher) {
            while (isActive) {
                refresh()
                delay(1_000)
            }
        }
    }

    private suspend fun refresh() = operations.withLock {
        val group = state.value.groupId
        try {
            val data = repository.load(group)
            if (data.groups.none { it.id == group }) {
                saved["group_id"] = ""
                state.update { it.copy(groupId = "", data = null, loading = true, preview = null) }
                return@withLock
            }
            if (healthPendingId != null && data.healthRuns.any { it.id == healthPendingId }) healthPendingId = null
            val healthTimeout = healthPendingId != null && elapsedClock() - healthPendingAt > 15_000
            if (healthTimeout) healthPendingId = null
            val healthMissing = state.value.healthRunId != null && data.healthRuns.none { it.id == state.value.healthRunId }
            if (healthMissing) saved["health_run_id"] = null
            if (pendingId != null && data.runs.any { it.id == pendingId }) pendingId = null
            val timedOut = pendingId != null && elapsedClock() - pendingAt > 15_000
            if (timedOut) pendingId = null
            val selected = state.value.historyRunId
            val missing = selected != null && data.runs.none { it.id == selected }
            val selectedId = selected?.takeUnless { missing }
            if (missing) { saved["history_run_id"] = null; historyNamesFor = null }
            val names = if (selectedId == null) emptyMap() else if (historyNamesFor == selectedId) state.value.historyNames else {
                val run = data.runs.first { it.id == selectedId }
                val profiles = if (run.groupId == group) data.profiles else repository.load(run.groupId).profiles
                withContext(computationDispatcher) {
                    val guids = run.rows.map { it.guid }.toSet()
                    profiles.filter { it.candidate.guid in guids }.associate { it.candidate.guid to it.name }
                }.also { historyNamesFor = selectedId }
            }
            val historyGroups = withContext(computationDispatcher) {
                data.runs.firstOrNull { it.id == selectedId }?.rows?.groupBy { it.transport }
                    ?.toSortedMap(compareBy { it.ordinal })?.toMap().orEmpty()
            }
            state.update { it.copy(data = data, loading = false, starting = pendingId != null,
                healthStarting = healthPendingId != null, healthRunId = it.healthRunId.takeUnless { healthMissing },
                healthError = if (data.healthUnavailable || healthTimeout || healthMissing) R.string.health_error else it.healthError,
                historyRunId = selectedId, historyNames = names, historyGroups = historyGroups,
                error = if (missing) R.string.lab_history_missing else if (timedOut) R.string.lab_start_failed
                    else it.error?.takeUnless { e -> e == R.string.lab_history_error },
                now = System.currentTimeMillis()) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            log("load", e)
            pendingId = null
            state.update { it.copy(error = R.string.lab_history_error, loading = false, starting = false) }
        }
    }

    fun onAction(action: LabAction) {
        when (action) {
            is LabAction.SetUrl -> { saved["url"] = action.value; state.update { it.copy(url = action.value, error = null, preview = null) } }
            LabAction.Dismiss -> state.update { it.copy(preview = null) }
            else -> viewModelScope.launch(dispatcher) {
                operations.withLock {
                    try {
                        when (action) {
                            LabAction.ShowCdn -> {
                                saved["cdn_page"] = true
                                state.update { it.copy(cdnPage = true, preview = null) }
                            }
                            LabAction.ShowHealth -> {
                                saved["health_page"] = true
                                state.update { it.copy(healthPage = true, preview = null) }
                            }
                            is LabAction.HealthInterval -> if (action.seconds in listOf(30, 60, 120) && state.value.healthActive == null && !state.value.healthStarting) {
                                saved["health_interval"] = action.seconds
                                state.update { it.copy(healthInterval = action.seconds, healthPreview = null) }
                            }
                            is LabAction.HealthRecovery -> if (state.value.healthActive == null && !state.value.healthStarting) {
                                saved["health_recovery"] = action.enabled
                                state.update { it.copy(healthRecovery = action.enabled, healthPreview = null) }
                            }
                            LabAction.PreviewHealth -> {
                                val value = state.value
                                if (value.data != null && !value.data.healthUnavailable && value.healthActive == null && !value.healthStarting) {
                                    state.update { it.copy(healthPreview = HealthRequest(UUID.randomUUID().toString(), value.healthInterval, value.healthRecovery), healthError = null) }
                                }
                            }
                            LabAction.DismissHealth -> state.update { it.copy(healthPreview = null) }
                            LabAction.ConfirmHealth -> {
                                val request = state.value.healthPreview ?: return@withLock
                                healthPendingId = request.id; healthPendingAt = elapsedClock()
                                state.update { it.copy(healthPreview = null, healthStarting = true, healthError = null) }
                                repository.sendHealth(request)
                            }
                            LabAction.StopHealth -> repository.sendHealth(null)
                            is LabAction.OpenHealth -> {
                                val id = action.id
                                if (id == null || state.value.data?.healthRuns?.any { it.id == id } == true) {
                                    saved["health_run_id"] = id
                                    state.update { it.copy(healthRunId = id) }
                                } else state.update { it.copy(healthError = R.string.health_error) }
                            }
                            LabAction.ShowHistory -> navigateHistory(true, null)
                            LabAction.ShowConfigs -> navigateHistory(false, null)
                            LabAction.CloseReport -> navigateHistory(true, null)
                            is LabAction.OpenReport -> {
                                if (state.value.data?.runs?.any { it.id == action.id } == true) navigateHistory(true, action.id)
                                else state.update { it.copy(error = R.string.lab_history_missing) }
                            }
                            is LabAction.SelectGroup -> if (state.value.active == null && !state.value.starting) {
                                if (state.value.data?.groups?.none { it.id == action.id } != false) return@withLock
                                saved["group_id"] = action.id
                                state.update { it.copy(groupId = action.id, data = null, loading = true, preview = null, error = null) }
                            }
                            LabAction.Preview -> {
                                val s = state.value
                                if (s.healthPage || s.historyPage || s.active != null || s.starting) return@withLock
                                if (!LabHttpProbe.validUrl(s.url)) { state.update { it.copy(error = R.string.lab_invalid_url) }; return@withLock }
                                val data = repository.load(s.groupId)
                                val profiles = data.profiles
                                val eligible = profiles.count { it.supported }
                                if (profiles.size > LabRecords.MAX_TARGETS || eligible == 0) {
                                    state.update { it.copy(data = data, error = if (eligible == 0) R.string.lab_no_supported else R.string.lab_too_many) }
                                } else state.update { it.copy(data = data, preview = LabPreview(
                                    LabStartRequest(UUID.randomUUID().toString(), s.groupId, s.url, profiles.map { p -> p.candidate }), eligible), error = null) }
                            }
                            LabAction.Confirm -> {
                                val preview = state.value.preview ?: return@withLock
                                pendingId = preview.request.id; pendingAt = elapsedClock()
                                state.update { it.copy(preview = null, starting = true, error = null) }
                                repository.send(preview.request)
                            }
                            LabAction.Cancel -> repository.send(null)
                            is LabAction.SetUrl, LabAction.Dismiss -> Unit
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        log("action", e); pendingId = null; healthPendingId = null
                        state.update { it.copy(error = R.string.lab_start_failed, starting = false, preview = null, healthStarting = false, healthPreview = null, healthError = R.string.health_error) }
                    }
                }
                refresh()
            }
        }
    }

    private fun navigateHistory(page: Boolean, id: String?) {
        saved["cdn_page"] = false
        saved["health_page"] = false
        saved["history_page"] = page
        saved["history_run_id"] = id
        historyNamesFor = null
        state.update { it.copy(cdnPage = false, healthPage = false, healthPreview = null, historyPage = page, historyRunId = id, historyNames = emptyMap(), historyGroups = emptyMap(), preview = null,
            error = it.error?.takeIf { error -> error == R.string.lab_history_error }) }
    }

    private fun log(operation: String, error: Exception) {
        reportFailure("$operation group=${state.value.groupId} report=${state.value.historyRunId.orEmpty()}", error)
    }
}
