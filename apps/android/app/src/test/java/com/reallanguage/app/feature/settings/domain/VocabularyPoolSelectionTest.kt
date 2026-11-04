package com.reallanguage.app.feature.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VocabularyPoolSelectionTest {
    @Test fun exposesExactlyTheEightPeerVocabularyPools() {
        assertEquals(
            listOf(
                BasicVocabulary.JUNIOR_HIGH,
                BasicVocabulary.SENIOR_HIGH,
                BasicVocabulary.CET_4,
                BasicVocabulary.CET_6,
                BasicVocabulary.POSTGRADUATE,
                BasicVocabulary.TOEFL,
                BasicVocabulary.IELTS,
                BasicVocabulary.GRE,
            ),
            selectableVocabularyPools,
        )
        assertFalse(BasicVocabulary.PRIMARY_SCHOOL in selectableVocabularyPools)
        assertFalse(BasicVocabulary.TEM_8 in selectableVocabularyPools)
    }
}
