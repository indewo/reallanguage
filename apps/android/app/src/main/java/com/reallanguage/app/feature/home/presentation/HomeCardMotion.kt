package com.reallanguage.app.feature.home.presentation

import kotlin.math.abs
import kotlin.math.floor

/** Ink changes its paper-local layout only while invisible, including a reversed drag. */
internal data class HomeCardInkFrame(val slot: Int, val opacity: Float)

internal fun homeCardInkFrame(cardIndex: Int, position: Float, count: Int = 5): HomeCardInkFrame {
    val anchor = floor(position + .5f).toInt()
    val slot = Math.floorMod(cardIndex - anchor + count / 2, count) - count / 2
    val distanceFromAnchor = abs(position - anchor)
    val t = (distanceFromAnchor / .46f).coerceIn(0f, 1f)
    return HomeCardInkFrame(slot, 1f - t * t * (3f - 2f * t))
}
