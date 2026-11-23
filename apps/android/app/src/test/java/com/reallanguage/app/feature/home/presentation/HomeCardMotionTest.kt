package com.reallanguage.app.feature.home.presentation

import org.junit.Assert.*
import org.junit.Test

class HomeCardMotionTest {
    @Test fun firstHalfKeepsOldPaperLayoutAndSecondHalfKeepsNewLayout() {
        for (index in 0..4) {
            val old = homeCardInkFrame(index, 0f).slot
            val next = homeCardInkFrame(index, 1f).slot
            for (position in listOf(.05f, .25f, .45f)) assertEquals(old, homeCardInkFrame(index, position).slot)
            for (position in listOf(.55f, .75f, .95f)) assertEquals(next, homeCardInkFrame(index, position).slot)
            assertTrue(homeCardInkFrame(index, .05f).opacity > homeCardInkFrame(index, .25f).opacity)
            assertTrue(homeCardInkFrame(index, .25f).opacity > homeCardInkFrame(index, .45f).opacity)
            assertTrue(homeCardInkFrame(index, .55f).opacity < homeCardInkFrame(index, .75f).opacity)
            assertTrue(homeCardInkFrame(index, .75f).opacity < homeCardInkFrame(index, .95f).opacity)
        }
    }

    @Test fun everyLayoutSwapIsHiddenIncludingReverseAndWrap() {
        for (step in -7..7) {
            for (index in 0..4) {
                assertEquals(0f, homeCardInkFrame(index, step + .499f).opacity, .00001f)
                assertEquals(0f, homeCardInkFrame(index, step + .501f).opacity, .00001f)
                assertEquals(1f, homeCardInkFrame(index, step.toFloat()).opacity, .00001f)
            }
        }
        assertEquals(homeCardInkFrame(0, 0f), homeCardInkFrame(0, 5f))
        assertEquals(homeCardInkFrame(0, 0f), homeCardInkFrame(0, -5f))
    }
}
