package com.reallanguage.app.core.app

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.scene.domain.*
import org.junit.Assert.*
import org.junit.Test

class SafetySourceEditRouteTest {
    @Test fun diaryRejectedFromMessagesReturnsToOriginalTextInsteadOfMessageList() {
        assertEquals(HomeDiaryRoute.Input("原始日记。"), safetySourceEditRoute(diary, HomeDiaryRoute.Messages("message-entry")))
    }

    @Test fun diaryEditUsesTheCaseTextRatherThanAnOlderDraftOrAnotherScreen() {
        for (origin in listOf(HomeDiaryRoute.Input("其他草稿。"), HomeDiaryRoute.Home, HomeDiaryRoute.TranscriptResult("r", "t"))) {
            assertEquals(HomeDiaryRoute.Input(diary.text), safetySourceEditRoute(diary, origin))
        }
    }

    @Test fun recordingWithoutOriginalIdsCannotBePretendedToBeADiaryOrMessageEditor() {
        val source = ConfirmedSource("录音确认文本。", SourceType.RECORDING, SourceContentShape.ARTICLE)
        for (origin in listOf(null, HomeDiaryRoute.Messages("message-entry"), HomeDiaryRoute.Input("别的日记。"))) {
            assertNull(safetySourceEditRoute(source, origin))
        }
    }

    @Test fun sceneWithoutItsSessionCannotBecomeADiaryOrInventAQuestionHistory() {
        val source = ConfirmedSource("用户：谢谢。\n对方：不客气。", SourceType.AI_SCENE, SourceContentShape.DIALOGUE)
        for (origin in listOf(null, HomeDiaryRoute.Messages("message-entry"), HomeDiaryRoute.TranscriptResult("r", "t"))) {
            assertNull(safetySourceEditRoute(source, origin))
        }
    }

    @Test fun originalRecordingEditorKeepsTheExactRecordingAndTranscriptIdentities() {
        val source = ConfirmedSource("录音确认文本。", SourceType.RECORDING, SourceContentShape.ARTICLE)
        val origin = HomeDiaryRoute.TranscriptResult("original-recording", "original-transcript")
        assertSame(origin, safetySourceEditRoute(source, origin))
    }

    @Test fun originalSceneEditorKeepsItsDraftUnitsRolesFactsAndQuestionHistory() {
        val origin = sceneOrigin(SceneContentType.DIALOGUE)
        val source = ConfirmedSource("用户：谢谢。\n对方：不客气。", SourceType.AI_SCENE, SourceContentShape.DIALOGUE)
        assertSame(origin, safetySourceEditRoute(source, origin))
    }

    @Test fun contradictorySceneShapeIsNotSilentlyRestoredAsDifferentContent() {
        val source = ConfirmedSource("对话内容。", SourceType.AI_SCENE, SourceContentShape.DIALOGUE)
        assertNull(safetySourceEditRoute(source, sceneOrigin(SceneContentType.ARTICLE)))
    }

    private val diary = ConfirmedSource("原始日记。", SourceType.DIARY, SourceContentShape.ARTICLE)

    private fun sceneOrigin(type: SceneContentType): HomeDiaryRoute.SceneChineseReview {
        val session = SceneQuestionSession(SceneStart.DirectDescription("礼貌道谢"), type,
            listOf(SceneAnswer("original-question", "表达感谢")), listOf("事实"), operationId = "original-session")
        val draft = SceneGeneratedDraft("original-draft", type, listOf(
            SceneDraftUnit("u1", if (type == SceneContentType.DIALOGUE) SceneSpeaker.USER else null, "谢谢。", listOf("f1")),
            SceneDraftUnit("u2", if (type == SceneContentType.DIALOGUE) SceneSpeaker.OTHER else null, "不客气。", listOf("f2")),
        ))
        return HomeDiaryRoute.SceneChineseReview(session, draft)
    }
}
