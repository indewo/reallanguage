package com.reallanguage.app.feature.learning.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import kotlinx.coroutines.CancellationException
import com.reallanguage.app.feature.learning.domain.LearningCheckpointRepository
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint

@Composable
fun LearningCheckpointGate(
    checkpoint: OrdinaryLearningCheckpoint?,
    repository: LearningCheckpointRepository,
    onReturn: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (checkpoint == null) { content(); return }
    var result by remember(checkpoint, repository) { mutableStateOf<CheckpointSaveResult?>(null) }
    var attempt by remember(checkpoint, repository) { mutableIntStateOf(0) }
    LaunchedEffect(checkpoint, repository, attempt) {
        result = null
        result = try { repository.save(checkpoint) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CheckpointSaveResult.Failed }
    }
    when (result) {
        CheckpointSaveResult.Saved -> content()
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RlColor.Primary)
        }
        CheckpointSaveResult.Failed -> Column(
            Modifier.fillMaxSize().padding(RlSpace.Space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.learning_exit_save_failed), color = RlColor.Error)
            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("checkpoint-retry")) {
                Text(stringResource(R.string.diary_retry))
            }
            TextButton(onClick = onReturn) { Text(stringResource(R.string.formation_back)) }
        }
    }
}
