package com.reallanguage.app.feature.learning.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTypographyTest {
    @Test fun `all supported apostrophes render as compact English contractions`() {
        listOf('\u2018', '\u2019', '\u02BC', '\uFF07').forEach { apostrophe ->
            assertEquals("I'm eating. That's nice.",
                "I${apostrophe} m eating. That ${apostrophe} s nice.".normalizedEnglishTypography())
        }
    }

    @Test fun `quoted words and ordinary spacing stay unchanged`() {
        assertEquals("She said 'good morning' today.",
            "She said 'good morning' today.".normalizedEnglishTypography())
    }
}
