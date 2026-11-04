package com.reallanguage.app.feature.scene.domain

data class RecentSceneRecommendation(
    val recommendationId: String,
    val shortTopicZh: String,
    val whyRelevantZh: String,
    val sourceRefs: List<String>,
)

data class PublicSceneTemplate(
    val templateId: String,
    val titleZh: String,
    val scopeZh: String,
)

sealed interface SceneRecommendationResult {
    data class Ready(val recommendations: List<RecentSceneRecommendation>) : SceneRecommendationResult
    data object Unavailable : SceneRecommendationResult
}

sealed interface SceneStart {
    data class Recommendation(val recommendation: RecentSceneRecommendation) : SceneStart
    data class Template(val template: PublicSceneTemplate) : SceneStart
    data class DirectDescription(val description: String) : SceneStart
}

enum class SceneContentType { DIALOGUE, ARTICLE }

interface SceneHomeRepository {
    /** Returns only the account-scoped local snapshot; it never starts a network request. */
    suspend fun loadRecommendations(): SceneRecommendationResult

    /**
     * Refreshes only when the eligible fixed-content fingerprint changed. Returning null
     * means the local snapshot is already current, so opening the page never spends again.
     */
    suspend fun refreshRecommendationsIfNeeded(): SceneRecommendationResult? = null
}

val PublicSceneTemplates = listOf(
    PublicSceneTemplate("work", "工作沟通", "汇报进度、澄清任务、确认截止时间、表达不同意见"),
    PublicSceneTemplate("learning", "学习与协作", "提问、请求解释、接受反馈、商量分工"),
    PublicSceneTemplate("social", "初次见面与社交", "自我介绍、闲聊、延续话题、邀请或婉拒"),
    PublicSceneTemplate("planning", "安排与变更计划", "约时间、确认安排、迟到说明、改期或取消"),
    PublicSceneTemplate("travel", "旅行、交通与住宿", "问路、乘车、打车、购票、酒店沟通"),
    PublicSceneTemplate("shopping", "餐饮与购物", "点餐、表达偏好、询价、购买、退换"),
    PublicSceneTemplate("help", "求助与确认信息", "请求帮助、请对方重复、核对信息、确认理解"),
    PublicSceneTemplate("services", "生活服务与问题处理", "预约、维修、客服、投诉、协商解决"),
)
