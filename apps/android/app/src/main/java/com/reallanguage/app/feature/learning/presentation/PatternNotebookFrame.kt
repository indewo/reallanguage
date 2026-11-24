package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample

/** The approved notebook layout keeps actions and context outside the exercise scroll. */
@Composable
internal fun PatternNotebookFrame(state: PatternSpeakingUiState, scroll: ScrollState,
    onBack: () -> Unit, targetAssessment: @Composable (LockedSpeakingPattern) -> Unit,
    pinSample: Boolean, onPronounce: () -> Unit,
    footer: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    NotebookPage("第 ${state.chapter.chapterNumber} 章 · 句式会说",
        LocalLearningExitAction.current ?: onBack, "退出学习", footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(.25f)) {
                  Text(if (state.stage == PatternSpeakingStage.READING)
                    "例句指读 · ${state.completedReadingExampleIds.size} / ${state.examples.size}"
                    else "场景 ${state.responseIndex + 1} / ${state.examples.size}",
                    Modifier.testTag("pattern-speaking-scene-progress"), color = RlColor.Secondary, fontSize = 13.sp)
                  if (state.recording) Text("正在收音", color = RlColor.ActionVivid, fontSize = 12.sp)
                }
                Row(Modifier.weight(.75f), horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer)
            }
        }) {
        LearningPaper(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.currentPattern?.structure.orEmpty(), fontSize = 18.sp, color = RlColor.Secondary)
                Text(state.currentPattern?.explanationZh.orEmpty(), Modifier.weight(1f), fontSize = 14.sp)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = maxWidth < 540.dp || LocalDensity.current.fontScale > 1.3f
            val sidebar: @Composable (Modifier) -> Unit = { pane ->
                NotebookPanel(pane, tint = androidx.compose.ui.graphics.Color(0xFFF0F0D9)) {
                    LearningRibbon(if (state.stage == PatternSpeakingStage.READING) "例句指读" else "具体回应")
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.currentResponseExample?.sceneZh ?: state.currentPattern?.explanationZh.orEmpty(), fontSize = 13.sp, lineHeight = 18.sp)
                        state.currentResponseExample?.let { Text(it.aiPromptEn, fontSize = 12.sp, color = RlColor.TextSecondary) }
                        state.currentPattern?.let { targetAssessment(it) }
                    }
                    if (!compact) Image(painterResource(learningThemeArtwork(state.chapter.sentences.joinToString { it.textEn })),
                        null, Modifier.fillMaxWidth().height(48.dp), contentScale = ContentScale.Fit)
                }
            }
            val exercise: @Composable (Modifier) -> Unit = { pane ->
                val progress: @Composable () -> Unit = {
                    if (state.stage == PatternSpeakingStage.RESPONDING) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            state.examples.forEachIndexed { index, _ ->
                                LearningRibbon("${index + 1}", Modifier.weight(1f), if (index == state.responseIndex) RlColor.ActionVivid else RlColor.Secondary)
                            }
                        }
                        if (pinSample) state.currentResponseExample?.let {
                            PatternExamplePrompt(it, onPronounce, !state.submitting && !state.recording && !state.technicalRetryPending && !state.technicalRetryBlocked)
                        }
                    }
                }
                NotebookPanel(pane.testTag("pattern-exercise-paper"), scroll = scroll, scrollTag = "pattern-exercise-content", header = progress) {
                    if (compact) {
                        Text(state.currentResponseExample?.sceneZh ?: state.currentPattern?.explanationZh.orEmpty(), fontSize = 14.sp)
                        state.currentResponseExample?.let { Text(it.aiPromptEn, color = RlColor.TextSecondary, fontSize = 12.sp) }
                    }
                    content()
                    if (compact) {
                        state.currentPattern?.let { targetAssessment(it) }
                    }
                }
            }
            if (compact) exercise(Modifier.fillMaxSize())
            else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                sidebar(Modifier.weight(.25f).fillMaxHeight())
                exercise(Modifier.weight(.75f).fillMaxHeight())
            }
        }
    }
}

@Composable
internal fun PatternExamplePrompt(example: PatternPracticeExample, onPronounce: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onPronounce, enabled = enabled) { Icon(Icons.Outlined.PlayCircleOutline, "播放示范", tint = RlColor.Secondary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(example.targetResponseEn, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
            Text(example.targetResponseZh, color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

/** Decoration is selected only from bundled art; it never triggers image generation. */
internal fun learningThemeArtwork(text: String, templateId: String? = null): Int {
    val selected = templateId?.let { sceneArtworkPresets[it] }
    if (selected != null) return if (templateId == "shopping" && coffeeArtworkWords.containsMatchIn(text))
        R.drawable.rl_coffee_polaroid_20261124 else selected.first
    if (templateId != null) return R.drawable.rl_diary_open_notebook
    val matches = sceneArtworkPresets.values.filter { it.second.containsMatchIn(text) }
    return if (matches.size == 1) {
        if (coffeeArtworkWords.containsMatchIn(text)) R.drawable.rl_coffee_polaroid_20261124 else matches.single().first
    } else R.drawable.rl_diary_open_notebook
}
private val coffeeArtworkWords = Regex("(?i)\\b(coffee|café|cafe|latte|americano)\\b|咖啡|拿铁")

// Same eight preset IDs as PublicSceneTemplates. Ambiguous source text stays neutral.
internal val sceneArtworkPresets = linkedMapOf(
    "work" to (R.drawable.rl_scene_response_office to Regex("(?i)\\b(office|meeting|colleague|project|deadline)\\b|工作|会议|同事|项目")),
    "learning" to (R.drawable.rl_v73_learning_open_book to Regex("(?i)\\b(classroom|homework|assignment|study group)\\b|课堂|作业|学习小组")),
    "social" to (R.drawable.home_scene_collage_v2 to Regex("(?i)\\b(party|introduce myself|greeting)\\b|社交|自我介绍|聚会")),
    "planning" to (R.drawable.rl_daily_reminder_calendar_collage_v2 to Regex("(?i)\\b(reschedule|schedule|cancel|arrangement)\\b|改期|安排时间|取消计划")),
    "travel" to (R.drawable.rl_scene_travel_ephemera to Regex("(?i)\\b(hotel|airport|subway|train|taxi|ticket)\\b|酒店|机场|地铁|车票|旅行")),
    "shopping" to (R.drawable.rl_billing_receipt_20261124 to Regex("(?i)\\b(coffee|café|cafe|latte|americano|shopping|restaurant|purchase)\\b|咖啡|拿铁|购物|餐厅")),
    "help" to (R.drawable.home_scene_collage_v2 to Regex("(?i)\\b(ask for help|repeat that|confirm information)\\b|请求帮助|核对信息|请再说一遍")),
    "services" to (R.drawable.rl_billing_receipt_20261124 to Regex("(?i)\\b(repair|customer service|complaint)\\b|维修|客服|投诉")),
)
