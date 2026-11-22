package com.callanswer.app.ui.screens
import com.callanswer.app.data.rememberAgentName
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import com.callanswer.app.R
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.IconBadge
import com.callanswer.app.ui.components.SectionHeader
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import kotlinx.coroutines.launch
/** Home: setup progress, quick links, then every denied item with "Request again" and "Open settings" (9.7). */
@Composable
fun HomeScreen(model: SetupModel, actions: SetupActions, onOpenSettings: () -> Unit = {}, onOpenCallLog: () -> Unit = {}, onOpenAccount: () -> Unit = {}) {
    val status = model.status
    val total = SetupItem.entries.size
    val ready = total - status.denied.size
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.spaceMd)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        SetupCard(ready, total)
        SectionHeader(stringResource(R.string.home_quick_links))
        QuickLink(Icons.Filled.Call, stringResource(R.string.calllog_open), stringResource(R.string.calllog_open_hint), onOpenCallLog)
        QuickLink(Icons.Filled.Settings, stringResource(R.string.settings_open), stringResource(R.string.settings_open_hint), onOpenSettings)
        if (status.denied.isNotEmpty()) {
            Text(
                stringResource(R.string.home_denied_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            status.denied.forEach { item -> DeniedItem(item, actions, onOpenAccount) }
        }
    }
}
@Composable
private fun SetupCard(ready: Int, total: Int) {
    AppCard(contentDescription = stringResource(R.string.app_name)) {
        Text(
            if (ready == total) stringResource(R.string.home_setup_complete)
            else stringResource(R.string.home_setup_progress, ready, total),
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(progress = { ready.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
}
@Composable
private fun QuickLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    AppCard(contentDescription = "$title. $subtitle", onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceMd)) {
            IconBadge(icon, size = AppDimens.iconLg + AppDimens.spaceSm)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
@Composable
private fun DeniedItem(item: SetupItem, actions: SetupActions, onOpenAccount: () -> Unit) {
    val signIn = item == SetupItem.SIGN_IN
    val message = if (signIn) stringResource(R.string.home_signin_missing) else deniedMessage(item)
    AppCard(contentDescription = message) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceMd)) {
            IconBadge(item.icon(), size = AppDimens.iconLg + AppDimens.spaceSm)
            Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall)
        }
        StatusBanner(message, BannerSeverity.WARNING, Modifier.padding(vertical = AppDimens.spaceSm))
        if (signIn) {
            AppButton(stringResource(R.string.action_sign_in), onClick = onOpenAccount)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm), modifier = Modifier.fillMaxWidth()) {
                AppButton(stringResource(R.string.action_request_again), onClick = { actions.request(item) })
                AppButton(stringResource(R.string.action_open_settings), onClick = { actions.openSettings(item) }, primary = false)
            }
        }
    }
}
