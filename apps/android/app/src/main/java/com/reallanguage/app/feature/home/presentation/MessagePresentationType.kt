package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.MessageKind

enum class MessagePresentationType(val statusLabelZh: String) {
    REVIEW_APPROVED("已通过"),
    REVIEW_NEEDS_EDIT("需修改"),
    LEARNING_READY("可学习"),
    REVIEW_READY("去查看"),
    VIEW("去查看"),
}

internal fun AppMessage.presentationType(): MessagePresentationType = when (kind) {
    MessageKind.MANUAL_REVIEW_RESULT -> when (summaryZh) {
        "本次内容已通过人工复核。", "人工审核已通过" -> MessagePresentationType.REVIEW_APPROVED
        "本次内容需要进一步脱敏处理。", "本次内容维持安全拦截。", "人工审核未通过" -> MessagePresentationType.REVIEW_NEEDS_EDIT
        else -> MessagePresentationType.VIEW
    }
    MessageKind.SYSTEM -> when (summaryZh) {
        "学习内容已生成" -> MessagePresentationType.LEARNING_READY
        "今日回顾已就绪" -> MessagePresentationType.REVIEW_READY
        else -> MessagePresentationType.VIEW
    }
}
