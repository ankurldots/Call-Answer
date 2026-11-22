package com.callanswer.app.ui.screens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.callanswer.app.R
import com.callanswer.app.auth.AccountValidation
import com.callanswer.app.auth.AuthError
import com.callanswer.app.auth.AuthException
import com.callanswer.app.auth.AuthRepository
import com.callanswer.app.auth.AuthState
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppTextField
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
private enum class Mode { SIGN_IN, SIGN_UP, CONFIRM, FORGOT, RESET }
/** Email and password account: sign in, create account, confirm email, reset password, sign out. */
@Composable
fun AccountScreen(auth: AuthRepository, onBack: () -> Unit) {
    val state by auth.state.collectAsState()
    SettingsPage(stringResource(R.string.settings_section_account), onBack) {
        Text(stringResource(R.string.account_hint), style = MaterialTheme.typography.bodyMedium)
        when {
            !auth.isConfigured -> StatusBanner(stringResource(R.string.account_not_configured), BannerSeverity.WARNING)
            state is AuthState.SignedIn -> SignedInView(auth, (state as AuthState.SignedIn).email)
            else -> SignedOutForm(auth)
        }
    }
}
@Composable
private fun SignedInView(auth: AuthRepository, email: String) {
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.account_signed_in_as, email), style = MaterialTheme.typography.titleMedium)
    AppButton(stringResource(R.string.account_sign_out), primary = false, onClick = { scope.launch { auth.signOut() } })
}
@Composable
private fun SignedOutForm(auth: AuthRepository) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Mode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var info by remember { mutableStateOf<Int?>(null) }
    var submitted by remember { mutableStateOf(false) }
    fun go(to: Mode, message: Int? = null) { mode = to; info = message; error = null; submitted = false }
    /** Runs [block]; maps a failure to a message and stays on the same screen. */
    fun run(block: suspend () -> Unit) {
        busy = true; error = null; info = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                error = errorText(e.error)
            } finally {
                busy = false
            }
        }
    }
    val needsPassword = mode == Mode.SIGN_IN || mode == Mode.SIGN_UP || mode == Mode.RESET
    val emailError = if (submitted && !AccountValidation.isValidEmail(email)) stringResource(R.string.account_error_email) else null
    val passwordError = when {
        !submitted || !needsPassword -> null
        mode == Mode.SIGN_IN -> if (password.isEmpty()) stringResource(R.string.account_error_password) else null
        !AccountValidation.isValidPassword(password) -> stringResource(R.string.account_error_password)
        else -> null
    }
    val codeError = if (submitted && mode in setOf(Mode.CONFIRM, Mode.RESET) && code.isBlank()) stringResource(R.string.account_error_code) else null
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        AppTextField(email, { email = it }, stringResource(R.string.account_email), errorMessage = emailError, enabled = !busy)
        if (needsPassword) {
            val label = if (mode == Mode.RESET) R.string.account_new_password else R.string.account_password
            AppTextField(password, { password = it }, stringResource(label), errorMessage = passwordError, enabled = !busy, password = true)
        }
        if (mode == Mode.CONFIRM || mode == Mode.RESET) {
            AppTextField(code, { code = it }, stringResource(R.string.account_code), errorMessage = codeError, enabled = !busy)
        }
    }
    info?.let { StatusBanner(stringResource(it)) }
    error?.let { StatusBanner(stringResource(it), BannerSeverity.ERROR) }
    fun valid(): Boolean {
        submitted = true
        return AccountValidation.isValidEmail(email) && when (mode) {
            Mode.SIGN_IN -> password.isNotEmpty()
            Mode.SIGN_UP -> AccountValidation.isValidPassword(password)
            Mode.CONFIRM -> code.isNotBlank()
            Mode.FORGOT -> true
            Mode.RESET -> AccountValidation.isValidPassword(password) && code.isNotBlank()
        }
    }
    val primaryLabel = when (mode) {
        Mode.SIGN_IN -> R.string.account_sign_in
        Mode.SIGN_UP -> R.string.account_create
        Mode.CONFIRM -> R.string.account_confirm
        Mode.FORGOT -> R.string.account_send_reset_code
        Mode.RESET -> R.string.account_reset_password
    }
    AppButton(stringResource(primaryLabel), enabled = !busy, onClick = {
        if (!valid()) return@AppButton
        when (mode) {
            Mode.SIGN_IN -> run {
                try {
                    auth.signIn(email, password)
                    password = ""
                } catch (e: AuthException) {
                    if (e.error != AuthError.NOT_CONFIRMED) throw e
                    runCatching { auth.resendCode(email) }
                    go(Mode.CONFIRM, R.string.account_code_sent)
                }
            }
            Mode.SIGN_UP -> run { auth.signUp(email, password); go(Mode.CONFIRM, R.string.account_code_sent) }
            Mode.CONFIRM -> run {
                auth.confirmSignUp(email, code)
                code = ""
                if (password.isNotEmpty()) {
                    try { auth.signIn(email, password); password = "" } catch (e: AuthException) { go(Mode.SIGN_IN, R.string.account_confirmed_sign_in) }
                } else {
                    go(Mode.SIGN_IN, R.string.account_confirmed_sign_in)
                }
            }
            Mode.FORGOT -> run { auth.forgotPassword(email); go(Mode.RESET, R.string.account_code_sent) }
            Mode.RESET -> run {
                auth.resetPassword(email, code, password)
                password = ""; code = ""
                go(Mode.SIGN_IN, R.string.account_password_changed)
            }
        }
    })
    when (mode) {
        Mode.SIGN_IN -> {
            AppButton(stringResource(R.string.account_to_create), primary = false, enabled = !busy, onClick = { go(Mode.SIGN_UP) })
            AppButton(stringResource(R.string.account_forgot), primary = false, enabled = !busy, onClick = { go(Mode.FORGOT) })
        }
        Mode.SIGN_UP, Mode.FORGOT, Mode.RESET ->
            AppButton(stringResource(R.string.account_to_sign_in), primary = false, enabled = !busy, onClick = { go(Mode.SIGN_IN) })
        Mode.CONFIRM -> {
            AppButton(stringResource(R.string.account_resend), primary = false, enabled = !busy && AccountValidation.isValidEmail(email), onClick = {
                run { auth.resendCode(email); info = R.string.account_code_sent }
            })
            AppButton(stringResource(R.string.account_to_sign_in), primary = false, enabled = !busy, onClick = { go(Mode.SIGN_IN) })
        }
    }
}
private fun errorText(error: AuthError): Int = when (error) {
    AuthError.WRONG_CREDENTIALS -> R.string.account_err_wrong_credentials
    AuthError.NOT_CONFIRMED -> R.string.account_err_not_confirmed
    AuthError.ALREADY_EXISTS -> R.string.account_err_exists
    AuthError.INVALID_PASSWORD -> R.string.account_err_invalid_password
    AuthError.INVALID_EMAIL -> R.string.account_error_email
    AuthError.CODE_INVALID -> R.string.account_err_code_invalid
    AuthError.CODE_EXPIRED -> R.string.account_err_code_expired
    AuthError.TOO_MANY_REQUESTS -> R.string.account_err_too_many
    AuthError.NETWORK -> R.string.account_err_network
    else -> R.string.account_err_unknown
}
