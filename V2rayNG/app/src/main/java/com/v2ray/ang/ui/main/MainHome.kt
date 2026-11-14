package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FrozPanel
import com.v2ray.ang.ui.compose.FrozSectionTitle
import com.v2ray.ang.ui.compose.FrozStatusLabel
import com.v2ray.ang.ui.compose.ForouzBrand
import java.text.DateFormat
import java.util.Date

/** A service observation and a dated HTTPS observation are intentionally separate. */
@Composable internal fun MainHome(state: MainUiState, isLoading: Boolean, displayText: String,
    onAction: (MainAction) -> Unit, onOpenConfigs: () -> Unit,
    onNavigate: (MainDestination) -> Unit, onSwitchProfile: () -> Unit, modifier: Modifier = Modifier) {
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        onAction(MainAction.PrivacyOverviewVisible(true))
        onStopOrDispose { onAction(MainAction.PrivacyOverviewVisible(false)) }
    }
    val colors = MaterialTheme.colorScheme
    val phase = state.overviewPhase()
    val profile = state.overviewProfile()
    val verified = phase == OverviewPhase.VERIFIED
    val check = state.connectivity?.takeIf { it.belongsTo(state.connection.snapshot) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 660.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FrozStatusLabel(stringResource(overviewStatusText(phase)), active = verified,
                icon = if (phase in listOf(OverviewPhase.ERROR, OverviewPhase.CHECK_FAILED)) R.drawable.ic_forouz_error
                    else if (verified) R.drawable.ic_action_done else R.drawable.ic_power_24dp)
            Text(stringResource(when {
                phase == OverviewPhase.OBSERVING -> R.string.forouz_observing
                verified -> R.string.forouz_route_live
                state.isRunning -> R.string.forouz_tunnel_active
                else -> R.string.forouz_ready
            }), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            RouteStatus(state.isRunning && state.connectionKnown, verified, !state.connectionKnown)
            Surface(onClick = onOpenConfigs, shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (state.isRunning && !state.connectionKnown) R.string.forouz_last_profile
                        else if (state.isRunning) R.string.forouz_active_profile else R.string.lab_selected_profile),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(profile?.name?.ifBlank { profile.protocol } ?: stringResource(if (state.isRunning)
                        R.string.forouz_profile_loading else R.string.lab_choose_profile), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    profile?.let { Text(it.protocol, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        color = colors.onSurfaceVariant) }
                    if (state.isRunning) {
                        Text(check?.let { if (it.delayMillis >= 0) stringResource(R.string.forouz_https_latency, it.delayMillis)
                            else stringResource(R.string.forouz_check_failed) } ?: stringResource(R.string.forouz_not_verified),
                            color = if (verified) colors.tertiary else colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        check?.let {
                            if (!it.fresh(state.observedAt)) Text(stringResource(R.string.forouz_stale_check), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                            val locale = LocalConfiguration.current.locales[0]
                            val time = remember(it.completedAt, locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(it.completedAt)) }
                            Text(stringResource(R.string.forouz_checked_at, time), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                    } else profile?.delayMillis?.let {
                        Text(stringResource(R.string.forouz_saved_latency, it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            MainPrivacyCard(state, onAction)
            OverviewMetrics(state)
            if (state.isRunning && state.selectedGuid != state.activeTestProfileGuid() && state.homeProfile != null) {
                FrozPanel {
                    Text(stringResource(R.string.forouz_next_profile, state.homeProfile.name), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onSwitchProfile, enabled = state.pendingCommand == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.forouz_switch_profile)) }
                }
            }
            if (phase == OverviewPhase.ERROR || state.connectionIssue != null) {
                Text(stringResource(when (state.connectionIssue) {
                    MainConnectionIssue.PERMISSION -> R.string.forouz_permission_denied
                    MainConnectionIssue.START_FAILED -> R.string.forouz_start_failed
                    else -> R.string.forouz_unconfirmed_command
                }), color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            FrozSectionTitle(stringResource(R.string.forouz_tools))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(state.connectionTestAction()) },
                    enabled = state.isCurrentTesting || state.canTestConnection(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(painterResource(R.drawable.ic_fab_check), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (state.isCurrentTesting) R.string.action_cancel else R.string.lab_test_active_connection))
                }
                OutlinedButton(onClick = { onNavigate(MainDestination.Logcat) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(painterResource(R.drawable.ic_logcat_24dp), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.forouz_view_logs))
                }
                OutlinedButton(onClick = { onNavigate(MainDestination.Laboratory) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(painterResource(R.drawable.ic_lab_24dp), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.lab_title))
                }
                if (profile?.isAmnezia == true) TextButton(onClick = { onAction(MainAction.CopyAmneziaNative(profile.guid)) },
                    enabled = !isLoading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.awg_copy_native)) }
            }
            if (state.status is MainStatus.TestProgress) Text(displayText, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onNavigate(MainDestination.Routing) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(painterResource(R.drawable.ic_routing_24dp), null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.routing_settings_title))
            }
            if (state.isRunning && state.connectionKnown) FrozPanel {
                FrozSectionTitle(stringResource(R.string.froz_traffic_title))
                MainTraffic(state.connection, Modifier.fillMaxWidth(), showSummary = false)
            }
        }
    }
}

