package com.astraedus.nudge.domain.walk

/**
 * The phase the user is currently in for a given app/feature, determined by total daily usage.
 *
 * Phase thresholds:
 *  - FREE    : < 30 min daily  — no blocking, only gentle notifications
 *  - CAPPED  : 30–60 min daily — 5-min sessions + 25-min cooldowns, 400-step bypass
 *  - EXTRA   : ≥ 60 min daily  — 5-min sessions, no cooldown, step-based extra time
 *
 * No Android imports — fully unit-testable on the JVM.
 */
enum class Walk2UnlockPhase {
    /**
     * Total daily usage is under 30 minutes. No session limit or cooldown; Nudge only posts gentle
     * time-remaining notifications at 20 min left, 10 min left, and 0 min left.
     */
    FREE,

    /**
     * Total daily usage is between 30 and 60 minutes (inclusive of 30, exclusive of 60).
     * Sessions are capped at 5 minutes. After each session a 25-minute cooldown begins.
     * Walking 400 steps (measured since the cooldown start) bypasses the cooldown and opens the
     * next 5-minute session immediately.
     */
    CAPPED,

    /**
     * Total daily usage is at or above 60 minutes.
     * Sessions are still capped at 5 minutes to prevent one long sitting.
     * There is NO cooldown: when a session ends the user is at the step gate immediately.
     * Walking 200 steps earns 1 minute; walking 600 steps earns 3 minutes.
     */
    EXTRA
}
