package com.callanswer.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.app.ui.theme.AppTheme

/** Card; when [onClick] is set it is a single clickable element described by [contentDescription]. */
@Composable
fun AppCard(
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        this.contentDescription = contentDescription
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(AppDimens.spaceMd), content = content)
    }
    if (onClick != null) Card(onClick = onClick, modifier = m, content = body)
    else Card(modifier = m, content = body)
}

@Preview
@Composable
private fun AppCardPreview() = AppTheme {
    AppCard(contentDescription = "Call from unknown number") { Text("Unknown number") }
}
