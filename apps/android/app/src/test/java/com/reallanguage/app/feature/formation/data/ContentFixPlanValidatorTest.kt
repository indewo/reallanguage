package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFixPlanValidatorTest {
    private val focus = EnglishSentenceDraft("u1", "我很高兴。", true, listOf(EnglishCandidate("e1", "I was happy.")))
    private val target = LearningTarget("t1", "u1", "happy", "高兴的", LearningTargetType.WORD)
    private val valid = ContentFixRequest("flow", "cmd", listOf(EnglishChapterDraft("c1", listOf(focus))), mapOf("u1" to "e1"), listOf(target), setOf("t1"))
    @Test fun `new target evidence must be bound to the selected English before any fixed write`() {
        val source = SourceEvidence(1, 6, 11, "happy", "happy", listOf("happy"))
        assertTrue(ContentFixPlanValidator().validate(valid.copy(targets = listOf(target.copy(sourceEvidence = source)))))
        listOf(source.copy(startUtf16 = 5), source.copy(surfaceForm = "other"), source.copy(allowedForms = listOf("happy", "joyful")))
            .forEach { assertFalse(ContentFixPlanValidator().validate(valid.copy(targets = listOf(target.copy(sourceEvidence = it))))) }
    }
    @Test fun `word evidence cannot bind a different word or a multiword span before fixing`() {
        val text = "I drink tea and coffee."
        fun request(display: String, surface: String) = valid.copy(
            chapters = listOf(EnglishChapterDraft("c1", listOf(focus.copy(candidates = listOf(EnglishCandidate("e1", text)))))),
            targets = listOf(target.copy(display = display, sourceEvidence = SourceEvidence(1,
                text.indexOf(surface), text.indexOf(surface) + surface.length, surface, "tea", listOf(surface)))),
        )
        assertFalse(ContentFixPlanValidator().validate(request("tea", "coffee")))
        assertFalse(ContentFixPlanValidator().validate(request("tea and coffee", "tea and coffee")))
        assertTrue(ContentFixPlanValidator().validate(request(" TEA ", "tea")))
    }

    @Test fun `a complete question cannot be fixed as a word or fixed expression`() {
        val text = "Can you hear what I'm saying right now?"
        val sentence = focus.copy(candidates = listOf(EnglishCandidate("e1", text)))
        val evidence = SourceEvidence(1, 0, text.length, text, text, listOf(text))
        listOf(LearningTargetType.WORD, LearningTargetType.FIXED_EXPRESSION).forEach { type ->
            val request = valid.copy(
                chapters = listOf(EnglishChapterDraft("c1", listOf(sentence))),
                targets = listOf(target.copy(type = type, display = text, sourceEvidence = evidence)),
            )
            assertFalse(ContentFixPlanValidator().validate(request))
        }
    }

    @Test fun `inflected surface keeps distinct normalized metadata and abstract phrase display`() {
        val text = "She worked up the courage."
        val chapter = EnglishChapterDraft("c1", listOf(focus.copy(candidates = listOf(EnglishCandidate("e1", text)))))
        fun request(type: LearningTargetType, display: String, surface: String, normalized: String) = valid.copy(
            chapters = listOf(chapter), targets = listOf(target.copy(type = type, display = display,
                sourceEvidence = SourceEvidence(1, text.indexOf(surface), text.indexOf(surface) + surface.length,
                    surface, normalized, listOf(surface)))),
        )
        assertTrue(ContentFixPlanValidator().validate(request(LearningTargetType.WORD, "WORKED", "worked", "work")))
        listOf(LearningTargetType.FIXED_EXPRESSION, LearningTargetType.PATTERN).forEach { type ->
            assertTrue(ContentFixPlanValidator().validate(request(type, "work up the courage", "worked up the courage", "work up the courage")))
        }
    }
    @Test fun `accepts confirmed sentences with one to three focus units and optional selected targets`() {
        assertTrue(ContentFixPlanValidator().validate(valid))
        assertTrue(ContentFixPlanValidator().validate(valid.copy(selectedTargetIds = emptySet())))
    }
    @Test fun `rejects missing candidate selections or targets outside focus sentences`() {
        assertFalse(ContentFixPlanValidator().validate(valid.copy(selectedCandidateIds = emptyMap())))
        val contextTarget = target.copy(unitId = "u2")
        assertFalse(ContentFixPlanValidator().validate(valid.copy(targets = listOf(contextTarget), selectedTargetIds = setOf("t1"))))
    }
    @Test fun `rejects duplicate sentence identities and chapters over four units`() {
        assertFalse(ContentFixPlanValidator().validate(valid.copy(chapters = listOf(EnglishChapterDraft("c1", listOf(focus, focus))))))
        assertFalse(ContentFixPlanValidator().validate(valid.copy(chapters = listOf(EnglishChapterDraft("c1", List(5) { focus.copy(unitId = "u$it") })))))
    }
}
