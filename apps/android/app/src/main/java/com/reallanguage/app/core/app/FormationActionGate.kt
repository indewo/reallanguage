package com.reallanguage.app.core.app

import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal class FormationProgressActions(
    val stateChanged: (HomeDiaryRoute, Int) -> Unit,
    val navigate: (HomeDiaryRoute) -> Unit,
)

/** Save errors belong to the current accepted input, never a fabricated empty or fixed chapter. */
@Composable
internal fun FormationActionGate(
    route: HomeDiaryRoute,
    save: suspend (HomeDiaryRoute, Int, () -> Boolean) -> Boolean,
    onNavigate: (HomeDiaryRoute) -> Unit,
    initialSentenceIndex: Int = (route as? HomeDiaryRoute.LearningTargetSelection)?.initialSentenceIndex ?: 0,
    content: @Composable (HomeDiaryRoute, FormationProgressActions) -> Unit,
) {
    if (formationSourceEntryId(route) == null) {
        content(route, FormationProgressActions({ _, _ -> }, onNavigate)); return
    }
    key(route) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val currentSave by rememberUpdatedState(save)
        val currentNavigate by rememberUpdatedState(onNavigate)
        val live = remember { FormationLiveInput(route, initialSentenceIndex) }
        var busy by remember { mutableStateOf(false) }
        var autoSave by remember { mutableStateOf<Job?>(null) }
        DisposableEffect(live) { onDispose { live.active = false; autoSave?.cancel() } }

        fun submit(attempt: FormationSaveAttempt) {
            if (!live.active || busy) return
            live.version++
            val version = live.version
            autoSave?.cancel()
            busy = true
            scope.launch {
                val allowed = { live.active && live.version == version }
                val success = try {
                    currentSave(attempt.route, attempt.index, allowed) && allowed() &&
                        (attempt.destination == null || formationSourceEntryId(attempt.destination) == null ||
                            currentSave(attempt.destination, attempt.index, allowed))
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (!allowed()) return@launch
                busy = false
                if (success) {
                    if (attempt.destination != null) currentNavigate(attempt.destination)
                } else {
                    Toast.makeText(context, "学习进度保存失败，请重试。", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val actions = FormationProgressActions(
            stateChanged = { updated, index ->
                if (live.active && !busy &&
                    formationSourceEntryId(updated) == formationSourceEntryId(route) &&
                    (updated != live.route || index != live.index)) {
                    live.route = updated; live.index = index; live.version++
                    val version = live.version
                    autoSave?.cancel()
                    autoSave = scope.launch {
                        // A button may update selection and navigate in one frame. Give the
                        // navigation save a chance to cancel this debounce so the same snapshot
                        // is not written twice.
                        yield()
                        val allowed = { live.active && live.version == version && !busy }
                        if (!allowed()) return@launch
                        val success = try { currentSave(updated, index, allowed) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        if (!success && allowed()) {
                            Toast.makeText(context, "学习进度保存失败，请重试。", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            navigate = { next -> if (live.active && !busy) submit(FormationSaveAttempt(live.route, live.index, next)) },
        )
        BackHandler(enabled = busy) { /* Keep the accepted page visible until its save finishes. */ }
        content(live.route, actions)
    }
}

private class FormationLiveInput(var route: HomeDiaryRoute, var index: Int) {
    var active = true
    var version = 0L
}
private data class FormationSaveAttempt(val route: HomeDiaryRoute, val index: Int, val destination: HomeDiaryRoute?)
