package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessagePresentationTypeTest {
    @Test fun exactProductionApprovalMapsToApproved() {
        assertEquals(MessagePresentationType.REVIEW_APPROVED,
            message(MessageKind.MANUAL_REVIEW_RESULT, "本次内容已通过人工复核。").presentationType())
    }

    @Test fun bothExactProductionNonApprovalsRequireEditing() {
        listOf("本次内容需要进一步脱敏处理。", "本次内容维持安全拦截。").forEach { summary ->
            assertEquals(summary, MessagePresentationType.REVIEW_NEEDS_EDIT,
                message(MessageKind.MANUAL_REVIEW_RESULT, summary).presentationType())
        }
    }

    @Test fun exactLegacyReviewDesignLabelsKeepTheirExplicitDecision() {
        assertEquals(MessagePresentationType.REVIEW_APPROVED,
            message(MessageKind.MANUAL_REVIEW_RESULT, "人工审核已通过").presentationType())
        assertEquals(MessagePresentationType.REVIEW_NEEDS_EDIT,
            message(MessageKind.MANUAL_REVIEW_RESULT, "人工审核未通过").presentationType())
    }

    @Test fun exactSystemLearningReadyLabelDoesNotMakeManualReviewReadyToLearn() {
        assertEquals(MessagePresentationType.LEARNING_READY,
            message(MessageKind.SYSTEM, "学习内容已生成").presentationType())
        assertEquals(MessagePresentationType.VIEW,
            message(MessageKind.MANUAL_REVIEW_RESULT, "学习内容已生成").presentationType())
    }

    @Test fun exactReviewReadyDesignKeepsItsSeparateVisualType() {
        val review = message(MessageKind.SYSTEM, "今日回顾已就绪").presentationType()
        val unknown = message(MessageKind.SYSTEM, "回顾说明更新").presentationType()
        assertEquals(MessagePresentationType.REVIEW_READY, review)
        assertEquals(MessagePresentationType.VIEW, unknown)
        assertNotEquals("Known review-ready artwork must not collapse into unknown-message styling", review, unknown)
        assertEquals("去查看", review.statusLabelZh)
        assertEquals("去查看", unknown.statusLabelZh)
    }

    @Test fun genericManualCompletionAndBareDecisionKeywordsRemainViewOnly() {
        listOf("人工复核已完成", "复核完成", "通过", "未通过", "结果更新，请查看详情。").forEach { summary ->
            assertEquals(summary, MessagePresentationType.VIEW,
                message(MessageKind.MANUAL_REVIEW_RESULT, summary).presentationType())
        }
    }

    @Test fun securityAccountAndUnknownSystemNoticesCannotClaimAReviewDecision() {
        listOf("账号已通过安全审核", "系统审核记录更新", "系统通知：人工审核未通过的说明已更新",
            "本次内容已通过人工复核。", "人工审核已通过", "人工审核未通过").forEach { summary ->
            assertEquals(summary, MessagePresentationType.VIEW,
                message(MessageKind.SYSTEM, summary).presentationType())
        }
    }

    @Test fun quotedEmbeddedOrModifiedSummaryCannotBeGuessedAsAProductionDecision() {
        listOf("引用：本次内容已通过人工复核。", "本次内容已通过人工复核。\n用户补充内容",
            "本次内容已通过人工复核", " 本次内容已通过人工复核。 ").forEach { summary ->
            assertEquals(summary, MessagePresentationType.VIEW,
                message(MessageKind.MANUAL_REVIEW_RESULT, summary).presentationType())
        }
    }

    @Test fun sourceLabelsAndReadStateCannotCreateOrChangeTheDecision() {
        assertEquals(MessagePresentationType.VIEW,
            message(MessageKind.SYSTEM, "查看账号详情").copy(relatedContentLabelZh = "本次内容已通过人工复核。", read = true).presentationType())
        assertEquals(MessagePresentationType.REVIEW_NEEDS_EDIT,
            message(MessageKind.MANUAL_REVIEW_RESULT, "本次内容维持安全拦截。")
                .copy(relatedContentLabelZh = "学习内容已生成", read = true).presentationType())
    }

    private fun message(kind: MessageKind, summary: String) = AppMessage(
        "isolated-message", kind, summary, null, 1L, false,
        AppMessageTarget("manual-review", "isolated-review"), MessageTargetState.AVAILABLE,
    )
}