@Composable internal fun OverviewConnectionAction(state: MainUiState, phase: OverviewPhase, ready: Boolean,
    onImport: () -> Unit, action: (MainAction) -> Unit) {
    val empty = phase == OverviewPhase.EMPTY
    val stopPending = phase == OverviewPhase.DISCONNECTING
    val observing = phase == OverviewPhase.OBSERVING && !state.isRunning
    val starting = phase in listOf(OverviewPhase.CONNECTING, OverviewPhase.RECONNECTING)
    val title = stringResource(when {
        observing -> R.string.forouz_observing
        empty -> R.string.forouz_import
        stopPending -> R.string.forouz_disconnecting
        starting -> R.string.action_cancel
        state.isRunning -> R.string.acc_stop
        else -> R.string.acc_start
    })
    Button(onClick = { when { empty -> onImport(); starting -> action(MainAction.CancelServiceStart); else -> action(MainAction.ToggleService) } },
        enabled = !stopPending && !observing && (state.isRunning || starting || ready), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.buttonColors(containerColor = ForouzBrand.action, contentColor = ForouzBrand.onAction)) {
        if (stopPending || observing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.ic_power_24dp), null, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable private fun RouteStatus(running: Boolean, verified: Boolean, unknown: Boolean) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(if (unknown) R.string.forouz_route_unknown else if (verified) R.string.forouz_route_verified else if (running)
        R.string.forouz_route_unverified else R.string.forouz_route_off)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically) {
        listOf(R.string.forouz_device, R.string.forouz_proxy, R.string.forouz_internet).forEachIndexed { i, label ->
            if (i > 0) HorizontalDivider(Modifier.weight(.25f), color = if ((i == 1 && running) || verified) colors.tertiary else colors.outline)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = if ((i == 1 && running) || verified) colors.tertiaryContainer else colors.surfaceContainerHigh) {
                    Icon(painterResource(when (i) { 0 -> R.drawable.ic_per_apps_24dp; 1 -> R.drawable.ic_lock_24dp; else -> R.drawable.ic_routing_24dp }),
                        null, Modifier.padding(12.dp).size(24.dp), tint = if ((i == 1 && running) || verified) colors.onTertiaryContainer else colors.onSurfaceVariant)
                }
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable private fun OverviewMetrics(state: MainUiState) {
    val snapshot = state.connection.snapshot
    val available = state.connectionKnown && state.isRunning && snapshot?.running == true && snapshot.trafficAvailable && snapshot.sampleReady
    val dash = stringResource(R.string.forouz_unavailable)
    val values = listOf(
        stringResource(R.string.lab_download) to if (available) rateText(snapshot.downloadRate) else dash,
        stringResource(R.string.lab_upload) to if (available) rateText(snapshot.uploadRate) else dash,
        stringResource(R.string.forouz_session) to if (state.connectionKnown && state.isRunning && snapshot?.running == true) stringResource(R.string.lab_duration,
            state.connectedSeconds / 3600, state.connectedSeconds / 60 % 60, state.connectedSeconds % 60) else dash,
        stringResource(R.string.forouz_transferred) to if (available) stringResource(R.string.forouz_megabytes,
            (snapshot.uploaded.toDouble() + snapshot.downloaded) / 1_000_000) else dash)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (stacked) values.forEach { Metric(it.first, it.second, Modifier.fillMaxWidth()) }
            else values.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { Metric(it.first, it.second, Modifier.weight(1f)) }
            } }
            if (!available) Text(stringResource(R.string.forouz_stats_scope), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr))
    }
}

@Composable internal fun rateText(bytes: Long): String = stringResource(R.string.forouz_rate_si, bytes / 1_000_000.0)

private fun overviewStatusText(phase: OverviewPhase): Int = when (phase) {
    OverviewPhase.OBSERVING -> R.string.forouz_observing
    OverviewPhase.EMPTY, OverviewPhase.DISCONNECTED -> R.string.forouz_disconnected
    OverviewPhase.CONNECTING -> R.string.forouz_connecting
    OverviewPhase.DISCONNECTING -> R.string.forouz_disconnecting
    OverviewPhase.RECONNECTING -> R.string.forouz_reconnecting
    OverviewPhase.ACTIVE -> R.string.forouz_active_unverified
    OverviewPhase.CHECKING -> R.string.forouz_checking
    OverviewPhase.VERIFIED -> R.string.forouz_connected
    OverviewPhase.CHECK_FAILED -> R.string.forouz_check_failed
    OverviewPhase.ERROR -> R.string.forouz_start_failed
}
