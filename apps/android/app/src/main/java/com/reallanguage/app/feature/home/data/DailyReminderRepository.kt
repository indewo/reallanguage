package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.*
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

class DailyReminderRepository(
    private val store: DailyReminderPreferenceStore,
    private val identity: ReminderLocalIdentity,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val permission: () -> ReminderPermission,
) : DailyReminderIntroRepository, DailyReminderDeliveryPreferences {
    constructor(store: DailyReminderPreferenceStore, runtime: SessionRuntime, clock: Clock,
        zone: () -> ZoneId, permission: () -> ReminderPermission) :
        this(store, RuntimeReminderLocalIdentity(runtime), clock, zone, permission)

    private val owner = identity.accountId
    private suspend fun active() = owner != null && identity.isCurrent()

    suspend fun load(): DailyReminderPreferences? {
        if (!active()) return null
        val saved = try { store.read(requireNotNull(owner)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        return saved.takeIf { active() }
    }

    override suspend fun recordChoice(choice: DailyReminderChoice, commandId: String): DailyReminderIntroResult {
        if (commandId.isBlank()) return DailyReminderIntroResult.Failed
        val current = load() ?: return DailyReminderIntroResult.Failed
        if (current.introHandled) return if (current.introCommandId == commandId && current.introChoice == choice)
            DailyReminderIntroResult.Saved else DailyReminderIntroResult.Failed
        return try {
            val actualPermission = permission()
            if (choice == DailyReminderChoice.ENABLED && actualPermission != ReminderPermission.GRANTED) return DailyReminderIntroResult.Failed
            val changed = current.copy(introHandled = true, enabled = choice == DailyReminderChoice.ENABLED,
                zoneId = if (choice == DailyReminderChoice.ENABLED) current.zoneId ?: zone().id else current.zoneId,
                permission = actualPermission, introCommandId = commandId, introChoice = choice)
            if (save(current, changed)) DailyReminderIntroResult.Saved else DailyReminderIntroResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DailyReminderIntroResult.Failed }
    }

    suspend fun setEnabled(enabled: Boolean): ReminderPreferenceChange = change { current ->
        val actualPermission = permission()
        check(!enabled || actualPermission == ReminderPermission.GRANTED)
        current.copy(enabled = enabled, introHandled = current.introHandled || enabled, permission = actualPermission,
            zoneId = if (enabled) current.zoneId ?: zone().id else current.zoneId)
    }

    suspend fun setTime(time: LocalTime): ReminderPreferenceChange = change { current ->
        require(time.second == 0 && time.nano == 0)
        current.copy(time = time)
    }

    suspend fun recordPermission(): ReminderPreferenceChange = change { it.copy(permission = permission()) }

    suspend fun saveSettings(
        expectedEnabled: Boolean, expectedTime: LocalTime, enabled: Boolean, time: LocalTime,
    ): ReminderPreferenceChange {
        val current = load() ?: return ReminderPreferenceChange(false, owner)
        val saved = try {
            require(expectedTime.second == 0 && expectedTime.nano == 0 && time.second == 0 && time.nano == 0)
            val actualPermission = permission()
            check(!enabled || actualPermission == ReminderPermission.GRANTED)
            // A retry may observe its already saved values, but must not overwrite a newer edit.
            val alreadySaved = current.enabled == enabled && current.time == time
            check(alreadySaved || current.enabled == expectedEnabled && current.time == expectedTime)
            val replacement = current.copy(enabled = enabled, time = time,
                introHandled = current.introHandled || enabled, permission = actualPermission,
                zoneId = if (enabled) current.zoneId ?: zone().id else current.zoneId)
            save(current, replacement) && load()?.let { it.enabled == enabled && it.time == time } == true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        return ReminderPreferenceChange(saved, owner)
    }

    /** May be called after SessionRuntime.clear(); only the captured owner's token is invalidated. */
    fun cancelForLogout(): ReminderPreferenceChange {
        val account = owner ?: return ReminderPreferenceChange(false, null)
        val saved = runCatching {
            val current = store.read(account)
            store.compareAndSet(account, current, current.copy(revision = nextRevision(current)))
        }.getOrDefault(false)
        return ReminderPreferenceChange(saved, account)
    }

    fun clearForAccountDeletion(): ReminderPreferenceChange = ReminderPreferenceChange(
        owner?.let { runCatching { store.clear(it) }.getOrDefault(false) } == true, owner,
    )

    override suspend fun nextSchedule(): DailyReminderScheduleDecision {
        if (!active()) return DailyReminderScheduleDecision.Cancel(owner, ReminderCancelReason.SIGNED_OUT)
        val current = load() ?: return DailyReminderScheduleDecision.Cancel(owner, ReminderCancelReason.STORAGE_UNAVAILABLE)
        return try { futureReminderDecision(requireNotNull(owner), current, clock.instant(), zone(), permission()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DailyReminderScheduleDecision.Cancel(owner, ReminderCancelReason.STORAGE_UNAVAILABLE) }
    }

    override suspend fun claim(token: DailyReminderScheduleToken): ReminderDeliveryClaim = claimScheduledDelivery(token)

    override suspend fun mayPostReserved(token: DailyReminderScheduleToken): Boolean {
        return try {
            if (token.accountId != owner || identity.accountId != owner || token.revision < 0 ||
                token.revision == Long.MAX_VALUE || !active()) return false
            // A reservation is durable evidence, not permission to post after a later preference change.
            val current = store.read(requireNotNull(owner))
            val currentZone = zone()
            val now = clock.instant()
            val today = now.atZone(currentZone).toLocalDate()
            current.enabled && permission() == ReminderPermission.GRANTED &&
                current.zoneId == currentZone.id && token.zoneId == currentZone.id && token.localDate == today &&
                token.scheduledAt <= now && reminderInstant(today, current.time, currentZone) == token.scheduledAt &&
                today in current.claimedLocalDates && current.revision == token.revision + 1 && active()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    suspend fun claimScheduledDelivery(token: DailyReminderScheduleToken): ReminderDeliveryClaim {
        if (!active() || token.accountId != owner) return ReminderDeliveryClaim.Rejected
        val current = load() ?: return ReminderDeliveryClaim.StorageFailed
        return try {
            val currentZone = zone()
            val now = clock.instant()
            val date = now.atZone(currentZone).toLocalDate()
            if (!current.enabled || permission() != ReminderPermission.GRANTED || current.zoneId != currentZone.id ||
                token.zoneId != currentZone.id || token.localDate != date) return ReminderDeliveryClaim.Rejected
            if (date in current.claimedLocalDates) return ReminderDeliveryClaim.AlreadyReserved
            if (token.revision != current.revision || token.scheduledAt > now ||
                reminderInstant(date, current.time, currentZone) != token.scheduledAt) return ReminderDeliveryClaim.Rejected
            if (save(current, current.copy(claimedLocalDates = current.claimedLocalDates + date))) ReminderDeliveryClaim.Reserved
            else if (active() && load()?.claimedLocalDates?.contains(date) == true) ReminderDeliveryClaim.AlreadyReserved
            else ReminderDeliveryClaim.StorageFailed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReminderDeliveryClaim.StorageFailed }
    }

    private suspend fun change(update: (DailyReminderPreferences) -> DailyReminderPreferences): ReminderPreferenceChange {
        val current = load() ?: return ReminderPreferenceChange(false, owner)
        val saved = try { save(current, update(current)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        return ReminderPreferenceChange(saved, owner)
    }

    private suspend fun save(current: DailyReminderPreferences, replacement: DailyReminderPreferences): Boolean {
        if (!active()) return false
        if (replacement == current) return true
        return store.compareAndSet(requireNotNull(owner), current, replacement.copy(revision = nextRevision(current))) && active()
    }

    private fun nextRevision(current: DailyReminderPreferences): Long {
        check(current.revision < Long.MAX_VALUE)
        return current.revision + 1
    }
}
