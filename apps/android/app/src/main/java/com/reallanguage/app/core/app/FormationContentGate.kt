package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.reallanguage.app.feature.formation.domain.FormationRecoverySnapshot
import kotlinx.coroutines.CancellationException

/**
 * Mounting a billable page requires an exact saved-and-reread accepted input.
 *
 * The previous real page remains mounted while this local gate runs. There is deliberately no
 * loading/failure page: a persistence operation is not a user-facing learning step.
 */
@Composable
internal fun FormationContentGate(
    route: HomeDiaryRoute,
    load: suspend (HomeDiaryRoute) -> FormationRecoverySnapshot?,
    onCached: (HomeDiaryRoute) -> Unit,
    onRestore: (HomeDiaryRoute) -> Unit,
    content: @Composable (HomeDiaryRoute, FormationRecoverySnapshot?) -> Unit,
) {
    val context = LocalContext.current
    val currentCached by rememberUpdatedState(onCached)
    val currentRestore by rememberUpdatedState(onRestore)
    val currentLoad by rememberUpdatedState(load)
    var displayedRoute by remember {
        mutableStateOf(if (formationSourceEntryId(route) == null) route else HomeDiaryRoute.Home)
    }
    var displayedSnapshot by remember { mutableStateOf<FormationRecoverySnapshot?>(null) }

    LaunchedEffect(route) {
        if (formationSourceEntryId(route) == null) {
            displayedRoute = route
            displayedSnapshot = null
            return@LaunchedEffect
        }
        val requested = route
        val saved = try { currentLoad(requested) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (saved == null) {
            Toast.makeText(context, "学习进度读取失败，请重试。", Toast.LENGTH_SHORT).show()
            currentRestore(displayedRoute)
            return@LaunchedEffect
        }
        val continuation = cachedFormationContinuation(requested, saved)
        displayedSnapshot = saved
        displayedRoute = continuation ?: requested
        if (continuation != null) currentCached(continuation)
    }

    content(displayedRoute, displayedSnapshot)
}
