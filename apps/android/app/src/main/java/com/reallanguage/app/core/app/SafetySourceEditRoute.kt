package com.reallanguage.app.core.app

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.scene.domain.SceneContentType

/** Resolves an actual source editor; null means that its original identity is unavailable. */
internal fun safetySourceEditRoute(source: ConfirmedSource, origin: HomeDiaryRoute?): HomeDiaryRoute? =
    when (source.sourceType) {
        SourceType.DIARY -> HomeDiaryRoute.Input(source.text)
        SourceType.RECORDING -> origin as? HomeDiaryRoute.TranscriptResult
        SourceType.AI_SCENE -> (origin as? HomeDiaryRoute.SceneChineseReview)?.takeIf {
            val expectedType = when (source.contentShape) {
                SourceContentShape.ARTICLE -> SceneContentType.ARTICLE
                SourceContentShape.DIALOGUE -> SceneContentType.DIALOGUE
            }
            it.session.contentType == expectedType && it.draft.contentType == expectedType
        }
    }
