package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.WritingTargetType
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCompleteWritingRepositoryTest {
    @Test
    fun `maps locked vocabulary and fixed expressions to meaning but patterns separately`() {
        assertEquals(WritingTargetType.MEANING, writingTargetType("WORD"))
        assertEquals(WritingTargetType.MEANING, writingTargetType("FIXED_EXPRESSION"))
        assertEquals(WritingTargetType.PATTERN, writingTargetType("PATTERN"))
    }
}
