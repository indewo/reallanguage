package com.reallanguage.app.feature.home.data

import com.reallanguage.app.feature.home.domain.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64

/** Atomic compare-and-set is required across repository instances in the app process. */
interface DailyReminderPreferenceStore {
    fun read(accountId: String): DailyReminderPreferences
    fun compareAndSet(accountId: String, expected: DailyReminderPreferences, replacement: DailyReminderPreferences): Boolean
    fun clear(accountId: String): Boolean
}

/** Small string preference port, allowing persistence/failed commits to be isolated in tests. */
interface ReminderStringPreferences {
    fun read(key: String): String?
    fun write(key: String, value: String?): Boolean
}

class PersistentDailyReminderPreferenceStore(private val preferences: ReminderStringPreferences) : DailyReminderPreferenceStore {
    override fun read(accountId: String): DailyReminderPreferences = synchronized(lock) { readKey(key(accountId)) }

    override fun compareAndSet(accountId: String, expected: DailyReminderPreferences, replacement: DailyReminderPreferences): Boolean = synchronized(lock) {
        val key = key(accountId)
        if (readKey(key) != expected) return@synchronized false
        val encoded = encode(replacement)
        durableWrite(key, encoded)
    }

    override fun clear(accountId: String): Boolean = synchronized(lock) { durableWrite(key(accountId), null) }

    private fun readKey(key: String): DailyReminderPreferences = preferences.read(key)?.let(::decode) ?: DailyReminderPreferences()

    private fun durableWrite(key: String, encoded: String?): Boolean {
        val previous = preferences.read(key)
        return try {
            if (preferences.write(key, encoded) && preferences.read(key) == encoded) true
            else {
                // SharedPreferences can change its in-memory map even when disk commit fails.
                // Restore the previous value so retry cannot acknowledge an uncommitted intro/claim.
                preferences.write(key, previous)
                false
            }
        } catch (_: Exception) {
            runCatching { preferences.write(key, previous) }
            false
        }
    }

    private fun key(accountId: String): String {
        require(accountId.isNotBlank())
        return "account-" + MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun encode(value: DailyReminderPreferences): String {
        validate(value)
        return listOf("1", if (value.introHandled) "1" else "0", if (value.enabled) "1" else "0", value.time.toString(),
            encodedText(value.zoneId), value.permission.name, value.revision.toString(), value.claimedLocalDates.sorted().joinToString(","),
            encodedText(value.introCommandId), value.introChoice?.name.orEmpty()).joinToString("|")
    }

    private fun decode(value: String): DailyReminderPreferences {
        val parts = value.split('|')
        require(parts.size == 10 && parts[0] == "1")
        fun bool(index: Int): Boolean { require(parts[index] in setOf("0", "1")); return parts[index] == "1" }
        val dates = if (parts[7].isEmpty()) emptyList() else parts[7].split(',').map(LocalDate::parse)
        require(dates.distinct().size == dates.size)
        return DailyReminderPreferences(bool(1), bool(2), LocalTime.parse(parts[3]), decodedText(parts[4]),
            ReminderPermission.valueOf(parts[5]), parts[6].toLong(), dates.toSet(), decodedText(parts[8]),
            parts[9].takeIf(String::isNotEmpty)?.let(DailyReminderChoice::valueOf)).also(::validate)
    }

    private fun validate(value: DailyReminderPreferences) {
        require(value.time.second == 0 && value.time.nano == 0 && value.revision >= 0)
        require(!value.enabled || (value.introHandled && value.zoneId != null))
        value.zoneId?.let(ZoneId::of)
        require((value.introCommandId == null) == (value.introChoice == null))
        require(value.introCommandId == null || value.introCommandId.isNotBlank())
    }

    private fun encodedText(value: String?): String = value?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
    }.orEmpty()
    private fun decodedText(value: String): String? = value.takeIf(String::isNotEmpty)?.let {
        String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8)
    }

    private companion object { val lock = Any() }
}
