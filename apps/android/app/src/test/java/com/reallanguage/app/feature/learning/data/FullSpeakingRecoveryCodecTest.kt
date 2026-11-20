package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.ReadingFeedback
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAction
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Pure format checks; these do not substitute for encrypted file persistence on the phone. */
class FullSpeakingRecoveryCodecTest {
    @Test fun recognitionRoundTripsExactlyIncludingEmptyAndUnknownLegacyText() {
        for (transcript in listOf(null, "", "I'd like to learn English—future university life. café 😀")) {
            val snapshot = FullSpeakingRecoverySnapshot("recording", FullSpeakingRecoveryPhase.TrainingDelivered,
                training = ReadingFeedback("反馈", ReadingFeedbackAction.CONTINUE, .8, transcript = transcript))
            assertEquals(snapshot, FullSpeakingRecoveryCodec.decode(FullSpeakingRecoveryCodec.encode(snapshot)))
        }
    }

    @Test fun oldDeliveredFeedbackLoadsWithoutFabricatingRecognizedWords() {
        for (version in 1..2) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                fun text(value: String) { val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
                out.writeInt(0x524c5236); out.writeInt(version); out.writeInt(2)
                text("recording"); out.writeInt(-1); out.writeBoolean(true)
                out.writeInt(1); text("原反馈")
                if (version == 2) { out.writeBoolean(true); out.writeDouble(.8); out.writeInt(0) }
                out.writeBoolean(false); out.writeBoolean(false); out.writeInt(-1); out.writeBoolean(false)
            }
            val restored = FullSpeakingRecoveryCodec.decode(bytes.toByteArray())
            assertEquals("原反馈", restored.training?.feedbackZh)
            assertNull(restored.training?.transcript)
            assertEquals(if (version == 1) null else .8, restored.training?.feedbackConfidence)
        }
    }

    @Test fun pendingRequestAndTerminalRecordDoNotGainFeedbackOrRecognition() {
        val pending = FullSpeakingRecoverySnapshot("recording", FullSpeakingRecoveryPhase.TrainingPending, requestBody = "original request")
        val cleared = FullSpeakingRecoverySnapshot("recording", FullSpeakingRecoveryPhase.Cleared)
        for (snapshot in listOf(pending, cleared)) {
            val bytes = FullSpeakingRecoveryCodec.encode(snapshot)
            assertEquals(snapshot, FullSpeakingRecoveryCodec.decode(bytes))
            assertThrows(IllegalArgumentException::class.java) { FullSpeakingRecoveryCodec.decode(bytes + byteArrayOf(1)) }
        }
    }
}
