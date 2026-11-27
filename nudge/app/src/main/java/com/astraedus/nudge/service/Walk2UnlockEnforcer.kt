package com.astraedus.nudge.service

import android.content.Context
import com.astraedus.nudge.data.walk.Walk2UnlockStore
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.walk.Walk2UnlockEvaluator
import com.astraedus.nudge.domain.walk.Walk2UnlockEvaluator.Decision
import com.astraedus.nudge.domain.walk.Walk2UnlockPhase
import com.astraedus.nudge.domain.walk.Walk2UnlockPhaseEngine
import com.astraedus.nudge.domain.walk.Walk2UnlockState
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.KEY_FACEBOOK
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.KEY_INSTAGRAM
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.KEY_YOUTUBE_SHORTS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.SESSION_DURATION_MS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.TARGET_KEYS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Walk2Unlock enforcement for Instagram, Facebook and YouTube Shorts.
 *
 * Called from [NudgeAccessibilityService] both:
 *  1. On arrival / feature detection via [evaluateGate] (returns a synthetic `HARD_BLOCK` when gated),
 *  2. On the 30-second foreground-time ticker via [tick] (updates notifications, expires sessions,
 *     kicks home when a 5-min / 1-min / 3-min session ends).
 *
 * ## Scoping
 * - Instagram (`com.instagram.android`): whole app locked.
 * - Facebook (`com.facebook.katana`): whole app locked.
 * - YouTube (`com.google.android.youtube`): ONLY Shorts (`SHORTS`) locked; normal videos stay free.
 */
