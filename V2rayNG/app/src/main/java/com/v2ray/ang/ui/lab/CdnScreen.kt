package com.v2ray.ang.ui.lab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.cdn.*
import com.v2ray.ang.core.lab.LabIssue
import com.v2ray.ang.core.lab.LabTlsFailure
import com.v2ray.ang.ui.compose.*

@Composable internal fun LabToolsTabs(cdn: Boolean, onStress: () -> Unit, onCdn: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !cdn, onClick = onStress, label = { Text(stringResource(R.string.cdn_stress_tab)) }, modifier = Modifier.weight(1f))
        FilterChip(selected = cdn, onClick = onCdn, label = { Text(stringResource(R.string.cdn_title)) }, modifier = Modifier.weight(1f))
    }
}

@Composable internal fun CdnScreen(model: CdnViewModel, onBack: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val action = model::onAction
    val run = state.live?.run
    var profilesExpanded by remember { mutableStateOf(false) }
    Scaffold(topBar = { Column {
        AppTopBar(title = stringResource(R.string.lab_title), onBackClick = onBack)
        LabToolsTabs(true, onBack, {})
    } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.cdn_edge_intro), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { FrozPanel {
                FrozSectionTitle(stringResource(R.string.cdn_feed_title))
                Text(state.data?.feedAt?.let { stringResource(R.string.cdn_feed_at, labDate(it)) } ?: stringResource(R.string.cdn_need_feed))
                state.data?.feedIssue?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(value = state.feedUrl, onValueChange = { action(CdnAction.EditFeedUrl(it)) },
                    enabled = !state.active && !state.busy, singleLine = true, label = { Text(stringResource(R.string.cdn_source_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { action(CdnAction.SaveFeedUrl) }, enabled = !state.active && !state.busy && state.feedUrl != state.data?.feedUrl) {
                        Text(stringResource(R.string.cdn_source_save))
                    }
                    TextButton(onClick = { action(CdnAction.ResetFeedUrl) }, enabled = !state.active && !state.busy) {
                        Text(stringResource(R.string.cdn_source_default))
                    }
                }
                Text(stringResource(R.string.cdn_source_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { action(CdnAction.Update) }, enabled = !state.active && !state.busy && state.feedUrl == (state.data?.feedUrl ?: CdnPolicy.FEED),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.cdn_update)) }
                Text(stringResource(R.string.cdn_source_privacy), style = MaterialTheme.typography.bodySmall)
            } }
            item { FrozPanel {
                FrozSectionTitle(stringResource(R.string.cdn_setup))
                Box {
                    OutlinedButton(onClick = { profilesExpanded = true }, enabled = !state.active && !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(state.data?.profiles?.firstOrNull { it.guid == state.guid }?.name ?: stringResource(R.string.cdn_select))
                    }
                    DropdownMenu(expanded = profilesExpanded, onDismissRequest = { profilesExpanded = false }) {
                        state.data?.profiles.orEmpty().forEach { profile -> key(profile.guid) {
                            DropdownMenuItem(text = { Text(profile.name) }, onClick = { profilesExpanded = false; action(CdnAction.Select(profile.guid)) })
                        } }
                    }
                }
                Text(stringResource(R.string.cdn_edge_eligibility), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = state.manualIps, onValueChange = { action(CdnAction.ManualIps(it)) },
                    enabled = !state.active && !state.busy, minLines = 3, maxLines = 6,
                    label = { Text(stringResource(R.string.cdn_manual_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.cdn_manual_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cdn_edge_branch), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(32, 64, 128, 512).forEach { budget ->
                        FilterChip(selected = state.budget == budget, onClick = { action(CdnAction.Budget(budget)) },
                            enabled = !state.active && !state.busy && state.manualIps.isBlank(), label = { Text(stringResource(R.string.cdn_budget, budget)) })
                    }
                }
                if (state.data?.profiles?.isEmpty() == true) Text(stringResource(R.string.cdn_empty))
                if (state.busy || state.starting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(if (state.errorCode != null) stringResource(it, state.errorCode!!) else stringResource(it),
                    color = MaterialTheme.colorScheme.error) }
                if (state.active) {
                    FrozStatusLabel(stringResource(if (state.stale) R.string.lab_stale else R.string.lab_running), active = !state.stale)
                    Text(stringResource(R.string.lab_progress, run?.rows?.size ?: 0, (run?.request?.budget ?: state.budget) + if (run?.request?.edgeScan == false) 1 else 0))
                    OutlinedButton(onClick = { action(CdnAction.Cancel) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                } else Button(onClick = { action(CdnAction.Preview) }, enabled = !state.busy && state.guid.isNotBlank() && (state.manualIps.isNotBlank() || state.data?.feedAt != null),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.cdn_preview)) }
            } }
            if (run != null) {
                item { FrozPanel {
                    FrozSectionTitle(stringResource(R.string.cdn_results))
                    Text(state.data?.profiles?.firstOrNull { it.guid == run.request.guid }?.name ?: stringResource(R.string.cdn_changed))
                    Text(stringResource(R.string.cdn_provider, if (run.request.manual) stringResource(R.string.cdn_manual_provider) else run.request.provider))
                    Text(stringResource(if (run.request.edgeScan) R.string.cdn_edge_count else R.string.cdn_count, run.rows.size, run.request.budget + if (run.request.edgeScan) 0 else 1), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.lab_tested_at, labDate(run.updatedAt)), style = MaterialTheme.typography.bodySmall)
                    FrozStatusLabel(stringResource(when (run.status) {
                        CdnStatus.RUNNING -> R.string.lab_running
                        CdnStatus.COMPLETE -> R.string.lab_complete
                        CdnStatus.CANCELLED -> R.string.lab_cancelled
                        CdnStatus.INTERRUPTED -> R.string.lab_interrupted
                        CdnStatus.FAILED -> R.string.lab_failed
                    }), active = run.status == CdnStatus.COMPLETE)
                    if (run.issue != LabIssue.NONE) Text(stringResource(labIssueText(run.issue)))
                    Text(stringResource(if (run.request.edgeScan) R.string.cdn_edge_note else if (run.rows.any { it.nativeSamples != null }) R.string.cdn_native_metric_note
                        else R.string.cdn_metric_note), style = MaterialTheme.typography.bodySmall)
                } }
                if (run.request.edgeScan) item {
                    OutlinedButton(onClick = { action(CdnAction.Copy(run.request.id)) },
                        enabled = !state.busy && run.edgeAddresses().isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cdn_edge_copy_all))
                    }
                    if (state.copied) Text(stringResource(R.string.cdn_edge_copied))
                }
                items(if (run.request.edgeScan) run.edgeRows() else run.rows, key = { run.request.id + ":" + (it.address ?: "baseline") }) { row -> FrozPanel {
                    Text(row.address ?: stringResource(R.string.cdn_baseline), style = MaterialTheme.typography.titleMedium)
                    if (row.edge != null) {
                        FrozStatusLabel(stringResource(if (row.edge.reachable()) R.string.cdn_edge_reachable else R.string.cdn_edge_unreachable),
                            active = row.edge.reachable())
                        listOf(R.string.cdn_edge_tcp80 to row.edge.tcp80, R.string.cdn_edge_tcp443 to row.edge.tcp443,
                            R.string.cdn_edge_tls to row.edge.tls).forEach { (labelId, check) ->
                            val label = stringResource(labelId)
                            Text(when {
                                check.skipped -> stringResource(R.string.cdn_edge_skipped, label)
                                check.passed() -> stringResource(R.string.cdn_edge_pass, label, requireNotNull(check.millis))
                                else -> stringResource(R.string.cdn_edge_fail, label, check.tls?.name ?: check.issue.name)
                            })
                        }
                        if (row.edge.reachable() && row.address != null) OutlinedButton(
                            onClick = { action(CdnAction.Copy(run.request.id, row.address)) }, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cdn_edge_copy)) }
                    } else FrozStatusLabel(stringResource(if (row.usable(run.request.download)) R.string.lab_success else R.string.lab_failed),
                        active = row.usable(run.request.download))
                    row.median?.let { Text(stringResource(if (row.nativeSamples != null) R.string.cdn_native_metrics
                        else R.string.cdn_metrics, it, row.variation ?: 0, row.failures)) }
                    if (row.downloadPassed() && row.elapsedMillis != null) Text(stringResource(R.string.cdn_speed,
                        row.bytes * 8.0 / row.elapsedMillis.coerceAtLeast(1) / 1000))
                    if (row.issue != LabIssue.NONE) Text(stringResource(row.tlsFailure?.let(::cdnTlsText) ?: labIssueText(row.issue)))
                    row.nativeFailure?.let { Text(stringResource(R.string.cdn_native_failure, it.name), style = MaterialTheme.typography.bodySmall) }
                    row.baselineControls?.let { results ->
                        Text(stringResource(R.string.cdn_baseline_controls_note), style = MaterialTheme.typography.bodySmall)
                        results.forEach { result ->
                            val label = stringResource(when (result.mode) {
                                CdnControlMode.BOUND -> R.string.cdn_control_bound
                                CdnControlMode.DEFAULT_NETWORK -> R.string.cdn_control_default
                                CdnControlMode.BOUND_TCP -> R.string.cdn_control_tcp
                            })
                            Text(when {
                                result.tcpMillis == -1L -> stringResource(R.string.cdn_control_tcp_failed, label)
                                result.millis >= 0 -> stringResource(R.string.cdn_control_pass, label, result.millis)
                                else -> stringResource(R.string.cdn_control_fail, label, requireNotNull(result.failure).name)
                            })
                        }
                    }
                    row.downloadIssue?.let { issue ->
                        Text(stringResource(R.string.cdn_download_result, stringResource(row.downloadTlsFailure?.let(::cdnTlsText)
                            ?: if (issue == LabIssue.NONE) R.string.lab_success else labIssueText(issue))))
                    }
                    if (row.issue == LabIssue.TLS) Text(stringResource(R.string.cdn_tls_note), style = MaterialTheme.typography.bodySmall)
                    row.http1Control?.let { issue ->
                        Text(stringResource(R.string.cdn_http1_control,
                            stringResource(if (issue == LabIssue.NONE) R.string.lab_success else labIssueText(issue))))
                    }
                    row.nativeControlMillis?.let { duration ->
                        Text(if (duration >= 0) stringResource(R.string.cdn_native_control_pass, duration)
                            else stringResource(R.string.cdn_native_control_fail))
                        Text(stringResource(R.string.cdn_control_note), style = MaterialTheme.typography.bodySmall)
                    }
                } }
                if (!run.request.edgeScan && run.status == CdnStatus.COMPLETE) item { FrozPanel {
                    FrozSectionTitle(stringResource(R.string.cdn_recommendation))
                    Text(if (run.best() == null) stringResource(R.string.cdn_no_winner)
                        else run.best()?.address ?: stringResource(R.string.cdn_keep_baseline))
                    Text(stringResource(R.string.cdn_save_network), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { action(CdnAction.Save) }, enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.cdn_save)) }
                    if (state.saved) Text(stringResource(R.string.cdn_saved), color = MaterialTheme.colorScheme.tertiary)
                } }
            }
        }
    }
    state.preview?.let { preview -> AlertDialog(onDismissRequest = { action(CdnAction.Dismiss) },
        title = { Text(stringResource(R.string.cdn_confirm_title)) },
        text = { Text(if (preview.edgeScan) stringResource(R.string.cdn_edge_confirm,
            if (preview.manual) stringResource(R.string.cdn_manual_provider) else preview.provider, preview.budget)
            else stringResource(R.string.cdn_confirm_body, preview.provider, preview.budget + 1,
                if (preview.download) (preview.budget + 1) * 10 else 0)) },
        confirmButton = { TextButton(onClick = { action(CdnAction.Confirm) }) { Text(stringResource(R.string.cdn_start)) } },
        dismissButton = { TextButton(onClick = { action(CdnAction.Dismiss) }) { Text(stringResource(R.string.action_cancel)) } }) }
}

internal fun cdnTlsText(issue: LabTlsFailure): Int = when (issue) {
    LabTlsFailure.EXPIRED -> R.string.cdn_tls_expired
    LabTlsFailure.NOT_YET_VALID -> R.string.cdn_tls_future
    LabTlsFailure.CHAIN -> R.string.cdn_tls_chain
    LabTlsFailure.CERTIFICATE -> R.string.cdn_tls_certificate
    LabTlsFailure.PEER -> R.string.cdn_tls_peer
    LabTlsFailure.HANDSHAKE -> R.string.cdn_tls_handshake
    LabTlsFailure.PROTOCOL -> R.string.cdn_tls_protocol
    LabTlsFailure.OTHER -> R.string.cdn_tls_other
}
