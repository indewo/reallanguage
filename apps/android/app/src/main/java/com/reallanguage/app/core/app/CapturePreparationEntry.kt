package com.reallanguage.app.core.app

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun CapturePreparationEntry(key: Any, generate: suspend () -> HomeDiaryRoute.Bilingual,
    onReady: (HomeDiaryRoute.Bilingual) -> Unit, onBlocked: (String) -> Unit, onReturn: () -> Unit) {
    var attempt by remember(key) { mutableIntStateOf(0) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    val currentReady by rememberUpdatedState(onReady)
    val currentBlocked by rememberUpdatedState(onBlocked)
    val currentGenerate by rememberUpdatedState(generate)
    LaunchedEffect(key, attempt) {
        try { currentReady(currentGenerate()) }
        catch (cancel: CancellationException) { throw cancel }
        catch (blocked: CaptureContentBlocked) { currentBlocked(blocked.caseId) }
        catch (e: Exception) { failure = e.localizedMessage ?: "中英对照生成失败，请重试。" }
    }
    if (failure != null) AlertDialog(onDismissRequest = onReturn, text = { Text(failure.orEmpty()) },
        dismissButton = { TextButton(onClick = onReturn) { Text("返回") } },
        confirmButton = { TextButton(onClick = { failure = null; attempt++ }) { Text("重试") } })
    else androidx.compose.ui.window.Dialog(onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text("正在整理中英对照", Modifier.padding(start = 12.dp))
        }
        }
    }
}
