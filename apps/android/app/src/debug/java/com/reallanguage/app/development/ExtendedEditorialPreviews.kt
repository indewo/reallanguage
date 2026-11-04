package com.reallanguage.app.development

import androidx.compose.runtime.Composable

/** Screenshot-only state catalog. Production routes never call this adapter. */
@Composable
internal fun renderExtendedEditorialPreview(destination: String?, referenceCanvas: Boolean): Boolean {
    when {
        destination == "BIRTH_YEAR_MONTH" -> BirthYearMonthDesignPreview(referenceCanvas)
        destination in passwordResetEdgeDestinations -> PasswordResetEdgePreview(requireNotNull(destination))
        destination in learningNormalEdgeDestinations -> LearningNormalEdgesPreview(requireNotNull(destination))
        destination in remainingBoundaryDestinations -> RemainingBoundaryPreview(requireNotNull(destination))
        else -> return false
    }
    return true
}
