package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FrozSectionTitle

/** Navigation hub only; existing settings owners retain every persisted value and editor. */
@Composable internal fun MainSettingsHub(onNavigate: (MainDestination) -> Unit, modifier: Modifier = Modifier) {
    val sections = listOf(
        R.string.forouz_preferences to listOf(MainDestination.Settings),
        R.string.forouz_network to listOf(MainDestination.Routing, MainDestination.PerAppProxy, MainDestination.Subscriptions),
        R.string.forouz_diagnostics to listOf(MainDestination.Laboratory, MainDestination.Logcat, MainDestination.UserAssets),
        R.string.forouz_app_info to listOf(MainDestination.BackupRestore, MainDestination.CheckUpdate, MainDestination.About))
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.forouz_settings_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            sections.forEach { (title, destinations) ->
                item(key = "heading-$title") { FrozSectionTitle(stringResource(title), Modifier.padding(top = 12.dp)) }
                items(destinations, key = { it.name }) { destination ->
                    Surface(onClick = { onNavigate(destination) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(painterResource(destination.iconRes), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(if (destination == MainDestination.Settings) R.string.forouz_app_preferences else destination.labelRes),
                                    style = MaterialTheme.typography.titleSmall)
                                if (destination == MainDestination.Settings) Text(stringResource(R.string.forouz_preferences_detail),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
