package com.v2ray.ang.ui.lab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.health.*
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FrozPanel
import com.v2ray.ang.ui.compose.FrozSectionTitle

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HealthScreen(state: LabUiState, action: (LabAction) -> Unit, onBack: () -> Unit) {
    val selected = state.healthSelected
    val running = state.healthActive
    val scroll = rememberSaveableStateHolder()
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.health_title), onBackClick = onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            scroll.SaveableStateProvider(selected?.id ?: "health-list") {
                LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(stringResource(R.string.health_intro), style = MaterialTheme.typography.bodyMedium)
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        (state.healthError ?: state.error)?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    }
                    if (selected == null) {
                        item {
                            FrozPanel {
                                FrozSectionTitle(stringResource(R.string.health_setup))
                                Text(stringResource(R.string.health_interval))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(30, 60, 120).forEach { seconds ->
                                        FilterChip(selected = state.healthInterval == seconds,
                                            onClick = { action(LabAction.HealthInterval(seconds)) },
                                            enabled = running == null && !state.healthStarting,
                                            label = { Text(stringResource(R.string.health_seconds, seconds)) })
                                    }
                                }
                                // One Material toggle node; no duplicate clickable row/switch semantics.
                                FilterChip(selected = state.healthRecovery,
                                    onClick = { action(LabAction.HealthRecovery(!state.healthRecovery)) },
                                    enabled = running == null && !state.healthStarting,
                                    label = { Text(stringResource(R.string.health_auto)) })
                                Text(stringResource(R.string.health_scope), style = MaterialTheme.typography.bodySmall)
                                if (running != null || state.healthStarting) {
                                    Text(stringResource(if (state.healthStale) R.string.health_stale else R.string.health_running))
                                    OutlinedButton(onClick = { action(LabAction.StopHealth) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                        Text(stringResource(R.string.health_stop))
                                    }
                                } else Button(onClick = { action(LabAction.PreviewHealth) },
                                    enabled = !state.loading && state.data != null && state.data.healthUnavailable == false,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.health_start)) }
                            }
                        }
                        item { FrozSectionTitle(stringResource(R.string.health_history)) }
                        if (!state.loading && state.data?.healthRuns.isNullOrEmpty()) item { Text(stringResource(R.string.health_empty)) }
                        items(state.data?.healthRuns.orEmpty(), key = { it.id }) { run ->
                            FrozPanel {
                                HealthSummary(run)
                                TextButton(onClick = { action(LabAction.OpenHealth(run.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.lab_history_open))
                                }
                            }
                        }
                    } else {
                        item { FrozPanel { HealthSummary(selected) } }
                        item { Text(stringResource(R.string.health_event_note), style = MaterialTheme.typography.bodySmall) }
                        items(selected.events.reversed()) { event ->
                            FrozPanel {
                                Text(labDate(event.at), style = MaterialTheme.typography.labelMedium)
                                Text(stringResource(healthPhaseText(event.phase)), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(when (event.network) {
                                    "wifi" -> R.string.health_wifi
                                    "cellular" -> R.string.health_cellular
                                    "ethernet" -> R.string.health_ethernet
                                    else -> R.string.health_other
                                }))
                                event.delayMillis?.let { Text(stringResource(R.string.health_latency, it)) }
                            }
                        }
                    }
                }
            }
        }
    }
    state.healthPreview?.let { request ->
        AlertDialog(onDismissRequest = { action(LabAction.DismissHealth) }, title = { Text(stringResource(R.string.health_start)) },
            text = { Text(stringResource(if (request.recover) R.string.health_confirm_recovery else R.string.health_confirm,
                request.intervalSeconds), modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = {
                TextButton(onClick = { action(LabAction.ConfirmHealth) }) { Text(stringResource(R.string.health_start)) }
            }, dismissButton = { TextButton(onClick = { action(LabAction.DismissHealth) }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable private fun HealthSummary(run: HealthRun) {
    Text(stringResource(R.string.lab_tested_at, labDate(run.createdAt)), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(when (run.status) {
        HealthStatus.RUNNING -> R.string.health_running_record
        HealthStatus.ENDED -> R.string.health_ended
        HealthStatus.CANCELLED -> R.string.health_cancelled
        HealthStatus.INTERRUPTED -> R.string.health_interrupted
        HealthStatus.ERROR -> R.string.health_error
    }))
    Text(stringResource(R.string.health_counts, run.checks, run.failedChecks, run.requests, run.attempts))
    run.events.lastOrNull()?.let { Text(stringResource(healthPhaseText(it.phase))) }
}

private fun healthPhaseText(phase: HealthPhase): Int = when (phase) {
    HealthPhase.WAITING_VPN -> R.string.health_wait_vpn
    HealthPhase.WAITING_NETWORK -> R.string.health_wait_network
    HealthPhase.GRACE -> R.string.health_grace
    HealthPhase.HEALTHY -> R.string.health_ok
    HealthPhase.FAILED_CHECK -> R.string.health_failed_check
    HealthPhase.UNSUPPORTED -> R.string.health_unsupported
    HealthPhase.CHANGED -> R.string.health_changed
    HealthPhase.RECOVERY -> R.string.health_recovery
    HealthPhase.RECOVERED -> R.string.health_recovered
    HealthPhase.RECOVERY_SKIPPED -> R.string.health_skipped
    HealthPhase.RECOVERY_FAILED -> R.string.health_recovery_failed
    HealthPhase.FINISHED -> R.string.health_finished
}
