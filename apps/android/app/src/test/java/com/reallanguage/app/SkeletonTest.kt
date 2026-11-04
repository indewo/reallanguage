package com.reallanguage.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SkeletonTest {
    @Test
    fun packageIdentityIsStable() {
        assertEquals("com.reallanguage.app", MainActivity::class.java.packageName)
    }
}
