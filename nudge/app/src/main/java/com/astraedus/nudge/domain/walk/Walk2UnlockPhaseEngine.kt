package com.astraedus.nudge.domain.walk

/**
 * Pure function mapping total daily foreground time → [Walk2UnlockPhase].
 *
 * Thresholds are intentionally constants here rather than user-configurable: Walk2Unlock is a
 * fixed behaviour for Instagram, Facebook and YouTube Shorts, not a general rule the user edits.
 *
 * No Android imports — fully unit-testable on the JVM.
 */
object Walk2UnlockPhaseEngine {

    /** Below this: Phase FREE (no gate). */
    val PHASE1_THRESHOLD_MS: Long = 30L * 60_000L   // 30 minutes

    /** At or above this: Phase EXTRA (step-only gate). */
    val PHASE2_THRESHOLD_MS: Long = 60L * 60_000L   // 60 minutes

    /**
     * Returns the [Walk2UnlockPhase] for the given [dailyUsageMs].
     *
     * @param dailyUsageMs total foreground time (ms) for the target app/feature today, from
     *   [com.astraedus.nudge.service.UsageProvider.getDailyForegroundTimeMs].
     */
    fun phase(dailyUsageMs: Long): Walk2UnlockPhase = when {
        dailyUsageMs < PHASE1_THRESHOLD_MS -> Walk2UnlockPhase.FREE
        dailyUsageMs < PHASE2_THRESHOLD_MS -> Walk2UnlockPhase.CAPPED
        else                               -> Walk2UnlockPhase.EXTRA
    }

    /**
     * Milliseconds remaining in the FREE phase for [dailyUsageMs].
     * Returns 0 when already in CAPPED or EXTRA.
     */
    fun msUntilCapped(dailyUsageMs: Long): Long =
        (PHASE1_THRESHOLD_MS - dailyUsageMs).coerceAtLeast(0L)

    /**
     * Whole minutes remaining in the FREE phase, rounded down.
     * Useful for the notification copy ("10 min left").
     */
    fun minutesLeftInFree(dailyUsageMs: Long): Int =
        (msUntilCapped(dailyUsageMs) / 60_000L).toInt()

    /**
     * The notification milestone to announce, or null if no notification is due this tick.
     * Milestones: 20 min, 10 min, and 0 min remaining.
     */
    fun notificationMilestone(dailyUsageMs: Long): Int? {
        if (phase(dailyUsageMs) != Walk2UnlockPhase.FREE) return null
        val minutesLeft = minutesLeftInFree(dailyUsageMs)
        return when (minutesLeft) {
            in 0..0   -> 0   // exactly 0 minutes left
            in 1..10  -> 10  // in the last 10-min window
            in 11..20 -> 20  // in the last 20-min window
            else      -> null
        }
    }
}
