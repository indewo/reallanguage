package com.reallanguage.app.core.app

/**
 * Returns the real source node retained by routes that can be opened from more
 * than one place.
 *
 * Deliberate actions such as "返回首页", "稍后学习" and "退出学习" are not
 * handled here.  This function is only for the ordinary Back affordance.
 */
internal fun contextualPreviousRoute(route: HomeDiaryRoute): HomeDiaryRoute? = when (route) {
    is HomeDiaryRoute.SettingsEffect -> route.returnRoute ?: HomeDiaryRoute.Settings()
    is HomeDiaryRoute.Recharge -> route.origin
    is HomeDiaryRoute.InsufficientBalance -> route.origin
    is HomeDiaryRoute.RecordingConfirmation -> route.returnRoute ?: HomeDiaryRoute.Home
    is HomeDiaryRoute.MicrophonePermission -> route.returnRoute
    is HomeDiaryRoute.ChapterLearning -> route.returnRoute
    is HomeDiaryRoute.ChapterResult -> route.returnRoute ?: HomeDiaryRoute.Home
    is HomeDiaryRoute.ChapterSelection -> route.returnRoute ?: HomeDiaryRoute.Home
    is HomeDiaryRoute.CompletedStepReview -> route.currentTask
    is HomeDiaryRoute.ChapterAbandonConfirmation -> route.returnRoute
    else -> null
}
