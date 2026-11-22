package com.callanswer.app.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.app.ui.theme.AppTheme

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    contentDescription: String = text,
) {
    val m = modifier
        .defaultMinSize(minHeight = AppDimens.minTouchTarget)
        .semantics { this.contentDescription = contentDescription }
    if (primary) {
        Button(onClick = onClick, modifier = m, enabled = enabled) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = m, enabled = enabled) { Text(text) }
    }
}

@Preview
@Composable
private fun AppButtonPreview() = AppTheme { AppButton(text = "Take over", onClick = {}) }
