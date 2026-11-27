package com.astraedus.nudge.domain.walk

import com.astraedus.nudge.domain.walk.Walk2UnlockEvaluator.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Walk2UnlockTest {

    private val min = 60_000L

    @Test
    fun `phase engine classifies daily usage boundaries`() {
        assertEquals(Walk2UnlockPhase.FREE, Walk2UnlockPhaseEngine.phase(0L))
        assertEquals(Walk2UnlockPhase.FREE, Walk2UnlockPhaseEngine.phase(29 * min))
        assertEquals(Walk2UnlockPhase.CAPPED, Walk2UnlockPhaseEngine.phase(30 * min))
        assertEquals(Walk2UnlockPhase.CAPPED, Walk2UnlockPhaseEngine.phase(59 * min))
        assertEquals(Walk2UnlockPhase.EXTRA, Walk2UnlockPhaseEngine.phase(60 * min))
        assertEquals(Walk2UnlockPhase.EXTRA, Walk2UnlockPhaseEngine.phase(120 * min))
    }

    @Test
    fun `phase 1 emits notifications at 20, 10, and 0 minutes left once each`() {
        assertNull(Walk2UnlockPhaseEngine.notificationMilestone(5 * min)) // 25 min left
        assertEquals(20, Walk2UnlockPhaseEngine.notificationMilestone(10 * min)) // 20 min left
        assertEquals(10, Walk2UnlockPhaseEngine.notificationMilestone(20 * min)) // 10 min left
        assertEquals(0, Walk2UnlockPhaseEngine.notificationMilestone(29 * min + 30_000L)) // 0 full min left

        val stateNotified20 = Walk2UnlockState(
            packageKey = Walk2UnlockState.KEY_INSTAGRAM,
            lastNotifiedMilestone = 20
        )
        val repeatDecision = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.FREE,
            state = stateNotified20,
            nowMs = 1_000L,
            dailyUsageMs = 10 * min
        )
        assertEquals(Decision.FreeAllow(milestoneMinutesLeft = null), repeatDecision)
    }

    @Test
    fun `phase 2 allows 5-minute session then expires and enforces 25-minute cooldown unless 400 steps walked`() {
        val t0 = 1_000_000L
        val openState = Walk2UnlockState(
            packageKey = Walk2UnlockState.KEY_FACEBOOK,
            sessionStartedAt = t0
        )

        // 4 min into session -> still open
        val midSession = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.CAPPED,
            state = openState,
            nowMs = t0 + 4 * min,
            dailyUsageMs = 35 * min
        )
        assertEquals(Decision.SessionOpen(remainingMs = 1 * min, shouldPersistStart = false), midSession)

        // 5 min into session -> expired
        val expired = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.CAPPED,
            state = openState,
            nowMs = t0 + 5 * min,
            dailyUsageMs = 35 * min
        )
        assertEquals(Decision.SessionExpired, expired)

        // In cooldown with 200 steps -> still in cooldown
        val cooldownState = Walk2UnlockState(
            packageKey = Walk2UnlockState.KEY_FACEBOOK,
            cooldownStartedAt = t0 + 5 * min,
            stepsAtGateStart = 1000L,
            currentAbsoluteSteps = 1200L
        )
        val inCooldown = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.CAPPED,
            state = cooldownState,
            nowMs = t0 + 10 * min,
            dailyUsageMs = 35 * min
        )
        assertTrue(inCooldown is Decision.CappedCooldown)
        assertEquals(200, (inCooldown as Decision.CappedCooldown).stepsWalked)
        assertEquals(20 * min, inCooldown.cooldownRemainingMs)

        // Walk 400 steps -> bypasses cooldown and starts next 5-min session
        val bypassed = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.CAPPED,
            state = cooldownState.copy(currentAbsoluteSteps = 1400L),
            nowMs = t0 + 10 * min,
            dailyUsageMs = 35 * min
        )
        assertEquals(Decision.SessionOpen(remainingMs = 5 * min, shouldPersistStart = true), bypassed)
    }

    @Test
    fun `phase 3 grants 1 min for 200 steps and 3 mins for 600 steps with no cooldown`() {
        val gateState = Walk2UnlockState(
            packageKey = Walk2UnlockState.KEY_YOUTUBE_SHORTS,
            stepsAtGateStart = 5000L,
            currentAbsoluteSteps = 5150L
        )

        val gated = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.EXTRA,
            state = gateState,
            nowMs = 10_000L,
            dailyUsageMs = 65 * min
        )
        assertEquals(Decision.ExtraStepGate(stepsWalked = 150), gated)

        val unlocked1Min = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.EXTRA,
            state = gateState.copy(currentAbsoluteSteps = 5200L),
            nowMs = 10_000L,
            dailyUsageMs = 65 * min
        )
        assertEquals(Decision.ExtraUnlocked(earnedDurationMs = 1 * min, stepsWalked = 200), unlocked1Min)

        val unlocked3Min = Walk2UnlockEvaluator.evaluate(
            phase = Walk2UnlockPhase.EXTRA,
            state = gateState.copy(currentAbsoluteSteps = 5600L),
            nowMs = 10_000L,
            dailyUsageMs = 65 * min
        )
        assertEquals(Decision.ExtraUnlocked(earnedDurationMs = 3 * min, stepsWalked = 600), unlocked3Min)
    }
}
