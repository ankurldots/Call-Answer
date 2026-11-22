package com.callanswer.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.callanswer.app.R
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.cloud.ForwardingCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Opens the dialer with [code] filled in. `ACTION_DIAL` never dials, so no CALL_PHONE permission is needed. */
private fun openDialer(context: Context, code: String): Boolean {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse(ForwardingCodes.dialerUri(code))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
}

/** Loads `GET /me` and shows [content] only when consent is accepted and a number is assigned. */
@Composable
private fun ForwardingGate(
    api: CompanionApi,
    title: String,
    onBack: (() -> Unit)?,
    onOpenConsent: () -> Unit,
    content: @Composable (String) -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf<ForwardingAccess>(ForwardingAccess.Loading) }
    LaunchedEffect(reload) {
        access = ForwardingAccess.Loading
        access = try {
            forwardingAccess(api.me())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            ForwardingAccess.Failed(if (e is ApiException.Network) R.string.fwd_err_network else R.string.fwd_err_other)
        }
    }
    SettingsPage(title, onBack) {
        when (val a = access) {
            ForwardingAccess.Loading -> Text(stringResource(R.string.fwd_loading))
            is ForwardingAccess.Failed -> {
                StatusBanner(stringResource(a.messageRes), BannerSeverity.ERROR)
                AppButton(stringResource(R.string.fwd_retry), primary = false, onClick = { reload++ })
            }
            ForwardingAccess.Locked -> {
                StatusBanner(stringResource(R.string.fwd_locked), BannerSeverity.WARNING)
                AppButton(stringResource(R.string.fwd_open_consent), onClick = onOpenConsent)
            }
            ForwardingAccess.NoNumber -> StatusBanner(stringResource(R.string.fwd_no_number), BannerSeverity.WARNING)
            is ForwardingAccess.Ready -> content(a.virtualNumber)
        }
    }
}

@Composable
private fun CodeCard(label: String, code: String) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    AppCard(label) {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            Text(code, style = MaterialTheme.typography.titleMedium)
            AppButton(stringResource(R.string.fwd_open_dialer), primary = false, onClick = { failed = !openDialer(context, code) })
            if (failed) StatusBanner(stringResource(R.string.fwd_no_dialer), BannerSeverity.ERROR)
        }
    }
}

/** Setup: the three conditional codes, the ring time, the unconditional option with a warning, charges text. */
@Composable
fun ForwardingSetupScreen(
    api: CompanionApi,
    onBack: (() -> Unit)?,
    onOpenConsent: () -> Unit,
    onOpenTurnOff: () -> Unit,
    onOpenTest: () -> Unit,
    /** Extra content at the end of the page (the onboarding "Continue" button). */
    footer: @Composable () -> Unit = {},
) {
    ForwardingGate(api, stringResource(R.string.fwd_setup_title), onBack, onOpenConsent) { number ->
        var ring by remember { mutableIntStateOf(20) }
        val choices = ForwardingCodes.ringSecondsChoices
        val codes = ForwardingCodes.set(number, ring)
        Text(stringResource(R.string.fwd_your_number, number), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.fwd_intro))
        if (codes == null) {
            StatusBanner(stringResource(R.string.fwd_no_number), BannerSeverity.WARNING)
            return@ForwardingGate
        }
        Text(stringResource(R.string.fwd_ring_time, ring))
        Slider(
            value = choices.indexOf(ring).toFloat(),
            onValueChange = { ring = choices[it.toInt().coerceIn(0, choices.lastIndex)] },
            valueRange = 0f..choices.lastIndex.toFloat(),
            steps = choices.size - 2,
        )
        CodeCard(stringResource(R.string.fwd_step_noreply), codes[0])
        CodeCard(stringResource(R.string.fwd_step_busy), codes[1])
        CodeCard(stringResource(R.string.fwd_step_unreachable), codes[2])
        StatusBanner(stringResource(R.string.fwd_charges))
        ForwardingCodes.unconditional(number)?.let { unconditional ->
            StatusBanner(stringResource(R.string.fwd_unconditional_warning), BannerSeverity.WARNING)
            CodeCard(stringResource(R.string.fwd_unconditional_step), unconditional)
        }
        AppButton(stringResource(R.string.fwd_open_test), onClick = onOpenTest)
        AppButton(stringResource(R.string.fwd_open_turnoff), primary = false, onClick = onOpenTurnOff)
        footer()
    }
}

/** Turn-off: `##61#`, `##67#`, `##62#`, `##002#`, plus the unconditional clear. */
@Composable
fun ForwardingTurnOffScreen(api: CompanionApi, onBack: () -> Unit, onOpenConsent: () -> Unit) {
    ForwardingGate(api, stringResource(R.string.fwd_turnoff_title), onBack, onOpenConsent) { _ ->
        Text(stringResource(R.string.fwd_turnoff_intro))
        val labels = listOf(
            R.string.fwd_off_noreply, R.string.fwd_off_busy, R.string.fwd_off_unreachable, R.string.fwd_off_all,
        )
        ForwardingCodes.turnOff.forEachIndexed { i, code -> CodeCard(stringResource(labels[i]), code) }
        CodeCard(stringResource(R.string.fwd_off_unconditional), ForwardingCodes.clearUnconditional)
        StatusBanner(stringResource(R.string.fwd_charges))
    }
}

private sealed interface TestState {
    data object Idle : TestState
    data class Waiting(val secondsLeft: Int, val pollFailed: Boolean) : TestState
    data object Passed : TestState
    data object TimedOut : TestState
}

/**
 * "Send test call": the User calls this phone from another phone and does not answer. For up to 10 minutes the screen
 * polls the call list for a call that started after the button was pressed.
 */
@Composable
fun ForwardingTestScreen(api: CompanionApi, onBack: () -> Unit, onOpenConsent: () -> Unit) {
    var state by remember { mutableStateOf<TestState>(TestState.Idle) }
    var startedAt by remember { mutableStateOf(0L) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        var left = TEST_CALL_WAIT_SEC
        var failed = false
        while (left > 0) {
            state = TestState.Waiting(left, failed)
            if (left % 10 == 0) {
                try {
                    if (api.listCalls(limit = 5).calls.any { it.startedAtEpochMs >= startedAt }) {
                        state = TestState.Passed
                        return@LaunchedEffect
                    }
                    failed = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    failed = true
                }
            }
            delay(1_000)
            left--
        }
        state = TestState.TimedOut
    }
    ForwardingGate(api, stringResource(R.string.fwd_test_title), onBack, onOpenConsent) { _ ->
        Text(stringResource(R.string.fwd_test_intro))
        when (val s = state) {
            TestState.Idle -> AppButton(stringResource(R.string.fwd_test_start), onClick = {
                startedAt = System.currentTimeMillis(); run++
            })
            is TestState.Waiting -> {
                StatusBanner(stringResource(R.string.fwd_test_waiting, formatCountdown(s.secondsLeft)))
                if (s.pollFailed) StatusBanner(stringResource(R.string.fwd_test_poll_error), BannerSeverity.WARNING)
            }
            TestState.Passed -> {
                StatusBanner(stringResource(R.string.fwd_test_passed))
                AppButton(stringResource(R.string.fwd_test_again), primary = false, onClick = { state = TestState.Idle })
            }
            TestState.TimedOut -> {
                StatusBanner(stringResource(R.string.fwd_test_timeout), BannerSeverity.ERROR)
                AppButton(stringResource(R.string.fwd_test_again), onClick = { state = TestState.Idle })
            }
        }
    }
}
