package com.callanswer.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.callanswer.app.R
import com.callanswer.app.ui.theme.AppTheme

/** Text field with a validation message slot: a non-null [errorMessage] is shown under the field. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    password: Boolean = false,
) {
    val errorText = errorMessage?.let { stringResource(R.string.cd_error_prefix, it) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = errorMessage != null,
        supportingText = errorMessage?.let { { Text(it) } },
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = label
            if (errorText != null) error(errorText)
        },
    )
}

@Preview
@Composable
private fun AppTextFieldPreview() = AppTheme {
    AppTextField(value = "abc", onValueChange = {}, label = "Transfer number", errorMessage = "Enter 7 to 15 digits")
}
