package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.privacy.*
import com.v2ray.ang.ui.compose.FrozPanel
import java.text.DateFormat
import java.util.Date

@Composable internal fun MainPrivacyCard(state: MainUiState, onAction: (MainAction) -> Unit) {
    val privacy = state.privacy
    val target = state.privacyTarget()
    val sample = privacy.sample?.takeIf { privacy.sampleTarget == target }
    val busy = privacy.requestId != null
    val now = maxOf(state.observedAt, sample?.elapsedAt ?: 0)
    val stale = sample != null && now - sample.elapsedAt > PRIVACY_SAMPLE_MAX_AGE
    val comparison = sample?.takeIf { it.vpn && !stale }?.let { compareDns(it, privacy.baseline, now) }
    FrozPanel {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.privacy_disclosure), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.privacy_running), style = MaterialTheme.typography.bodyMedium)
        }
        PrivacyAddress(R.string.privacy_ipv4, sample?.ipv4)
        PrivacyAddress(R.string.privacy_ipv6, sample?.ipv6)
        Text(stringResource(R.string.privacy_dns), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(when {
            busy -> R.string.privacy_running
            stale -> R.string.privacy_stale
            sample?.proxyPath == true -> R.string.privacy_proxy_dns_unavailable
            sample != null && sample.resolvers.isEmpty() -> R.string.privacy_dns_unavailable
            sample != null && !sample.vpn -> R.string.privacy_baseline_captured
            comparison == DnsComparison.POSSIBLE_BYPASS -> R.string.privacy_dns_possible
            comparison == DnsComparison.NO_MATCH_IN_SAMPLE -> R.string.privacy_dns_no_match
            comparison == DnsComparison.BASELINE_EXPIRED -> R.string.privacy_baseline_expired
            comparison == DnsComparison.DIFFERENT_NETWORK -> R.string.privacy_network_different
            comparison == DnsComparison.UNAVAILABLE -> R.string.privacy_dns_unavailable
            comparison == DnsComparison.NO_BASELINE -> R.string.privacy_need_baseline
            privacy.cancelled -> R.string.privacy_cancelled
            else -> R.string.privacy_not_tested
        }), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        sample?.resolvers?.takeIf { it.isNotEmpty() }?.let { ips ->
            Text(ips.sorted().joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
        }
        if (comparison == DnsComparison.POSSIBLE_BYPASS || comparison == DnsComparison.NO_MATCH_IN_SAMPLE) {
            Text(stringResource(R.string.privacy_dns_limit), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sample != null) {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sample.measuredAt))
            Text(stringResource(if (sample.proxyPath) R.string.privacy_proxy_sample
                else if (sample.vpn) R.string.privacy_vpn_sample else R.string.privacy_direct_sample, time),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        privacy.issue?.let { issue ->
            Text(stringResource(when (issue) {
                PrivacyIssue.UNSUPPORTED -> R.string.privacy_route_unsupported
                PrivacyIssue.VPN_UNAVAILABLE -> R.string.privacy_vpn_unavailable
                PrivacyIssue.DISCONNECT_FIRST -> R.string.privacy_disconnect_first
                PrivacyIssue.NETWORK_CHANGED -> R.string.privacy_network_changed
                PrivacyIssue.FAILED -> R.string.privacy_failed
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (busy) {
            OutlinedButton(onClick = { onAction(MainAction.CancelPrivacyCheck) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        } else {
            OutlinedButton(onClick = {
                onAction(if (target?.vpn == true) MainAction.RunPrivacyCheck else MainAction.RequestPrivacyBaseline)
            }, enabled = target != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (target?.vpn == true) R.string.privacy_check else R.string.privacy_capture_baseline))
            }
        }
        if (privacy.baseline != null) {
            TextButton(onClick = { onAction(MainAction.ClearPrivacyBaseline) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.privacy_clear))
            }
        }
    }
    if (privacy.showBaselineConfirmation) AlertDialog(
        onDismissRequest = { onAction(MainAction.DismissPrivacyBaseline) },
        title = { Text(stringResource(R.string.privacy_capture_baseline)) },
        text = { Text(stringResource(R.string.privacy_baseline_consent)) },
        confirmButton = { TextButton(onClick = { onAction(MainAction.ConfirmPrivacyBaseline) }) { Text(stringResource(R.string.privacy_capture)) } },
        dismissButton = { TextButton(onClick = { onAction(MainAction.DismissPrivacyBaseline) }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable private fun PrivacyAddress(label: Int, address: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
        Text(address ?: stringResource(R.string.privacy_address_unavailable), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = if (address != null) TextDirection.Ltr else TextDirection.Content))
    }
}
