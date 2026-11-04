package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakingTranscriptionWireTest {
    @Test fun unknownConfidenceStillAllowsTranscriptConfirmationInBothFlows() {
        val body = """{"transcript":"I can explain it.","lowConfidenceSegments":[],"confidence":null}"""
        val results = listOf(
            JsonFullSentenceSpeakingWireCodec().transcriptionResult(body),
            JsonSceneResponseWireCodec().transcriptionResult(body),
        )
        results.forEach { result ->
            val value = (result as SpeakingTranscriptionResult.Ready).value
            assertEquals("I can explain it.", value.transcript)
            assertNull(value.confidence)
        }
    }

    @Test fun preservesActualProviderConfidenceWhenPresent() {
        val body = """{"transcript":"I can explain it.","lowConfidenceSegments":["explain"],"confidence":0.62}"""
        val result = JsonFullSentenceSpeakingWireCodec().transcriptionResult(body) as SpeakingTranscriptionResult.Ready
        assertEquals(0.62, result.value.confidence!!, 0.0001)
        assertEquals(listOf("explain"), result.value.lowConfidenceSegments)
    }
}
