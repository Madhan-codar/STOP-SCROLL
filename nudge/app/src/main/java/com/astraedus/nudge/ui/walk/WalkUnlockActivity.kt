package com.astraedus.nudge.ui.walk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.walk.Walk2UnlockState
import com.astraedus.nudge.ui.theme.NudgeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen UI that shows the user's step progress toward unlocking their next session.
 *
 * Launched from [com.astraedus.nudge.ui.overlay.HardBlockContent] when a Walk2Unlock gate is
 * active (rule name == "Walk2Unlock"). The user walks until [WalkUnlockUiState.unlocked] is true,
 * at which point this Activity finishes and [Walk2UnlockEnforcer.tick] picks up the unlocked state
 * on the next clock tick (within 30 seconds).
 *
 * On devices with no step sensor, shows a friendly message and a "Continue anyway" fallback.
 */
@AndroidEntryPoint
class WalkUnlockActivity : ComponentActivity() {

    private val viewModel: WalkUnlockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageKey = intent.getStringExtra(EXTRA_PACKAGE_KEY) ?: Walk2UnlockState.KEY_INSTAGRAM
        val isPhase2 = intent.getBooleanExtra(EXTRA_IS_PHASE2, false)
        viewModel.setPackageKey(packageKey, isPhase2)

        setContent {
            NudgeTheme {
                val state by viewModel.uiState.collectAsState()

                // Auto-dismiss when unlocked.
                LaunchedEffect(state.unlocked) {
                    if (state.unlocked) finish()
                }

                WalkUnlockScreen(
                    state = state,
                    onCancel = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE_KEY = "walk2unlock_package_key"
        const val EXTRA_IS_PHASE2   = "walk2unlock_is_phase2"
    }
}

// ── UI composable ─────────────────────────────────────────────────────────────

@Composable
private fun WalkUnlockScreen(
    state: WalkUnlockUiState,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            if (!state.sensorAvailable) {
                // Sensor fallback.
                Icon(
                    imageVector = Icons.Filled.DirectionsWalk,
                    contentDescription = "Walk",
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Step sensor not available",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your device doesn't have a step counter. The walk bypass is unavailable — your cooldown will expire on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Go back")
                }
                return@Column
            }

            // Step ring.
            val animatedProgress by animateFloatAsState(
                targetValue = state.progress,
                animationSpec = tween(durationMillis = 500),
                label = "stepProgress"
            )
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(160.dp),
                strokeWidth = 10.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Icon(
                imageVector = Icons.Filled.DirectionsWalk,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "${state.stepsWalked} / ${if (state.isPhase2) state.longGoal else state.shortGoal} steps",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(8.dp))

            val goalText = when {
                state.isPhase2 -> "Walk ${state.longGoal} steps to skip the cooldown"
                else           -> "Walk ${state.shortGoal} steps for 1 min  ·  ${state.longGoal} steps for 3 min"
            }
            Text(
                text = goalText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "${state.stepsRemaining} steps to go",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(40.dp))

            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}
