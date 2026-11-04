package com.reallanguage.app.core.app

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewExitRepository
import com.reallanguage.app.feature.review.domain.ReviewExitResult
import com.reallanguage.app.feature.review.presentation.ReviewExitViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReviewExitEntryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun reopeningSameChapterCreatesFreshExitSession() {
        val visible = mutableStateOf(true)
        var created = 0
        var discarded = 0
        var returnedHome = 0
        val repository = object : ReviewExitRepository {
            override suspend fun discardWorkingState(reviewChapterId: String): ReviewExitResult {
                discarded += 1
                return ReviewExitResult.Discarded
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                created += 1
                return ReviewExitViewModel("same-chapter", 1, repository) as T
            }
        }
        composeRule.setContent {
            RlTheme {
                Button(onClick = { visible.value = true }) { Text("再次进入同一章") }
                if (visible.value) {
                    ReviewExitEntry("same-chapter", factory,
                        onContinue = { visible.value = false },
                        onHome = { returnedHome += 1; visible.value = false },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("review-exit-confirm").performClick()
        composeRule.waitUntil { returnedHome == 1 }
        composeRule.onNodeWithText("再次进入同一章").performClick()
        composeRule.waitForIdle()
        assertEquals("A new dialog must not reuse the completed previous session", 2, created)
        composeRule.onNodeWithTag("review-exit-confirm").performClick()
        composeRule.waitUntil { returnedHome == 2 }
        assertEquals(2, discarded)
    }
}
