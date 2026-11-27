package com.astraedus.nudge.domain.walk

/**
 * Per-app/feature persisted state for the Walk2Unlock gate.
 *
 * [packageKey] is one of the three hardcoded targets:
 *  - "com.instagram.android"
 *  - "com.facebook.katana"
 *  - "com.google.android.youtube:SHORTS"
 *
 * All timestamps are wall-clock epoch milliseconds (System.currentTimeMillis()), consistent with
 * the rest of the codebase's daily-limit and schedule arithmetic.
 *
 * No Android imports — fully serialisable as JSON for DataStore persistence and unit-testable.
 */
data class Walk2UnlockState(
    /** Identifies the app or feature this state belongs to. */
    val packageKey: String,

    // ── Session ──────────────────────────────────────────────────────────────

    /**
     * Wall-clock ms when the current session started (passthrough granted).
     * Null means no session is open.
     */
    val sessionStartedAt: Long? = null,

    /**
     * How long the current session lasts.
     * Constant 5 minutes for both Phase 2 and Phase 3; kept as a field for testability.
     */
    val sessionDurationMs: Long = SESSION_DURATION_MS,

    // ── Phase 2 cooldown ─────────────────────────────────────────────────────

    /**
     * Wall-clock ms when the current Phase-2 cooldown started.
     * Null when no cooldown is in force (Phase 3 has no cooldown).
     */
    val cooldownStartedAt: Long? = null,

    /**
     * How long a Phase-2 cooldown lasts. Constant 25 minutes; kept for testability.
     */
    val cooldownDurationMs: Long = COOLDOWN_DURATION_MS,

    // ── Step counter ─────────────────────────────────────────────────────────

    /**
     * The absolute TYPE_STEP_COUNTER reading taken when the cooldown or Phase-3 gate opened.
     * Steps walked since then = currentAbsoluteSteps − stepsAtGateStart.
     */
    val stepsAtGateStart: Long = 0L,

    /**
     * The most recent absolute TYPE_STEP_COUNTER reading pushed by [com.astraedus.nudge.service.StepCounterBridge].
     */
    val currentAbsoluteSteps: Long = 0L,

    // ── Phase 1 notification tracking ────────────────────────────────────────

    /**
     * The last Phase-1 "minutes left" milestone that was notified (20, 10, or 0).
     * Null means no milestone notification has been sent for today.
     * Reset to null when the daily usage counter crosses midnight (day boundary).
     */
    val lastNotifiedMilestone: Int? = null
) {
    /** Steps walked since the gate (cooldown or Phase-3 step gate) opened. */
    val stepsWalkedSinceGateStart: Int
        get() = (currentAbsoluteSteps - stepsAtGateStart).coerceAtLeast(0L).toInt()

    companion object {
        const val SESSION_DURATION_MS: Long = 5L * 60_000L   // 5 minutes
        const val COOLDOWN_DURATION_MS: Long = 25L * 60_000L // 25 minutes

        // Step requirements
        const val PHASE2_STEPS_REQUIRED: Int = 400
        const val PHASE3_SHORT_STEPS: Int = 200
        const val PHASE3_LONG_STEPS: Int = 600

        // Extra time earned by walking in Phase 3
        const val PHASE3_SHORT_DURATION_MS: Long = 1L * 60_000L   // 1 minute
        const val PHASE3_LONG_DURATION_MS: Long = 3L * 60_000L    // 3 minutes

        // Hardcoded target package keys
        const val KEY_INSTAGRAM = "com.instagram.android"
        const val KEY_FACEBOOK = "com.facebook.katana"
        const val KEY_YOUTUBE_SHORTS = "com.google.android.youtube:SHORTS"

        val TARGET_KEYS = setOf(KEY_INSTAGRAM, KEY_FACEBOOK, KEY_YOUTUBE_SHORTS)
    }
}
