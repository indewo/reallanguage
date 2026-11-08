package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.scene.domain.*
import org.junit.Assert.*
import org.junit.Test

class FormationRecoveryRoutesTest {
    @Test fun `eight stages round trip complete accepted data without discarding downstream cache`() {
        val original = sample()
        FormationRecoveryPhase.entries.filter { it != FormationRecoveryPhase.BILINGUAL_SELECTION }.forEach { phase ->
            val snapshot = original.copy(phase = phase)
            val restored = requireNotNull(snapshot.toFormationRecoveryRoute())
            assertEquals(1, restored.targetSentenceIndex)
            assertEquals(snapshot, restored.route.toFormationRecoverySnapshot(4, "隔离日记", snapshot, restored.targetSentenceIndex))
        }
    }

    @Test fun `bilingual drafts restore the whole passage while the next learning step keeps only selected English`() {
        val paragraph = com.reallanguage.app.feature.capture.domain.BilingualParagraph("如果周五有空，我就改邀请。还有一件事。",
            "If Friday works, I’ll update the invite. One more thing.",
            listOf(com.reallanguage.app.feature.capture.domain.BilingualSentence("p1-s1","If Friday works, I’ll update the invite.","如果周五有空，我就改邀请。"),
                com.reallanguage.app.feature.capture.domain.BilingualSentence("p1-s2","One more thing.","还有一件事。")))
        val review = HomeDiaryRoute.Bilingual(paragraph.textZh, CaptureOrigin.DIARY, SourceContentShape.ARTICLE,
            "abc", listOf(paragraph), HomeDiaryRoute.Input(paragraph.textZh), setOf("p1-s1"))
        val draft = requireNotNull(review.toFormationRecoverySnapshot(1, "改约"))
        assertEquals(review, draft.toFormationRecoveryRoute()?.route)
        val next = review.learningTargets()
        assertEquals(listOf("p1-s1"), next.units.map { it.unitId })
        val restoredTargets = requireNotNull(next.toFormationRecoverySnapshot(1, "改约", draft))
        assertEquals(next, restoredTargets.toFormationRecoveryRoute()?.route)
        assertEquals(review, formationPreviousRoute(next))
    }

    @Test fun `new diary split contains original full source but no invented derived content`() {
        val route = HomeDiaryRoute.SemanticSplit("正文\n保留全文", entryId = "source-new")
        val snapshot = requireNotNull(route.toFormationRecoverySnapshot(0, "日记"))
        assertEquals(route.text, snapshot.source.text)
        assertEquals(FormationSourceOrigin.Diary, snapshot.origin)
        assertTrue(snapshot.units.isEmpty())
        assertTrue(snapshot.englishChapters.isEmpty())
        assertNull(snapshot.targetSelectionEntryId)
    }

    @Test fun `focus change invalidates generated chapters candidates target identities and position`() {
        val old = sample()
        val route = HomeDiaryRoute.FocusSelection(old.source.text, old.units,
            initialSelectedUnitIds = setOf("u1"), sourceEntryId = old.sourceEntryId)
        val snapshot = requireNotNull(route.toFormationRecoverySnapshot(4, old.sourceTitle, old, 1))
        assertEquals(listOf("u1"), snapshot.selectedUnitIds)
        assertTrue(snapshot.chapters.isEmpty())
        assertTrue(snapshot.targets.isEmpty())
        assertNull(snapshot.targetSelectionEntryId)
        assertNull(snapshot.fixEntryId)
        assertEquals(0, snapshot.targetSentenceIndex)
    }

    @Test fun `changed candidate selection cannot inherit old targets or fixed command`() {
        val old = sample()
        val route = HomeDiaryRoute.LearningTargets(old.source.text, old.units, old.englishChapters,
            old.selectedCandidateIds + ("u1" to "e1b"), sourceEntryId = old.sourceEntryId)
        val snapshot = requireNotNull(route.toFormationRecoverySnapshot(4, old.sourceTitle, old))
        assertEquals("e1b", snapshot.selectedCandidateIds["u1"])
        assertTrue(snapshot.targets.isEmpty())
        assertTrue(snapshot.queryableExpressions.isEmpty())
        assertNull(snapshot.targetSelectionEntryId)
        assertNull(snapshot.fixEntryId)
    }

    @Test fun `final route needs matching accepted target context and preserves explicit empty selection`() {
        val old = sample()
        val final = HomeDiaryRoute.FinalFix(old.source.text, old.units, old.englishChapters,
            old.selectedCandidateIds, old.targets, emptySet(), entryId = "new-fix", sourceEntryId = old.sourceEntryId)
        assertNull(final.toFormationRecoverySnapshot(4, old.sourceTitle))
        assertNull(final.toFormationRecoverySnapshot(5, old.sourceTitle, old))
        val saved = requireNotNull(final.toFormationRecoverySnapshot(4, old.sourceTitle, old, 1))
        assertTrue(saved.selectedTargetIds.isEmpty())
        assertEquals(old.queryableExpressions, saved.queryableExpressions)
        assertEquals(old.targetSelectionEntryId, saved.targetSelectionEntryId)
        assertEquals("new-fix", saved.fixEntryId)
        assertEquals(1, saved.targetSentenceIndex)
    }

