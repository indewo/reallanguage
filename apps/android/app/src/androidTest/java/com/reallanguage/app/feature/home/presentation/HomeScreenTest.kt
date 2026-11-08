package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun landscape() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test
    fun arrowsWrapInEitherDirectionAndReentryResetsTheCenterCover() {
        val show = mutableStateOf(true)
        compose.setContent { RlTheme { if (show.value) HomeScreen(HomeUiState(), {}) } }
        val cards = listOf("home-quick-recording", "home-screenshot-card", "home-audio-card",
            "home-scene-card", "home-diary-card")
        fun selected(card: String) = compose.onNodeWithTag(card).assertIsSelected()
        selected(cards.first())
        for (n in 1..7) {
            compose.onNodeWithTag("home-next").performClick()
            selected(cards[n % 5])
        }
        for (n in 6 downTo -1) {
            compose.onNodeWithTag("home-previous").performClick()
            selected(cards[Math.floorMod(n, 5)])
        }
        compose.runOnIdle { show.value = false }
        compose.waitForIdle()
        compose.runOnIdle { show.value = true }
        selected(cards.first())
    }

    @Test
    fun aRealDragScrollsTheDeckAndDoesNotOpenAnEntry() {
        val events = mutableListOf<HomeAction>()
        compose.setContent { RlTheme { HomeScreen(HomeUiState(), events::add) } }
        val deck = compose.onNodeWithTag("home-deck")
        deck.performTouchInput {
            swipe(center, center.copy(x = center.x - width * .29f), durationMillis = 850)
        }
        compose.onNodeWithTag("home-screenshot-card").assertIsSelected()
        deck.performTouchInput {
            swipe(center, center.copy(x = center.x + width * .29f), durationMillis = 850)
        }
        compose.onNodeWithTag("home-quick-recording").assertIsSelected()
        compose.runOnIdle { assertEquals(emptyList<HomeAction>(), events) }
    }

    @Test
    fun fiveCoversAndFourFooterModulesDispatchTheirOwnActions() {
        val events = mutableListOf<HomeAction>()
        compose.setContent {
            RlTheme { HomeScreen(HomeUiState(hasDueReview = true, hasIncompleteLearning = true), events::add) }
        }
        val entries = listOf(
            "home-quick-recording" to HomeAction.QuickRecordingRequested,
            "home-screenshot-card" to HomeAction.ScreenshotImportRequested,
            "home-audio-card" to HomeAction.AudioImportRequested,
            "home-scene-card" to HomeAction.AiSceneRequested,
            "home-diary-card" to HomeAction.DiaryRequested,
            "home-vocabulary" to HomeAction.VocabularyRequested,
            "home-pattern" to HomeAction.SentencePatternsRequested,
            "home-review-card" to HomeAction.DueReviewRequested,
            "home-incomplete-card" to HomeAction.IncompleteLearningRequested,
        )
        entries.forEach { (tag, _) -> compose.onNodeWithTag(tag).assertIsDisplayed().performClick() }
        compose.runOnIdle { assertEquals(entries.map { it.second }, events) }
    }

    @Test
    fun shortLandscapeWithLargeTextKeepsAllFourModulesReachableAndDistinct() {
        compose.setContent {
            RlTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.size(580.dp, 280.dp)) { HomeScreen(HomeUiState(), {}) }
                }
            }
        }
        val tags = listOf("home-vocabulary", "home-pattern", "home-review-card", "home-incomplete-card")
        val bounds = tags.map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (left, right) ->
            org.junit.Assert.assertTrue(left.right <= right.left)
        }
    }
}
