package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.R
import com.reallanguage.app.feature.scene.domain.PublicSceneTemplates
import org.junit.Test
import org.junit.Assert.*

class SceneArtworkPresetTest {
    @Test fun presetsMatchTheExistingSceneCatalogAndUnknownTopicsStayNeutral() {
        assertEquals(PublicSceneTemplates.map { it.templateId }.toSet(), sceneArtworkPresets.keys)
        assertEquals(R.drawable.rl_diary_open_notebook, learningThemeArtwork("A new abstract topic"))
        assertEquals(R.drawable.rl_diary_open_notebook, learningThemeArtwork("coffee", "unknown"))
        assertEquals(R.drawable.rl_coffee_polaroid_20261124, learningThemeArtwork("Order a coffee without sugar"))
        assertEquals(R.drawable.rl_billing_receipt_20261124, learningThemeArtwork("Buying a shirt", "shopping"))
        assertEquals(R.drawable.rl_diary_open_notebook, learningThemeArtwork("coffee at an office meeting"))
        assertEquals(R.drawable.rl_scene_travel_ephemera, learningThemeArtwork("A hotel booking", "travel"))
    }
}
