package com.reallanguage.app.feature.learning.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Animate the new exercise only. Never retain an outgoing answer or route effects. */
@Composable
internal fun Modifier.learningContentEntrance(identity: Any): Modifier {
    val progress = remember(identity) { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8f * density
    }
}
