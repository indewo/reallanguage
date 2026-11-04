package com.reallanguage.app.core.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.presentation.ReadSpeakViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadSpeakEntryLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingTheCaptureRepositoryAlsoChangesTheBoundViewModel() {
        val identity = mutableStateOf(Any())
        val chapter = FixedLearningChapter("isolated-entry", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        var created = 0
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                created++
                return ReadSpeakViewModel(chapter, "s1", ReadSpeakRepository { _, _ -> ReadSpeakFeedbackResult.Failed }) as T
            }
        }
        compose.setContent { rememberReadSpeakViewModel("isolated-entry", "s1", factory, identity.value) }
        compose.waitForIdle()
        compose.runOnIdle { identity.value = Any() }
        compose.waitForIdle()
        assertEquals(2, created)
    }

    @Test fun reenteringAfterReadOnlyReviewCannotRetainTheDisposedCaptureRepository() {
        val shown = mutableStateOf(true)
        val chapter = FixedLearningChapter("isolated-entry", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        var created = 0
        var observed: ReadSpeakViewModel? = null
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                created++
                return ReadSpeakViewModel(chapter, "s1", ReadSpeakRepository { _, _ -> ReadSpeakFeedbackResult.Failed }) as T
            }
        }
        compose.setContent { if (shown.value) observed = rememberReadSpeakViewModel("isolated-entry", "s1", factory) }
        compose.waitForIdle()
        val first = observed
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()
        assertEquals("The entry must bind a fresh ViewModel to its fresh audio repository", 2, created)
        assertNotSame(first, observed)
    }
}
