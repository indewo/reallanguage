package com.reallanguage.app.core.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker

internal data class ConnectionPlaybackPolicy(val isAllowed: () -> Boolean, val interrupted: Boolean)
internal val LocalConnectionPlaybackPolicy = staticCompositionLocalOf { ConnectionPlaybackPolicy({ true }, false) }
internal val LocalSentenceSpeakerFactory = staticCompositionLocalOf<(Context, () -> Boolean) -> AndroidSentenceSpeaker> {
    { context, allowed -> AndroidSentenceSpeaker(context, allowed) }
}

/** Existing route still owns close(). A restored connection never triggers another playback. */
@Composable
internal fun rememberConnectionAwareSentenceSpeaker(key: Any? = Unit): AndroidSentenceSpeaker {
    val context = LocalContext.current
    val policy = LocalConnectionPlaybackPolicy.current
    val factory = LocalSentenceSpeakerFactory.current
    val speaker = remember(key) { factory(context, policy.isAllowed) }
    LaunchedEffect(speaker, policy.interrupted) {
        if (policy.interrupted) speaker.stop()
    }
    return speaker
}
