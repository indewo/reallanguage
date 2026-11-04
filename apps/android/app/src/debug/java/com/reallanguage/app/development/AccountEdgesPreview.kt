package com.reallanguage.app.development

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.reallanguage.app.feature.auth.presentation.AuthMode
import com.reallanguage.app.feature.auth.presentation.AuthScreen
import com.reallanguage.app.feature.auth.presentation.AuthUiState
import com.reallanguage.app.feature.auth.presentation.BirthYearMonthDialog
import com.reallanguage.app.feature.auth.presentation.BirthYearMonthDialogContent
import java.time.YearMonth
import com.reallanguage.app.feature.auth.presentation.PasswordResetScreen
import com.reallanguage.app.feature.auth.presentation.PasswordResetUiState
import com.reallanguage.app.feature.auth.presentation.PasswordResetStep
import com.reallanguage.app.feature.auth.presentation.PasswordResetCompletionDestination

internal val passwordResetEdgeDestinations = setOf("PASSWORD_RESET_EMAIL", "PASSWORD_RESET_NEW_PASSWORD", "PASSWORD_RESET_SUCCESS")

/** Real existing states, exposed only for screenshot/geometry coverage. */
@Composable
internal fun PasswordResetEdgePreview(destinationName: String) {
    val step = when (destinationName) {
        "PASSWORD_RESET_EMAIL" -> PasswordResetStep.EMAIL_CONFIRMATION
        "PASSWORD_RESET_NEW_PASSWORD" -> PasswordResetStep.NEW_PASSWORD
        "PASSWORD_RESET_SUCCESS" -> PasswordResetStep.SUCCESS
        else -> error("Unknown password reset preview: $destinationName")
    }
    PasswordResetScreen(PasswordResetUiState(step = step, email = "learner@example.com",
        newPassword = if (step == PasswordResetStep.NEW_PASSWORD) "abcdefghij" else "",
        completionDestination = PasswordResetCompletionDestination.LOGIN), {})
}

/** Screenshot-only entry; production opens the same picker through BirthYearMonthPicker. */
@Composable
internal fun BirthYearMonthDesignPreview(referenceCanvas: Boolean) {
    Box(Modifier.fillMaxSize()) {
        AuthScreen(AuthUiState(mode = AuthMode.REGISTRATION, email = "learner@example.com",
            birthYearMonth = "1992-09"), {})
        if (referenceCanvas) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)))
            BirthYearMonthDialogContent(YearMonth.of(1992, 9), {}, {})
        } else BirthYearMonthDialog(YearMonth.of(1992, 9), {}, {})
    }
}