@Singleton
class Walk2UnlockEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: Walk2UnlockStore,
    private val passthroughManager: PassthroughManager,
    private val usageProvider: UsageProvider,
    logger: NudgeLog
) {
    private val log: NudgeLog = logger.tagged("Walk2UnlockEnforcer")

    /**
     * Whether YouTube Shorts is currently the detected feature.
     * Updated by [NudgeAccessibilityService] whenever in-app feature detection runs on YouTube.
     */
    @Volatile
    var isShortsActive: Boolean = false

    /**
     * Per-key cached grant flag. Defaults to `true` (fail-open) until [evaluateGate] or [tick]
     * observes a cooldown or step gate.
     */
    private val sessionGranted = ConcurrentHashMap<String, Boolean>().apply {
        TARGET_KEYS.forEach { put(it, true) }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Whether Walk2Unlock manages this package at all (used to keep the 30-second foreground clock
     * running even if the user has no manual DB rule for it).
     */
    fun manages(packageName: String): Boolean =
        packageName == KEY_INSTAGRAM ||
            packageName == KEY_FACEBOOK ||
            packageName == KEY_YOUTUBE_SHORTS.substringBefore(':')

    /**
     * Evaluate on arrival / feature detection whether Walk2Unlock currently blocks [packageName]
     * (and [detectedFeature] for YouTube).
     *
     * Returns a `BlockDecision.Block(HARD_BLOCK, ruleName = RULE_NAME)` when gated, or `null` when
     * Walk2Unlock allows access (Phase 1 FREE, open session, or unlocked by steps).
     */
    suspend fun evaluateGate(packageName: String, detectedFeature: String?): BlockDecision.Block? {
        val key = resolveKey(packageName, detectedFeature) ?: return null

        val dailyUsageMs = try {
            usageProvider.getDailyForegroundTimeMs(packageName)
        } catch (e: Exception) {
            log.w("walk2unlock: usage read failed on gate check key=$key", e)
            return null
        }

        val phase = Walk2UnlockPhaseEngine.phase(dailyUsageMs)
        val state = store.getState(key)
        val nowMs = System.currentTimeMillis()
        val decision = Walk2UnlockEvaluator.evaluate(phase, state, nowMs, dailyUsageMs)

        return when (decision) {
            is Decision.FreeAllow -> {
                sessionGranted[key] = true
                null
            }
            is Decision.SessionOpen -> {
                if (decision.shouldPersistStart) {
                    store.openSession(key, nowMs, decision.remainingMs)
                    Walk2UnlockNotifier.cancelStepProgress(context)
                }
                sessionGranted[key] = true
                null
            }
            is Decision.ExtraUnlocked -> {
                store.openSession(key, nowMs, decision.earnedDurationMs)
                Walk2UnlockNotifier.cancelStepProgress(context)
                sessionGranted[key] = true
                null
            }
            is Decision.SessionExpired -> {
                when (phase) {
                    Walk2UnlockPhase.CAPPED -> store.armCooldown(key, nowMs)
                    Walk2UnlockPhase.EXTRA -> store.openStepGate(key)
                    Walk2UnlockPhase.FREE -> Unit
                }
                sessionGranted[key] = false
                hardBlockDecision(detectedFeature)
            }
            is Decision.CappedCooldown,
            is Decision.ExtraStepGate -> {
                sessionGranted[key] = false
                hardBlockDecision(detectedFeature)
            }
        }
    }

    /**
     * One tick of the Walk2Unlock clock for [packageName]. Reads daily usage, evaluates the
     * phase and state, and acts: grant/revoke passthrough, kick home, post notifications.
     *
     * Must be called on a background (IO) thread.
     */
    suspend fun tick(packageName: String, detectedFeature: String?, kickHome: () -> Unit) {
        val key = resolveKey(packageName, detectedFeature) ?: return

        val dailyUsageMs = try {
            usageProvider.getDailyForegroundTimeMs(packageName)
        } catch (e: Exception) {
            log.w("walk2unlock: usage read failed key=$key", e)
            return
        }

        val phase = Walk2UnlockPhaseEngine.phase(dailyUsageMs)
        val state = store.getState(key)
        val nowMs = System.currentTimeMillis()

        // Ensure the "0 left" Phase-1 warning fires if usage crossed 30m between ticks.
        if (phase != Walk2UnlockPhase.FREE && state.lastNotifiedMilestone != 0) {
            store.recordMilestoneNotified(key, 0)
            Walk2UnlockNotifier.notifyFreeTimeRemaining(context, 0)
            log.i("walk2unlock: phase-1 0-left notification fired on transition key=$key")
        }

        val decision = Walk2UnlockEvaluator.evaluate(phase, state, nowMs, dailyUsageMs)
        log.d("walk2unlock tick key=$key phase=$phase decision=$decision")

        when (decision) {
            is Decision.FreeAllow -> {
                grantSession(key, packageName, detectedFeature, SESSION_DURATION_MS * 10)
                decision.milestoneMinutesLeft?.let { minutesLeft ->
                    store.recordMilestoneNotified(key, minutesLeft)
                    Walk2UnlockNotifier.notifyFreeTimeRemaining(context, minutesLeft)
                    log.i("walk2unlock: phase-1 notification minutesLeft=$minutesLeft key=$key")
                }
            }

            is Decision.SessionOpen -> {
                if (decision.shouldPersistStart) {
                    store.openSession(key, nowMs, decision.remainingMs)
                    Walk2UnlockNotifier.cancelStepProgress(context)
                }
                grantSession(key, packageName, detectedFeature, decision.remainingMs)
            }

            is Decision.SessionExpired -> {
                log.i("walk2unlock: session expired key=$key — kicking home and gating")
                revokeSession(key)
                withContext(Dispatchers.Main) { kickHome() }
                when (phase) {
                    Walk2UnlockPhase.CAPPED -> {
                        store.armCooldown(key, nowMs)
                        log.i("walk2unlock: phase-2 cooldown armed key=$key")
                    }
                    Walk2UnlockPhase.EXTRA -> {
                        store.openStepGate(key)
                        log.i("walk2unlock: phase-3 step gate opened key=$key")
                    }
                    Walk2UnlockPhase.FREE -> Unit
                }
            }

            is Decision.CappedCooldown -> {
                revokeSession(key)
                Walk2UnlockNotifier.notifyStepProgress(
                    context = context,
                    stepsWalked = decision.stepsWalked,
                    stepsRequired = decision.stepsRequired,
                    appLabel = labelFor(key)
                )
            }

            is Decision.ExtraStepGate -> {
                revokeSession(key)
                Walk2UnlockNotifier.notifyStepProgress(
                    context = context,
                    stepsWalked = decision.stepsWalked,
                    stepsRequired = Walk2UnlockState.PHASE3_SHORT_STEPS,
                    appLabel = labelFor(key)
                )
            }

            is Decision.ExtraUnlocked -> {
                log.i("walk2unlock: phase-3 steps reached key=$key steps=${decision.stepsWalked} earned=${decision.earnedDurationMs}ms")
                Walk2UnlockNotifier.cancelStepProgress(context)
                store.openSession(key, nowMs, decision.earnedDurationMs)
                grantSession(key, packageName, detectedFeature, decision.earnedDurationMs)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hardBlockDecision(detectedFeature: String?) = BlockDecision.Block(
        mode = BlockMode.HARD_BLOCK,
        ruleName = RULE_NAME,
        feature = detectedFeature
    )

    private fun grantSession(
        key: String,
        packageName: String,
        detectedFeature: String?,
        durationMs: Long
    ) {
        sessionGranted[key] = true
        passthroughManager.grant(
            packageName = packageName,
            featureKey = detectedFeature
        )
        log.d("walk2unlock: session granted key=$key durationMs=$durationMs")
    }

    private fun revokeSession(key: String) {
        if (sessionGranted[key] == true) {
            sessionGranted[key] = false
            passthroughManager.clear()
            log.d("walk2unlock: session revoked key=$key")
        }
    }

    /**
     * Map a (packageName, detectedFeature) pair to a Walk2Unlock state key, or null if this
     * package is not managed by Walk2Unlock (or if it is YouTube without Shorts active).
     */
    private fun resolveKey(packageName: String, detectedFeature: String?): String? = when {
        packageName == KEY_INSTAGRAM -> KEY_INSTAGRAM
        packageName == KEY_FACEBOOK -> KEY_FACEBOOK
        packageName == KEY_YOUTUBE_SHORTS.substringBefore(':') &&
            (detectedFeature == "SHORTS" || isShortsActive) -> KEY_YOUTUBE_SHORTS
        else -> null
    }

    private fun labelFor(key: String): String = when (key) {
        KEY_INSTAGRAM -> "Instagram"
        KEY_FACEBOOK -> "Facebook"
        KEY_YOUTUBE_SHORTS -> "YouTube Shorts"
        else -> key
    }

    companion object {
        const val RULE_NAME = "Walk2Unlock"
    }
}
