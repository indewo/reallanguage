package com.reallanguage.app.core.app

import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.junit.Assert.*
import org.junit.Test

class SafetyCheckCommandIdentityTest {
    @Test fun sameLogicalSubmissionKeepsItsCommandAcrossReconstructionAndNavigationMetadataChanges() {
        val route = HomeDiaryRoute.SafetyCheck("同一份确认内容。", entryId = "submission-1")
        assertEquals(safetyCheckCommandId(route), safetyCheckCommandId(route.copy()))
        assertEquals(safetyCheckCommandId(route), safetyCheckCommandId(route.copy(returnRoute = HomeDiaryRoute.Input(route.text))))
        assertFalse(safetyCheckCommandId(route).contains(route.text))
    }

    @Test fun aNewExplicitSubmissionDoesNotReuseAnAlreadyReleasedBlockedCase() {
        val first = HomeDiaryRoute.SafetyCheck("同一份确认内容。")
        val second = HomeDiaryRoute.SafetyCheck("同一份确认内容。")
        assertTrue(first.entryId.isNotBlank())
        assertNotEquals(first.entryId, second.entryId)
        assertNotEquals(safetyCheckCommandId(first), safetyCheckCommandId(second))
    }

    @Test fun completePayloadIdentityIncludesShapeAndTypeAndDoesNotUseKotlinHashCode() {
        val route = HomeDiaryRoute.SafetyCheck("FB", entryId = "same-entry")
        assertEquals("FB".hashCode(), "Ea".hashCode())
        val values = listOf(
            route,
            route.copy(text = "Ea"),
            route.copy(sourceType = SourceType.RECORDING),
            route.copy(contentShape = SourceContentShape.DIALOGUE),
        ).map(::safetyCheckCommandId)
        assertEquals(4, values.toSet().size)
        assertEquals(values, listOf(route, route.copy(text = "Ea"), route.copy(sourceType = SourceType.RECORDING), route.copy(contentShape = SourceContentShape.DIALOGUE)).map(::safetyCheckCommandId))
    }

    @Test fun diarySubmitCreatesANewSafetyRequestButUnrelatedEventsRetainTheExistingRoute() {
        val input = HomeDiaryRoute.Input("同一份确认内容。")
        val first = homeDiaryRoute(input, HomeDiaryEvent.Submit(input.text)) as HomeDiaryRoute.SafetyCheck
        val second = homeDiaryRoute(input, HomeDiaryEvent.Submit(input.text)) as HomeDiaryRoute.SafetyCheck
        assertNotEquals(first.entryId, second.entryId)
        assertEquals(first, homeDiaryRoute(first, HomeDiaryEvent.Submit(first.text)))
    }
}
