package com.astraedus.nudge.data.walk

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.astraedus.nudge.domain.walk.Walk2UnlockState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.walk2UnlockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "walk2unlock_state"
)

/**
 * DataStore-backed repository for [Walk2UnlockState].
 *
 * Each of the three target keys (Instagram, Facebook, YouTube Shorts) gets its own JSON blob
 * stored under a dedicated DataStore preference key. We avoid a single mega-JSON so that one key's
 * update does not force a re-serialise of all three states.
 *
 * JSON is hand-rolled to stay dependency-free (the app has no Moshi/Gson, only the standard lib).
 * The schema is append-safe: unknown keys read by old code are simply ignored; missing keys fall
 * back to defaults, keeping every field's default meaningful as a migration.
 *
 * Threading: DataStore operations are `suspend` and must be called from a coroutine. The
 * accessibility service does so from its IO scope exactly as it does for other DataStore reads.
 */
@Singleton
class Walk2UnlockStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore = context.walk2UnlockDataStore

    // One DataStore key per target package key.
    private fun prefKey(packageKey: String): Preferences.Key<String> =
        stringPreferencesKey("w2u_${packageKey.replace(':', '_').replace('.', '_')}")

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Observe the live state for [packageKey]. Emits the default state if nothing is stored. */
    fun stateFlow(packageKey: String): Flow<Walk2UnlockState> = dataStore.data.map { prefs ->
        prefs[prefKey(packageKey)]?.let { deserialize(packageKey, it) }
            ?: Walk2UnlockState(packageKey = packageKey)
    }

    /** One-shot read (suspending). */
    suspend fun getState(packageKey: String): Walk2UnlockState =
        stateFlow(packageKey).first()

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Atomically read-modify-write the state for [packageKey].
     *
     * @param transform receives the current state (or the default) and returns the new state.
     */
    suspend fun updateState(
        packageKey: String,
        transform: (Walk2UnlockState) -> Walk2UnlockState
    ) {
        dataStore.edit { prefs ->
            val current = prefs[prefKey(packageKey)]
                ?.let { deserialize(packageKey, it) }
                ?: Walk2UnlockState(packageKey = packageKey)
            prefs[prefKey(packageKey)] = serialize(transform(current))
        }
    }

    /** Close the active session and clear cooldown/gate for [packageKey]. */
    suspend fun clearSessionAndCooldown(packageKey: String) {
        updateState(packageKey) { state ->
            state.copy(
                sessionStartedAt = null,
                cooldownStartedAt = null,
                stepsAtGateStart = state.currentAbsoluteSteps
            )
        }
    }

    /** Record the latest absolute step count for [packageKey]'s gate delta. */
    suspend fun updateSteps(packageKey: String, absoluteSteps: Long) {
        updateState(packageKey) { it.copy(currentAbsoluteSteps = absoluteSteps) }
    }

    /** Open a session for [packageKey], recording [nowMs] as the start and [durationMs] as its length. */
    suspend fun openSession(packageKey: String, nowMs: Long, durationMs: Long) {
        updateState(packageKey) { state ->
            state.copy(
                sessionStartedAt = nowMs,
                sessionDurationMs = durationMs,
                cooldownStartedAt = null,
                stepsAtGateStart = state.currentAbsoluteSteps
            )
        }
    }

    /** Arm a Phase-2 cooldown, recording [nowMs] as its start and locking in the current step baseline. */
    suspend fun armCooldown(packageKey: String, nowMs: Long) {
        updateState(packageKey) { state ->
            state.copy(
                sessionStartedAt = null,
                cooldownStartedAt = nowMs,
                stepsAtGateStart = state.currentAbsoluteSteps
            )
        }
    }

    /** Open a Phase-3 step gate (no cooldown; just resets the step baseline). */
    suspend fun openStepGate(packageKey: String) {
        updateState(packageKey) { state ->
            state.copy(
                sessionStartedAt = null,
                cooldownStartedAt = null,
                stepsAtGateStart = state.currentAbsoluteSteps
            )
        }
    }

    /** Record that a Phase-1 milestone notification was posted so it isn't re-posted this day. */
    suspend fun recordMilestoneNotified(packageKey: String, milestoneMinutesLeft: Int) {
        updateState(packageKey) { it.copy(lastNotifiedMilestone = milestoneMinutesLeft) }
    }

    /** Call at the daily boundary (midnight / dayStartMinute) to reset the notification tracking. */
    suspend fun resetDailyTracking(packageKey: String) {
        updateState(packageKey) { state ->
            state.copy(
                lastNotifiedMilestone = null,
                // Also close any open session / cooldown — the new day starts fresh.
                sessionStartedAt = null,
                cooldownStartedAt = null,
                stepsAtGateStart = state.currentAbsoluteSteps
            )
        }
    }

    // ── Serialisation (hand-rolled JSON, no external dependency) ─────────────

    private fun serialize(state: Walk2UnlockState): String = JSONObject().apply {
        put("packageKey", state.packageKey)
        state.sessionStartedAt?.let { put("sessionStartedAt", it) }
        put("sessionDurationMs", state.sessionDurationMs)
        state.cooldownStartedAt?.let { put("cooldownStartedAt", it) }
        put("cooldownDurationMs", state.cooldownDurationMs)
        put("stepsAtGateStart", state.stepsAtGateStart)
        put("currentAbsoluteSteps", state.currentAbsoluteSteps)
        state.lastNotifiedMilestone?.let { put("lastNotifiedMilestone", it) }
    }.toString()

    private fun deserialize(packageKey: String, json: String): Walk2UnlockState = try {
        val obj = JSONObject(json)
        Walk2UnlockState(
            packageKey = obj.optString("packageKey", packageKey),
            sessionStartedAt = if (obj.has("sessionStartedAt")) obj.getLong("sessionStartedAt") else null,
            sessionDurationMs = obj.optLong("sessionDurationMs", Walk2UnlockState.SESSION_DURATION_MS),
            cooldownStartedAt = if (obj.has("cooldownStartedAt")) obj.getLong("cooldownStartedAt") else null,
            cooldownDurationMs = obj.optLong("cooldownDurationMs", Walk2UnlockState.COOLDOWN_DURATION_MS),
            stepsAtGateStart = obj.optLong("stepsAtGateStart", 0L),
            currentAbsoluteSteps = obj.optLong("currentAbsoluteSteps", 0L),
            lastNotifiedMilestone = if (obj.has("lastNotifiedMilestone")) obj.getInt("lastNotifiedMilestone") else null
        )
    } catch (_: Exception) {
        // Corrupt JSON: return a clean slate rather than crashing.
        Walk2UnlockState(packageKey = packageKey)
    }
}
