package com.reallanguage.app.core.app

import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.billing.domain.BalanceOriginState
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualBackNavigationTest {
    private val source = HomeDiaryRoute.LocalRecordings("source-recordings")

    @Test fun routesOpenedFromMultiplePlacesReturnToTheirRetainedSource() {
        val routes = listOf(
            HomeDiaryRoute.SettingsEffect(
                InitialSettingsDraft(),
                InitialSettingsDraft(formalityLevel = 2),
                returnRoute = source,
            ),
            HomeDiaryRoute.Recharge(source),
            HomeDiaryRoute.InsufficientBalance(
                source,
                BalanceInsufficientContext("operation", BalanceOriginState.RECORDING_AWAITING_TRANSCRIPTION),
            ),
            HomeDiaryRoute.RecordingConfirmation("recording", source),
            HomeDiaryRoute.MicrophonePermission(canRequestDirectly = false, returnRoute = source),
            HomeDiaryRoute.ChapterLearning("chapter", returnRoute = source),
            HomeDiaryRoute.ChapterSelection(ChapterSelectionBoundary.FixedSource("source"), source),
            HomeDiaryRoute.ChapterAbandonConfirmation("chapter", source),
        )

        routes.forEach { route ->
            assertEquals("$route must return to its actual source node", source, contextualPreviousRoute(route))
        }
    }

    @Test fun explicitHomeActionsAreNotPartOfOrdinaryBackResolution() {
        assertEquals(null, contextualPreviousRoute(HomeDiaryRoute.Home))
        assertEquals(null, contextualPreviousRoute(HomeDiaryRoute.RecordingIntro))
    }
}
