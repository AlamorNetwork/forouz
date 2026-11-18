package com.v2ray.ang.ui.lab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.lab.*
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FrozPanel
import com.v2ray.ang.ui.compose.FrozSectionTitle
import com.v2ray.ang.ui.compose.FrozStatusLabel

@Composable internal fun LabScreen(model: LabViewModel, cdn: CdnViewModel, onBack: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val action = model::onAction
    BackHandler(enabled = state.cdnPage) { action(LabAction.ShowConfigs) }
    if (state.cdnPage) {
        CdnScreen(cdn) { action(LabAction.ShowConfigs) }
        return
    }
    val historyBack: () -> Unit = {
        action(if (state.historyRunId != null) LabAction.CloseReport else LabAction.ShowConfigs)
    }
    val healthBack: () -> Unit = { action(if (state.healthRunId != null) LabAction.OpenHealth(null) else LabAction.ShowConfigs) }
    BackHandler(enabled = state.healthPage, onBack = healthBack)
    if (state.healthPage) {
        HealthScreen(state, action, healthBack)
        return
    }
    BackHandler(enabled = state.historyPage, onBack = historyBack)
    if (state.historyPage) {
        LabHistoryScreen(state, action, historyBack)
        return
    }
    var groupsExpanded by remember { mutableStateOf(false) }
    val profiles = state.data?.profiles.orEmpty()
    val latest = state.latest
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = { Column {
        AppTopBar(title = stringResource(R.string.lab_title), onBackClick = onBack)
        LabToolsTabs(false, {}, { action(LabAction.ShowCdn) })
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth().imePadding(),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(stringResource(R.string.lab_intro), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                    FilledTonalButton(onClick = { action(LabAction.ShowHealth) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.health_title))
                    }
                    OutlinedButton(onClick = { action(LabAction.ShowHistory) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.lab_history_browse))
                    }
                }
                item {
                    FrozPanel {
                        FrozSectionTitle(stringResource(R.string.froz_lab_setup))
                        Box {
                            OutlinedButton(onClick = { groupsExpanded = true },
                                enabled = state.active == null && !state.starting,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Text(state.data?.groups?.firstOrNull { it.id == state.groupId }?.name
                                    ?: stringResource(R.string.filter_config_all), Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Icon(painterResource(R.drawable.ic_expand_more_24dp), null)
                            }
                            DropdownMenu(expanded = groupsExpanded, onDismissRequest = { groupsExpanded = false }) {
                                state.data?.groups.orEmpty().forEach { group ->
                                    key(group.id) { DropdownMenuItem(text = { Text(group.name) }, onClick = {
                                        groupsExpanded = false; action(LabAction.SelectGroup(group.id))
                                    }) }
                                }
                            }
                        }
                        OutlinedTextField(value = state.url, onValueChange = { action(LabAction.SetUrl(it)) },
                            label = { Text(stringResource(R.string.lab_url)) }, singleLine = false,
                            isError = state.error == R.string.lab_invalid_url,
                            enabled = state.active == null && !state.starting, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.lab_url_note), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                        state.error?.let { Text(stringResource(it), color = colors.error, style = MaterialTheme.typography.bodyMedium) }
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.active != null || state.starting) {
                            val progress = state.active ?: latest
                            val completed = progress?.rows?.count { it.status !in listOf(LabRowStatus.WAITING, LabRowStatus.TESTING) } ?: 0
                            FrozStatusLabel(stringResource(if (state.stale) R.string.lab_stale else R.string.lab_running),
                                active = !state.stale, icon = R.drawable.ic_lab_24dp)
                            Text(stringResource(R.string.lab_progress, completed, progress?.rows?.size ?: 0))
                            if (progress != null && progress.rows.isNotEmpty()) {
                                LinearProgressIndicator(progress = { completed.toFloat() / progress.rows.size }, modifier = Modifier.fillMaxWidth())
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { action(LabAction.Cancel) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.action_cancel)) }
                        } else {
                            Button(onClick = { action(LabAction.Preview) }, enabled = !state.loading &&
                                state.error != R.string.lab_history_error && profiles.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Text(stringResource(R.string.lab_test_round))
                            }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.lab_limits), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (!state.loading && profiles.isEmpty()) item { FrozPanel { Text(stringResource(R.string.lab_empty)) } }
                if (profiles.isNotEmpty()) item { FrozSectionTitle(stringResource(R.string.froz_lab_results)) }
                profiles.groupBy { it.candidate.transport }.toSortedMap(compareBy { it.ordinal }).forEach { (transport, members) ->
                    item(key = "category-${transport.name}") {
                        FrozSectionTitle(stringResource(R.string.lab_category, stringResource(labTransportText(transport)), members.size),
                            Modifier.padding(top = 8.dp))
                    }
                    items(members, key = { it.candidate.guid }) { profile ->
                        val original = latest?.rows?.firstOrNull { it.guid == profile.candidate.guid }
                        val row = original?.let { if (it.revision != profile.candidate.revision)
                            it.copy(status = LabRowStatus.SKIPPED, issue = LabIssue.CHANGED) else it }
                        LabResultCard(profile.name, row, profile.supported)
                    }
                }
                if (state.data?.runs.orEmpty().any { it.groupId == state.groupId }) item {
                    FrozSectionTitle(stringResource(R.string.lab_history), Modifier.padding(top = 8.dp))
                }
                items(state.data?.runs.orEmpty().filter { it.groupId == state.groupId }, key = { "run-${it.id}" }) { run ->
                    val good = run.rows.count { it.status == LabRowStatus.SUCCESS }
                    val payload = run.rows.sumOf { it.bytes } / 1_000_000.0
                    FrozPanel {
                        Text(stringResource(R.string.lab_tested_at, labDate(run.createdAt)), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        Text(stringResource(R.string.lab_run_summary, good, run.rows.size, payload),
                            style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when (run.networkKind) {
                            "wifi" -> R.string.lab_network_wifi
                            "cellular" -> R.string.lab_network_cellular
                            "ethernet" -> R.string.lab_network_ethernet
                            else -> R.string.lab_network_other
                        }), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        FrozStatusLabel(stringResource(when (run.status) {
                            LabRunStatus.RUNNING -> R.string.lab_running
                            LabRunStatus.COMPLETE -> R.string.lab_complete
                            LabRunStatus.CANCELLED -> R.string.lab_cancelled
                            LabRunStatus.INTERRUPTED -> R.string.lab_interrupted
                            LabRunStatus.FAILED -> R.string.lab_failed
                        }), active = run.status == LabRunStatus.COMPLETE)
                        if (run.issue != LabIssue.NONE) Text(stringResource(labIssueText(run.issue)), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { action(LabAction.OpenReport(run.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.lab_history_open))
                        }
                    }
                }
            }
        }
    }
    state.preview?.let { preview ->
        AlertDialog(onDismissRequest = { action(LabAction.Dismiss) }, title = { Text(stringResource(R.string.lab_confirm_title)) },
            text = { Text(stringResource(R.string.lab_confirm_body, preview.eligible,
                LabPlan.estimatedDownloadBytes(preview.eligible) / 1_000_000)) },
            confirmButton = { TextButton(onClick = { action(LabAction.Confirm) }) { Text(stringResource(R.string.lab_confirm)) } },
            dismissButton = { TextButton(onClick = { action(LabAction.Dismiss) }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable
internal fun LabResultCard(name: String, row: LabRow?, supported: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    FrozPanel {
        Text(name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
        FrozStatusLabel(stringResource(labRowText(row, supported)), active = row?.status == LabRowStatus.SUCCESS,
            icon = if (row?.status == LabRowStatus.SUCCESS) R.drawable.ic_action_done else null)
        if (row?.status == LabRowStatus.SUCCESS && row.elapsedMillis != null && row.responseMillis != null) {
            val mbps = row.bytes.toDouble() * 8 / row.elapsedMillis.coerceAtLeast(1) / 1000
            Text(stringResource(R.string.lab_metrics, row.responseMillis, mbps), style = MaterialTheme.typography.bodyLarge)
        } else if (row != null && row.issue != LabIssue.NONE) {
            Text(stringResource(labIssueText(row.issue)), style = MaterialTheme.typography.bodyMedium,
                color = if (row.status == LabRowStatus.FAILED) colors.error else colors.onSurfaceVariant)
        }
        row?.testedAt?.let { Text(stringResource(R.string.lab_tested_at, labDate(it)),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}

internal fun labDate(time: Long): String = java.text.DateFormat.getDateTimeInstance(
    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(time))

internal fun labTransportText(transport: LabTransport): Int = when (transport) {
    LabTransport.XHTTP -> R.string.lab_type_xhttp
    LabTransport.WS -> R.string.lab_type_ws
    LabTransport.GRPC -> R.string.lab_type_grpc
    LabTransport.RAW -> R.string.lab_type_raw
    LabTransport.HTTP_UPGRADE -> R.string.lab_type_upgrade
    LabTransport.HTTP2 -> R.string.lab_type_http2
    LabTransport.KCP -> R.string.lab_type_kcp
    LabTransport.HYSTERIA -> R.string.lab_type_hysteria
    LabTransport.WIREGUARD -> R.string.lab_type_wireguard
    LabTransport.AMNEZIAWG -> R.string.lab_type_awg
    LabTransport.COMPOSITE -> R.string.lab_type_composite
    LabTransport.OTHER -> R.string.lab_type_other
}

private fun labRowText(row: LabRow?, supported: Boolean): Int = when {
    row == null -> if (supported) R.string.lab_not_tested else R.string.lab_skipped
    else -> when (row.status) {
        LabRowStatus.WAITING -> R.string.lab_waiting
        LabRowStatus.TESTING -> R.string.lab_running
        LabRowStatus.SUCCESS -> R.string.lab_success
        LabRowStatus.FAILED -> R.string.lab_failed
        LabRowStatus.SKIPPED -> R.string.lab_skipped
        LabRowStatus.CANCELLED -> R.string.lab_cancelled
    }
}

internal fun labIssueText(issue: LabIssue): Int = when (issue) {
    LabIssue.UNSUPPORTED -> R.string.lab_issue_unsupported
    LabIssue.CHANGED -> R.string.lab_issue_changed
    LabIssue.NETWORK -> R.string.lab_issue_network
    LabIssue.CONFIG -> R.string.lab_issue_config
    LabIssue.HTTP -> R.string.lab_issue_http
    LabIssue.BODY -> R.string.lab_issue_body
    LabIssue.TLS -> R.string.lab_issue_tls
    LabIssue.TIMEOUT -> R.string.lab_issue_timeout
    LabIssue.IO -> R.string.lab_issue_io
    LabIssue.STORAGE -> R.string.lab_history_error
    LabIssue.NONE -> R.string.lab_not_tested
}
