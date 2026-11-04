package com.reallanguage.app.core.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordResetDeepLinkTest {
    @Test fun parsesAValidPasswordResetLink() {
        assertEquals(
            PasswordResetDeepLink("challenge-1", "bearer-1"),
            parsePasswordResetDeepLink(
                "reallanguage://password-reset/verify?challengeId=challenge-1&bearer=bearer-1",
            ),
        )
    }

    @Test fun rejectsWrongRoutesAndMissingSecrets() {
        assertNull(parsePasswordResetDeepLink("https://reallanguage.me/password-reset"))
        assertNull(parsePasswordResetDeepLink("reallanguage://password-reset/verify?bearer=secret"))
        assertNull(parsePasswordResetDeepLink("reallanguage://password-reset/verify?challengeId=id"))
    }
}
