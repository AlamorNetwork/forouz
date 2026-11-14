package com.v2ray.ang.ui.lab

import androidx.compose.foundation.layout.*
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
import com.v2ray.ang.core.lab.*
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.FrozPanel
import com.v2ray.ang.ui.compose.FrozSectionTitle
import com.v2ray.ang.ui.compose.FrozStatusLabel

/** Displays persisted rows by run ID, including rows whose current profile changed or disappeared. */
@Composable
internal fun LabHistoryScreen(state: LabUiState, onAction: (LabAction) -> Unit, onBack: () -> Unit) {
    val report = state.historyRun
    val colors = MaterialTheme.colorScheme
    val scrollStateHolder = rememberSaveableStateHolder()
    Scaffold(topBar = { AppTopBar(title = stringResource(if (report == null) R.string.lab_history else R.string.lab_history_report),
        onBackClick = onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            // Separate saved scroll positions for the list and each stable run ID.
            scrollStateHolder.SaveableStateProvider(report?.id ?: "history-list") {
                LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(if (report == null) stringResource(R.string.lab_history_scope, LabRecords.MAX_RUNS)
                            else stringResource(R.string.lab_history_note),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        state.error?.let { Text(stringResource(it), color = colors.error, modifier = Modifier.padding(top = 12.dp)) }
                    }
                    if (report == null) {
                        if (!state.loading && state.data != null && state.data.runs.isEmpty()) item {
                            FrozPanel { Text(stringResource(R.string.lab_history_empty)) }
                        }
                        items(state.data?.runs.orEmpty(), key = { it.id }) { run ->
                            HistorySummary(run, historyGroup(state, run.groupId)) {
                                TextButton(onClick = { onAction(LabAction.OpenReport(run.id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.lab_history_open))
                                }
                            }
                        }
                    } else {
                        item { HistorySummary(report, historyGroup(state, report.groupId)) }
                        state.historyGroups.forEach { (transport, rows) ->
                            item(key = "category-${transport.name}") {
                                FrozSectionTitle(stringResource(R.string.lab_category, stringResource(labTransportText(transport)), rows.size))
                            }
                            items(rows, key = { it.guid }) { row ->
                                val ordinal = report.rows.indexOfFirst { it.guid == row.guid } + 1
                                val name = state.historyNames[row.guid]?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.lab_history_profile, ordinal)
                                // Historical success remains historical: never substitute a current config's result/revision.
                                LabResultCard(name, row)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun historyGroup(state: LabUiState, id: String): String =
    state.data?.groups?.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.lab_history_old_group)

@Composable
private fun HistorySummary(run: LabRunRecord, group: String, action: @Composable ColumnScope.() -> Unit = {}) {
    FrozPanel {
        Text(stringResource(R.string.lab_tested_at, labDate(run.createdAt)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.lab_history_group, group), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.lab_run_summary, run.rows.count { it.status == LabRowStatus.SUCCESS },
            run.rows.size, run.rows.sumOf { it.bytes } / 1_000_000.0))
        Text(stringResource(when (run.networkKind) {
            "wifi" -> R.string.lab_network_wifi
            "cellular" -> R.string.lab_network_cellular
            "ethernet" -> R.string.lab_network_ethernet
            else -> R.string.lab_network_other
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FrozStatusLabel(stringResource(when (run.status) {
            LabRunStatus.RUNNING -> R.string.lab_running
            LabRunStatus.COMPLETE -> R.string.lab_complete
            LabRunStatus.CANCELLED -> R.string.lab_cancelled
            LabRunStatus.INTERRUPTED -> R.string.lab_interrupted
            LabRunStatus.FAILED -> R.string.lab_failed
        }), active = false)
        if (run.issue != LabIssue.NONE) Text(stringResource(labIssueText(run.issue)), style = MaterialTheme.typography.bodyMedium)
        action()
    }
}
