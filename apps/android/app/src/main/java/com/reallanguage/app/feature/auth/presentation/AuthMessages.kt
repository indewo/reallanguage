package com.reallanguage.app.feature.auth.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reallanguage.app.R

@Composable
internal fun AuthSubmitError.message(): String = stringResource(
    when (this) {
        AuthSubmitError.INVALID_CREDENTIALS -> R.string.auth_invalid_credentials
        AuthSubmitError.NETWORK -> R.string.auth_network_failure
        AuthSubmitError.SERVICE -> R.string.auth_service_failure
    },
)
