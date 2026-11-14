package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable internal fun MainImportChooser(onAction: (MainAction) -> Unit, onSubscriptions: () -> Unit,
    onManual: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.forouz_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.menu_item_import_config_clipboard to MainAction.ImportClipboard,
                    R.string.menu_item_import_config_qrcode to MainAction.ImportQRcode,
                    R.string.menu_item_import_config_local to MainAction.ImportConfigLocal).forEach { (label, action) ->
                    OutlinedButton(onClick = { onDismiss(); onAction(action) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(label))
                    }
                }
                OutlinedButton(onClick = { onDismiss(); onSubscriptions() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.forouz_subscription_url))
                }
                TextButton(onClick = { onDismiss(); onManual() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.forouz_manual))
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
