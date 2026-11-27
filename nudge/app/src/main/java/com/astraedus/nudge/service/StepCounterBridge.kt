package com.astraedus.nudge.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.astraedus.nudge.data.walk.Walk2UnlockStore
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.walk.Walk2UnlockState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's TYPE_STEP_COUNTER sensor and exposes a [StateFlow] of the current absolute step
 * count.
 *
 * ## Step counter semantics
 * TYPE_STEP_COUNTER counts total steps since the last device reboot. We store the reading at the
 * moment each gate (cooldown or Phase-3 step gate) opens as a baseline, then compute the delta.
 * This is the same pattern used for the foreground-time baselines in [TimeKickEvaluator].
 *
 * ## Sensor availability
 * TYPE_STEP_COUNTER is declared `required="false"` in the manifest. When no sensor is present,
 * [register] records the absence and [available] is false. [Walk2UnlockEnforcer] checks this flag
 * and falls back to pure cooldown-only behaviour (no step bypass) so the feature still works on
 * devices without a pedometer.
 *
 * ## Lifecycle
 * [register] is called from [NudgeAccessibilityService.onServiceConnected].
 * [unregister] is called from teardown, via [ServiceTeardown].
 *
 * This runs in [NudgeMonitorService]'s process which is already a foreground service, so sensor
 * registration does not require a separate wakelock.
 *
 * Threading: [SensorEventListener.onSensorChanged] is called on the sensor's handler thread.
 * We update the [StateFlow] there (CAS) and persist asynchronously to a background scope.
 */
@Singleton
class StepCounterBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: Walk2UnlockStore,
    logger: NudgeLog
) : SensorEventListener {

    private val log: NudgeLog = logger.tagged("StepCounterBridge")

    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    /** Whether a TYPE_STEP_COUNTER sensor exists on this device. */
    @Volatile
    var available: Boolean = false
        private set

    private val _steps = MutableStateFlow(0L)

    /** Live absolute step count since last reboot. 0 until the first sensor event arrives. */
    val steps: StateFlow<Long> = _steps.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Register for step-counter events. Safe to call multiple times (re-registration is a no-op
     * if already registered; the system deduplicates listener registrations).
     */
    fun register() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            log.w("TYPE_STEP_COUNTER sensor not available on this device — Walk2Unlock step bypass disabled")
            available = false
            return
        }
        available = true
        // SENSOR_DELAY_NORMAL is fine: we only need this for walk counting, not real-time motion.
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        log.i("step counter registered=$registered")
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
        log.i("step counter unregistered")
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        val absoluteSteps = event?.values?.getOrNull(0)?.toLong() ?: return
        _steps.value = absoluteSteps

        // Persist to store so the step delta survives process restarts.
        scope.launch {
            Walk2UnlockState.TARGET_KEYS.forEach { key ->
                store.updateSteps(key, absoluteSteps)
            }
        }

        log.d("step sensor: absoluteSteps=$absoluteSteps")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not relevant for step counter.
    }
}
