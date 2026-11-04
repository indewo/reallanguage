package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.ChapterEntryViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChapterEntryReentryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun entryReloadsAndCanStartAgainAfterLeavingWithoutReusingCommittedNavigation() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val shown = mutableStateOf(true)
        var loads = 0
        var starts = 0
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I explained my idea.", "我解释了想法。", true)))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChapterEntryViewModel("c1", FixedChapterRepository {
                loads++
                FixedChapterLoadResult.Loaded(chapter)
            }) as T
        }
        rule.setContent { RlTheme {
            if (shown.value) ChapterEntryContent("c1", factory,
                onStart = { _, _ -> starts++; shown.value = false }, onReturn = { shown.value = false })
            else TextButton(onClick = { shown.value = true }) { Text("重新进入章节") }
        } }
        rule.onNodeWithTag("chapter_entry_start").performClick()
        rule.onNodeWithText("重新进入章节").performClick()
        rule.onNodeWithTag("chapter_entry_start").performClick()
        rule.runOnIdle { assertEquals(2, loads); assertEquals(2, starts) }
    }
}
