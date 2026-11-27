package com.astraedus.nudge.ui.walk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.walk.Walk2UnlockStore
import com.astraedus.nudge.domain.walk.Walk2UnlockPhase
import com.astraedus.nudge.domain.walk.Walk2UnlockPhaseEngine
import com.astraedus.nudge.domain.walk.Walk2UnlockState
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE2_STEPS_REQUIRED
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE3_LONG_STEPS
import com.astraedus.nudge.domain.walk.Walk2UnlockState.Companion.PHASE3_SHORT_STEPS
import com.astraedus.nudge.service.StepCounterBridge
import com.astraedus.nudge.service.UsageProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Data exposed to [WalkUnlockActivity].
 */
data class WalkUnlockUiState(
    val stepsWalked: Int = 0,
    val shortGoal: Int = PHASE3_SHORT_STEPS,
    val longGoal: Int = PHASE3_LONG_STEPS,
    val isPhase2: Boolean = false,
    val unlocked: Boolean = false,
    val sensorAvailable: Boolean = true
) {
    /** Progress toward the nearest milestone [0.0, 1.0]. */
    val progress: Float
        get() {
            val goal = if (isPhase2) longGoal else shortGoal
            return (stepsWalked.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
        }

    /** Steps still needed for the nearest goal. */
    val stepsRemaining: Int
        get() {
            val goal = if (isPhase2) longGoal else shortGoal
            return (goal - stepsWalked).coerceAtLeast(0)
        }
}

@HiltViewModel
class WalkUnlockViewModel @Inject constructor(
    private val store: Walk2UnlockStore,
    private val stepBridge: StepCounterBridge,
    private val usageProvider: UsageProvider
) : ViewModel() {

    private var packageKey: String = Walk2UnlockState.KEY_INSTAGRAM

    fun setPackageKey(key: String, @Suppress("UNUSED_PARAMETER") phase2: Boolean) {
        packageKey = key
    }

    val uiState: StateFlow<WalkUnlockUiState> by lazy {
        combine(
            store.stateFlow(packageKey),
            stepBridge.steps
        ) { state, absoluteSteps ->
            val stepsWalked = (absoluteSteps - state.stepsAtGateStart)
                .coerceAtLeast(0L).toInt()

            val pkg = packageKey.substringBefore(':')
            val dailyUsageMs = runCatching { usageProvider.getDailyForegroundTimeMs(pkg) }.getOrDefault(0L)
            val isPhase2 = Walk2UnlockPhaseEngine.phase(dailyUsageMs) == Walk2UnlockPhase.CAPPED

            val shortGoal = if (isPhase2) PHASE2_STEPS_REQUIRED else PHASE3_SHORT_STEPS
            val longGoal = if (isPhase2) PHASE2_STEPS_REQUIRED else PHASE3_LONG_STEPS

            val unlocked = when {
                isPhase2 -> stepsWalked >= PHASE2_STEPS_REQUIRED
                else -> stepsWalked >= PHASE3_SHORT_STEPS
            }

            WalkUnlockUiState(
                stepsWalked = stepsWalked,
                shortGoal = shortGoal,
                longGoal = longGoal,
                isPhase2 = isPhase2,
                unlocked = unlocked,
                sensorAvailable = stepBridge.available
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WalkUnlockUiState(sensorAvailable = stepBridge.available)
        )
    }
}
