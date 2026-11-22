package com.callanswer.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.callanswer.app.R
import com.callanswer.app.ui.theme.AppTheme

@Composable
fun AppDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = stringResource(R.string.dialog_confirm),
    dismissText: String = stringResource(R.string.dialog_dismiss),
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = content,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

@Preview
@Composable
private fun AppDialogPreview() = AppTheme {
    AppDialog(title = "Report spam?", onConfirm = {}, onDismiss = {}) { Text("Send a report to 1909.") }
}
