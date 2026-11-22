package com.callanswer.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.callanswer.app.R
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.app.ui.theme.AppTheme

enum class BannerSeverity { INFO, WARNING, ERROR }

/** Banner announced to screen readers when it appears or changes (live region). */
@Composable
fun StatusBanner(message: String, severity: BannerSeverity = BannerSeverity.INFO, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (severity) {
        BannerSeverity.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BannerSeverity.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BannerSeverity.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    val description = stringResource(
        when (severity) {
            BannerSeverity.INFO -> R.string.cd_banner_info
            BannerSeverity.WARNING -> R.string.cd_banner_warning
            BannerSeverity.ERROR -> R.string.cd_banner_error
        },
        message,
    )
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Text(message, modifier = Modifier.padding(AppDimens.spaceMd), style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview
@Composable
private fun StatusBannerPreview() = AppTheme {
    StatusBanner(message = "Contacts permission is missing", severity = BannerSeverity.WARNING)
}
