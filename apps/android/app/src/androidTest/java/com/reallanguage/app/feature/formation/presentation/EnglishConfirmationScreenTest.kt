package com.reallanguage.app.feature.formation.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class EnglishConfirmationScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun uniqueCandidateIsShownDirectlyWithoutSingleChoiceControl() {
        val state = EnglishConfirmationUiState(
            chapters = listOf(
                EnglishChapterDraft(
                    "c1",
                    listOf(
                        EnglishSentenceDraft(
                            "u1", "我今天很开心。", true,
                            listOf(EnglishCandidate("e1", "I was happy today.")),
                        ),
                    ),
                ),
            ),
            selectedCandidateIds = mapOf("u1" to "e1"),
            continueEnabled = true,
        )
        composeRule.setContent { RlTheme { EnglishConfirmationScreen(state, {}) } }
        composeRule.onNodeWithText("我今天很开心。").assertIsDisplayed()
        composeRule.onNodeWithText("I was happy today.").assertIsDisplayed()
        composeRule.onAllNodesWithText("选择此表达").assertCountEquals(0)
        composeRule.onNodeWithTag("english_confirmation_header").assertIsDisplayed()
        composeRule.onNodeWithTag("english_confirmation_ledger").assertIsDisplayed()
        composeRule.onNodeWithTag("english_confirmation_acceptance").assertIsDisplayed()
    }

    @Test
    fun equalCandidatesAreAllShownWithChineseSource() {
        val actions = mutableListOf<EnglishConfirmationAction>()
        val state = EnglishConfirmationUiState(
            chapters = listOf(
                EnglishChapterDraft(
                    "c1",
                    listOf(
                        EnglishSentenceDraft(
                            "u1", "我们聊了很久。", false,
                            listOf(
                                EnglishCandidate("e1", "We talked for a long time."),
                                EnglishCandidate("e2", "We had a long conversation."),
                            ),
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent { RlTheme { EnglishConfirmationScreen(state, actions::add) } }
        composeRule.onNodeWithText("我们聊了很久。").assertIsDisplayed()
        composeRule.onNodeWithText("We talked for a long time.").assertIsDisplayed()
        composeRule.onNodeWithText("We had a long conversation.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("表达 1").assertIsDisplayed()
        composeRule.onNodeWithText("表达 2").assertIsDisplayed()
        composeRule.onNodeWithText("中文原句").assertIsDisplayed()
        composeRule.onNodeWithTag("english_candidate_u1_e1").assertIsDisplayed()
        composeRule.onNodeWithTag("english_candidate_u1_e2").assertIsDisplayed()
        composeRule.onNodeWithText("We talked for a long time.").performClick()
        assert(actions.contains(EnglishConfirmationAction.CandidateSelected("u1", "e1")))
    }

    @Test
    fun longSentenceContentScrollsInsideTheLedgerWhileFooterRemainsReachable() {
        val longChinese = "这是一句异常长但仍需完整展示的中文内容，必须允许用户上下滚动查看全部上下文，不能用省略号隐藏任何内容。".repeat(3)
        val longEnglish = "This is an unusually long expression that must remain readable inside the confirmation ledger without hiding its ending. ".repeat(5)
        val state = EnglishConfirmationUiState(
            chapters = listOf(
                EnglishChapterDraft(
                    "c1",
                    (1..4).map { index ->
                        EnglishSentenceDraft(
                            "u$index",
                            if (index == 4) longChinese else "第 $index 句中文。",
                            index == 4,
                            listOf(EnglishCandidate("e$index", if (index == 4) longEnglish else "English sentence $index.")),
                        )
                    },
                ),
            ),
            selectedCandidateIds = (1..4).associate { "u$it" to "e$it" },
            continueEnabled = true,
        )

        composeRule.setContent {
            RlTheme { EnglishConfirmationScreen(state, {}, Modifier.size(600.dp, 360.dp)) }
        }
        composeRule.onNodeWithTag("english_confirmation_acceptance").assertIsDisplayed()
        composeRule.onNodeWithText(longChinese).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(longEnglish).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("english_confirmation_acceptance").assertIsDisplayed()
        composeRule.onNodeWithText("继续选择学习目标").assertIsDisplayed()
    }

    @Test
    fun fourSentenceSourceKeepsOpeningAndEndingAndUsesCompactFinalActionRow() {
        val state = EnglishConfirmationUiState(
            chapters = listOf(
                EnglishChapterDraft(
                    "c1",
                    listOf(
                        EnglishSentenceDraft("u1", "我把车停在了公司。", false, listOf(EnglishCandidate("e1", "I left my car at the office."))),
                        EnglishSentenceDraft(
                            "u2", "但是我人在家。", true,
                            listOf(EnglishCandidate("e2", "But I'm home."), EnglishCandidate("e2b", "But I'm at home.")),
                        ),
                        EnglishSentenceDraft("u3", "所以今天上班我就需要通过其他的方式去公司。", false, listOf(EnglishCandidate("e3", "So I need to find another way to get to work today."))),
                        EnglishSentenceDraft("u4", "有时候住得远也是一个大问题。", false, listOf(EnglishCandidate("e4", "Sometimes living far away can be a real problem."))),
                    ),
                ),
            ),
            selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2", "u3" to "e3", "u4" to "e4"),
            continueEnabled = true,
        )

        composeRule.setContent {
            RlTheme { EnglishConfirmationScreen(state, {}, Modifier.size(780.dp, 360.dp)) }
        }

        composeRule.onNodeWithText("我把车停在了公司。").assertIsDisplayed()
        composeRule.onNodeWithText("有时候住得远也是一个大问题。").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("确认英文是否完整保留了你的原意。").assertCountEquals(0)
        composeRule.onAllNodesWithText("唯一合格表达已自动采用").assertCountEquals(0)
        composeRule.onNodeWithTag("english_confirmation_stamp").assertIsDisplayed()
        composeRule.onNodeWithText("返回重点句选择").assertIsDisplayed()
        composeRule.onNodeWithText("继续选择学习目标").assertIsDisplayed()
    }

    @Test
    fun wideCandidateCardsUseTheLongestOptionAsTheirSharedFrameAndStampMovesLeft() {
        val state = EnglishConfirmationUiState(
            chapters = listOf(
                EnglishChapterDraft(
                    "c1",
                    listOf(
                        EnglishSentenceDraft(
                            "u1",
                            "这个套件已经收获了被几乎所有软件更多的用户，",
                            true,
                            listOf(
                                EnglishCandidate(
                                    "e1",
                                    "this app has gotten more users than almost any other app,",
                                ),
                                EnglishCandidate("e2", "this suite has more users,"),
                            ),
                        ),
                    ),
                ),
            ),
            selectedCandidateIds = mapOf("u1" to "e1"),
            continueEnabled = true,
        )

        composeRule.setContent {
            RlTheme { EnglishConfirmationScreen(state, {}, Modifier.size(780.dp, 360.dp)) }
        }

        val first = composeRule.onNodeWithTag("english_candidate_u1_e1")
            .fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag("english_candidate_u1_e2")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, second.width, 0.5f)
        assertEquals(first.height, second.height, 0.5f)
        composeRule.onNodeWithTag("english_confirmation_stamp").assertIsDisplayed()
    }
}
