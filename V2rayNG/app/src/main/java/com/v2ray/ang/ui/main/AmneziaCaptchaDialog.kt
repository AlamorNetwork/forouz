package com.v2ray.ang.ui.main

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
internal fun AmneziaCaptchaDialog(image: Bitmap, onAction: (MainAction) -> Unit) {
    var answer by rememberSaveable(image) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onAction(MainAction.DismissAmneziaCaptcha) },
        title = { Text(stringResource(R.string.awg_captcha_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding().consumeWindowInsets(WindowInsets.navigationBars)) {
                Image(image.asImageBitmap(), stringResource(R.string.awg_captcha_image),
                    Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 180.dp))
                OutlinedTextField(answer, { if (it.length <= 64) answer = it },
                    label = { Text(stringResource(R.string.awg_captcha_answer)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = answer.isNotBlank(), onClick = { onAction(MainAction.SubmitAmneziaCaptcha(answer)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(MainAction.DismissAmneziaCaptcha) }) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
