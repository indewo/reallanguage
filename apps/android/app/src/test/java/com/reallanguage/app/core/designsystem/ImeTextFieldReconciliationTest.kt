package com.reallanguage.app.core.designsystem

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeTextFieldReconciliationTest {
    @Test fun viewModelEchoKeepsVoiceImeCompositionInsteadOfCommittingEveryPartialPhrase() {
        val composing = TextFieldValue(
            text = "今天我在开发APP",
            selection = TextRange(9),
            composition = TextRange(0, 9),
        )

        val reconciled = reconcileImeTextFieldValue(
            local = composing,
            externalText = "今天我在开发APP",
            externalSelectionStart = 9,
            externalSelectionEnd = 9,
        )

        assertEquals(TextRange(0, 9), reconciled.composition)
        assertEquals("今天我在开发APP", reconciled.text)
    }

    @Test fun genuineExternalReplacementClearsStaleCompositionAndUsesSafeSelection() {
        val composing = TextFieldValue("今天我", TextRange(3), TextRange(0, 3))

        val reconciled = reconcileImeTextFieldValue(
            local = composing,
            externalText = "恢复的草稿",
            externalSelectionStart = 99,
            externalSelectionEnd = 99,
        )

        assertEquals("恢复的草稿", reconciled.text)
        assertEquals(TextRange(5), reconciled.selection)
        assertNull(reconciled.composition)
    }
}
