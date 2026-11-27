package com.astraedus.nudge.domain.walk

import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.COOLDOWN_DURATION_MS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE2_STEPS_REQUIRED
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE3_LONG_DURATION_MS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE3_LONG_STEPS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE3_SHORT_DURATION_MS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE3_SHORT_STEPS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.SESSION_DURATION_MS

/**
 * Pure decision logic for the Walk2Unlock gate.
 *
 * Called from [com.astraedus.nudge.service.Walk2UnlockEnforcer] on the 30-second foreground clock.
 * Returns a [Decision] that the enforcer acts on — no side effects here.
 *
 * Threading model: all inputs are plain values; no Android imports. Fully unit-testable on the JVM.
 */
object Walk2UnlockEvaluator {

    // ── Decision types ────────────────────────────────────────────────────────

    sealed interface Decision {

        /**
         * Phase FREE: allow the user through without any gate.
         *
         * @param milestoneMinutesLeft non-null when a notification milestone was just crossed
         *   (20, 10, or 0 minutes left in the free window). The enforcer posts ONE notification
         *   per milestone per day.
         */
        data class FreeAllow(val milestoneMinutesLeft: Int?) : Decision

        /**
         * Phase CAPPED or EXTRA: an open session is running and the user has remaining time.
         * @param remainingMs milliseconds left in the session, for the overlay countdown.
         * @param shouldPersistStart true when this tick is opening a fresh session (first entry
         *   into CAPPED, cooldown expired, or 400 steps reached) so the enforcer persists
         *   `sessionStartedAt = nowMs`.
         */
        data class SessionOpen(
            val remainingMs: Long,
            val shouldPersistStart: Boolean = false
        ) : Decision

        /**
         * Phase CAPPED or EXTRA: the session timer just ran out. The enforcer must kick the user
         * home and either arm a cooldown (Phase CAPPED) or open the step gate (Phase EXTRA).
         */
        data object SessionExpired : Decision

        /**
         * Phase CAPPED: a cooldown is in force and not yet bypassed by steps.
         *
         * @param stepsWalked how many steps walked since the cooldown started.
         * @param cooldownRemainingMs ms left on the 25-minute cooldown, for display.
         */
        data class CappedCooldown(
            val stepsWalked: Int,
            val stepsRequired: Int = PHASE2_STEPS_REQUIRED,
            val cooldownRemainingMs: Long
        ) : Decision

        /**
         * Phase EXTRA: no session is running. The user must walk to earn time.
         * Walking [PHASE3_SHORT_STEPS] earns 1 min; walking [PHASE3_LONG_STEPS] earns 3 min.
         *
         * @param stepsWalked cumulative steps since the gate opened.
         */
        data class ExtraStepGate(val stepsWalked: Int) : Decision

        /**
         * Phase EXTRA: enough steps walked to earn a short or long extension.
         * The enforcer grants the passthrough for [earnedDurationMs] and resets the gate.
         */
        data class ExtraUnlocked(val earnedDurationMs: Long, val stepsWalked: Int) : Decision
    }

    // ── Core evaluation ───────────────────────────────────────────────────────

    /**
     * Evaluate the Walk2Unlock state for a single app/feature.
     *
     * @param phase              the current [Walk2UnlockPhase] from [Walk2UnlockPhaseEngine.phase].
     * @param state              the persisted [Walk2UnlockState] for this key.
     * @param nowMs              wall-clock epoch ms (System.currentTimeMillis()).
     * @param dailyUsageMs       total foreground time today, for milestone calculation.
     */
    fun evaluate(
        phase: Walk2UnlockPhase,
        state: Walk2UnlockState,
        nowMs: Long,
        dailyUsageMs: Long
    ): Decision = when (phase) {
        Walk2UnlockPhase.FREE   -> evaluateFree(state, dailyUsageMs)
        Walk2UnlockPhase.CAPPED -> evaluateCapped(state, nowMs)
        Walk2UnlockPhase.EXTRA  -> evaluateExtra(state, nowMs)
    }

    // ── Phase FREE ────────────────────────────────────────────────────────────

    private fun evaluateFree(state: Walk2UnlockState, dailyUsageMs: Long): Decision {
        val milestone = Walk2UnlockPhaseEngine.notificationMilestone(dailyUsageMs)
        // Only announce each milestone once per day.
        val newMilestone = if (milestone != null && milestone != state.lastNotifiedMilestone) {
            milestone
        } else {
            null
        }
        return Decision.FreeAllow(milestoneMinutesLeft = newMilestone)
    }

    // ── Phase CAPPED ──────────────────────────────────────────────────────────

    private fun evaluateCapped(state: Walk2UnlockState, nowMs: Long): Decision {
        // 1. Session open?
        val sessionStart = state.sessionStartedAt
        if (sessionStart != null) {
            val elapsed = nowMs - sessionStart
            val remaining = SESSION_DURATION_MS - elapsed
            return if (remaining > 0) {
                Decision.SessionOpen(remainingMs = remaining)
            } else {
                Decision.SessionExpired
            }
        }

        // 2. Cooldown in force?
        val cooldownStart = state.cooldownStartedAt
        if (cooldownStart != null) {
            val elapsed = nowMs - cooldownStart
            val cooldownRemaining = (COOLDOWN_DURATION_MS - elapsed).coerceAtLeast(0L)

            // Did the user walk enough steps to bypass the cooldown?
            if (state.stepsWalkedSinceGateStart >= PHASE2_STEPS_REQUIRED) {
                // Steps reached: open a fresh session. The enforcer persists sessionStartedAt.
                return Decision.SessionOpen(remainingMs = SESSION_DURATION_MS, shouldPersistStart = true)
            }

            // Cooldown timer expired without steps?
            if (cooldownRemaining == 0L) {
                // Open a fresh session anyway (time-based fallback).
                return Decision.SessionOpen(remainingMs = SESSION_DURATION_MS, shouldPersistStart = true)
            }

            return Decision.CappedCooldown(
                stepsWalked = state.stepsWalkedSinceGateStart,
                cooldownRemainingMs = cooldownRemaining
            )
        }

        // 3. No session and no cooldown: first time entering CAPPED, open a session.
        return Decision.SessionOpen(remainingMs = SESSION_DURATION_MS, shouldPersistStart = true)
    }

    // ── Phase EXTRA ───────────────────────────────────────────────────────────

    private fun evaluateExtra(state: Walk2UnlockState, nowMs: Long): Decision {
        // 1. Session open?
        val sessionStart = state.sessionStartedAt
        if (sessionStart != null) {
            val elapsed = nowMs - sessionStart
            val remaining = state.sessionDurationMs - elapsed
            return if (remaining > 0) {
                Decision.SessionOpen(remainingMs = remaining)
            } else {
                Decision.SessionExpired
            }
        }

        // 2. No session: check step progress toward earning extra time.
        val steps = state.stepsWalkedSinceGateStart

        return when {
            // Long goal reached first (600 earns 3 min).
            steps >= PHASE3_LONG_STEPS  -> Decision.ExtraUnlocked(
                earnedDurationMs = PHASE3_LONG_DURATION_MS,
                stepsWalked = steps
            )
            // Short goal (200 earns 1 min).
            steps >= PHASE3_SHORT_STEPS -> Decision.ExtraUnlocked(
                earnedDurationMs = PHASE3_SHORT_DURATION_MS,
                stepsWalked = steps
            )
            // Keep walking.
            else                        -> Decision.ExtraStepGate(stepsWalked = steps)
        }
    }
}
