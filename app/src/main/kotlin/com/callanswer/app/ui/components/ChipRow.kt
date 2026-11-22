package com.callanswer.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.callanswer.app.R
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.app.ui.theme.AppTheme

@Composable
fun ChipRow(
    labels: List<String>,
    selected: Set<String>,
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm),
    ) {
        labels.forEach { label ->
            val isSelected = label in selected
            val description = if (isSelected) stringResource(R.string.cd_chip_selected, label) else label
            FilterChip(
                selected = isSelected,
                onClick = { onChipClick(label) },
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

@Preview
@Composable
private fun ChipRowPreview() = AppTheme {
    ChipRow(labels = listOf("Delivery", "Door", "Sales"), selected = setOf("Delivery"), onChipClick = {})
}