    @Test fun `recording and unavailable review origins never turn into diary editors`() {
        val route = HomeDiaryRoute.SemanticSplit("原转写", SourceType.RECORDING,
            sourceReturnRoute = HomeDiaryRoute.TranscriptResult("recording-one", "transcript-one"), entryId = "recording-source")
        val saved = requireNotNull(route.toFormationRecoverySnapshot(0, "录音"))
        assertEquals(FormationSourceOrigin.Recording("recording-one", "transcript-one"), saved.origin)
        val restored = saved.toFormationRecoveryRoute()!!.route as HomeDiaryRoute.SemanticSplit
        assertEquals(route.sourceReturnRoute, restored.sourceReturnRoute)
        val missing = route.copy(sourceReturnRoute = HomeDiaryRoute.ManualReviewResult("review-one"))
            .toFormationRecoverySnapshot(0, "录音")!!
        assertEquals(FormationSourceOrigin.Unavailable("review-one"), missing.origin)
        assertEquals("review-one", ((missing.toFormationRecoveryRoute()!!.route as HomeDiaryRoute.SemanticSplit)
            .sourceReturnRoute as HomeDiaryRoute.ManualReviewResult).reviewRequestId)
    }

    @Test fun `scene editor only survives if draft text and shape match the actual source`() {
        val session = SceneQuestionSession(SceneStart.DirectDescription("问候"), SceneContentType.DIALOGUE,
            emptyList(), operationId = "scene-operation")
        val draft = SceneGeneratedDraft("scene-draft", SceneContentType.DIALOGUE,
            listOf(SceneDraftUnit("scene-u1", SceneSpeaker.USER, "你好。", emptyList())))
        val editor = HomeDiaryRoute.SceneChineseReview(session, draft)
        val route = HomeDiaryRoute.SemanticSplit("用户：你好。", SourceType.AI_SCENE, SourceContentShape.DIALOGUE,
            editor, "scene-source")
        val saved = requireNotNull(route.toFormationRecoverySnapshot(0, "场景"))
        assertEquals(FormationSourceOrigin.Scene(session, draft), saved.origin)
        assertEquals(editor, (saved.toFormationRecoveryRoute()!!.route as HomeDiaryRoute.SemanticSplit).sourceReturnRoute)
        assertEquals(FormationSourceOrigin.Unavailable(), route.copy(text = "用户：内容已改。")
            .toFormationRecoverySnapshot(0, "场景")!!.origin)
        assertEquals(FormationSourceOrigin.Unavailable(), route.copy(contentShape = SourceContentShape.ARTICLE)
            .toFormationRecoverySnapshot(0, "场景")!!.origin)
    }

    @Test fun `source revision or content changes do not reuse old output and invalid inputs fail closed`() {
        val old = sample()
        val route = HomeDiaryRoute.SemanticSplit(old.source.text, entryId = old.sourceEntryId)
        assertTrue(route.toFormationRecoverySnapshot(5, old.sourceTitle, old)!!.units.isEmpty())
        assertTrue(route.copy(text = "另一篇正文").toFormationRecoverySnapshot(4, old.sourceTitle, old)!!.units.isEmpty())
        assertNull(old.copy(selectedTargetIds = setOf("unknown")).toFormationRecoveryRoute())
        assertNull(route.toFormationRecoverySnapshot(-1, old.sourceTitle))
        assertNull(HomeDiaryRoute.Home.toFormationRecoverySnapshot(0, "首页"))
    }

    private fun sample(): FormationRecoverySnapshot {
        val units = listOf(SemanticUnit("u1", 0, "我喝茶。"), SemanticUnit("u2", 1, "她工作。"), SemanticUnit("u3", 2, "未入章原文。"))
        val english = listOf(EnglishChapterDraft("c1", listOf(
            EnglishSentenceDraft("u1", units[0].textZh, true, listOf(EnglishCandidate("e1", "I drink tea.", listOf("VOCABULARY:tea")), EnglishCandidate("e1b", "I have tea."))),
            EnglishSentenceDraft("u2", units[1].textZh, true, listOf(EnglishCandidate("e2", "She works."))),
        )))
        return FormationRecoverySnapshot("source-isolated", 4, FormationRecoveryPhase.FINAL_FIX,
            ConfirmedSource(units.joinToString("\n") { it.textZh }, SourceType.DIARY, SourceContentShape.ARTICLE),
            "隔离日记", FormationSourceOrigin.Diary, units, listOf("u1", "u2"), listOf(ChapterDraft("c1", listOf("u1", "u2"))),
            english, mapOf("u1" to "e1", "u2" to "e2"),
            listOf(LearningTarget("t1", "u1", "tea", "茶", LearningTargetType.WORD,
                sourceEvidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")), knownKnowledgeRef = "VOCABULARY:tea")),
            listOf(QueryableExpression("word:8", "u1", "tea")), setOf("t1"), "target-entry", "fix-entry", 1)
    }
}
