package com.reallanguage.app.feature.home.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessagesReadAllJsonTest {
    private val codec = JsonMessagesReadAllWireCodec()

    @Test fun zeroAndNonnegativeIntegerAcknowledgmentsAreValid() {
        for (body in listOf("{\"updatedCount\":0}", "{\"updatedCount\":3}", "{\"updatedCount\":201}")) {
            assertTrue(body, codec.accepted(body))
        }
    }

    @Test fun emptyMalformedAndNonObjectAcknowledgmentsAreRejected() {
        for (body in listOf("", "not-json", "[]", "null", "{", "true")) {
            assertFalse(body, codec.accepted(body))
        }
    }

    @Test fun missingAndNullCountCannotBeCoercedToZero() {
        for (body in listOf("{}", "{\"updatedCount\":null}")) {
            assertFalse(body, codec.accepted(body))
        }
    }

    @Test fun stringsBooleansAndContainersCannotBeCoercedToInteger() {
        for (value in listOf("\"0\"", "\"3\"", "true", "false", "[]", "{}")) {
            assertFalse(value, codec.accepted("{\"updatedCount\":$value}"))
        }
    }

    @Test fun negativeFractionalAndNonFiniteNumbersAreRejected() {
        for (value in listOf("-1", "0.5", "1.25", "1e999")) {
            assertFalse(value, codec.accepted("{\"updatedCount\":$value}"))
        }
    }

    @Test fun additionalFieldsDoNotInvalidateTheExistingExtensibleResponseContract() {
        assertTrue(codec.accepted("{\"updatedCount\":0,\"futureMetadata\":\"ignored\"}"))
    }

    @Test fun integerAcknowledgmentMatchesTheServerZodSafeIntegerBoundary() {
        assertTrue(codec.accepted("{\"updatedCount\":9007199254740991}"))
        for (value in listOf("9007199254740992", "9007199254740993", "9223372036854775807", "1e20")) {
            assertFalse(value, codec.accepted("{\"updatedCount\":$value}"))
        }
    }
}
