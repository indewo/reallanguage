package com.reallanguage.app.feature.recording.data

import java.io.File
import java.security.MessageDigest

internal fun accountRecordingStorageKey(accountId: String): String =
    MessageDigest.getInstance("SHA-256").digest("reallanguage:recordings:$accountId".toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun accountRecordingsDirectory(filesDir: File, accountId: String): File =
    File(File(filesDir, "recordings"), accountRecordingStorageKey(accountId))
