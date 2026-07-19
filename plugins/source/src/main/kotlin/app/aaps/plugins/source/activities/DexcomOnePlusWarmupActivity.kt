package app.aaps.plugins.source.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.DexcomOnePlusUiLabels
import app.aaps.plugins.source.compose.DexcomOnePlusWarmupCountdown
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Warm-up countdown UI bound to [OnePlusWarmupState].
 *
 * Countdown source (see [DexcomOnePlusWarmupCountdown]):
 * - Prefer protocol `remainingMs` / `endsAtEpochMs`.
 * - Local ~30 min fallback timer ONLY when both are null and phase is WARMING.
 */
@AndroidEntryPoint
class DexcomOnePlusWarmupActivity : AppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    DexcomOnePlusWarmupScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexcomOnePlusWarmupScreen(onBack: () -> Unit) {
    val driver = remember { OnePlusCgmDrivers.default() }
    var state by remember { mutableStateOf(driver.warmupState()) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }
    var localFallbackEndsAt by remember { mutableStateOf<Long?>(null) }
    var remainingMs by remember { mutableStateOf<Long?>(null) }
    var usingLocalFallback by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            state = driver.warmupState()
            sessionUp = driver.isSessionUp()
            if (DexcomOnePlusWarmupCountdown.shouldStartLocalFallback(state) && localFallbackEndsAt == null) {
                // Local fallback ONLY if remainingMs (and endsAt) are null — documented above.
                localFallbackEndsAt = now + DexcomOnePlusWarmupCountdown.LOCAL_FALLBACK_DURATION_MS
            }
            if (state.phase != OnePlusWarmupState.Phase.WARMING) {
                localFallbackEndsAt = null
            }
            usingLocalFallback =
                state.remainingMs == null &&
                    state.endsAtEpochMs == null &&
                    localFallbackEndsAt != null &&
                    state.phase == OnePlusWarmupState.Phase.WARMING
            remainingMs = DexcomOnePlusWarmupCountdown.resolveRemainingMs(
                state = state,
                nowEpochMs = now,
                localFallbackEndsAtEpochMs = localFallbackEndsAt,
            )
            delay(1_000L)
        }
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.dexcom_oneplus_warmup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dexcom_oneplus_nav_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_heading),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_phase, DexcomOnePlusUiLabels.phaseLabel(state.phase)),
                style = MaterialTheme.typography.bodyLarge,
            )
            when (state.phase) {
                OnePlusWarmupState.Phase.READY -> {
                    Text(
                        text = stringResource(R.string.dexcom_oneplus_warmup_done),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OnePlusWarmupState.Phase.FAILED -> {
                    Text(
                        text = stringResource(
                            R.string.dexcom_oneplus_warmup_failed,
                            DexcomOnePlusUiLabels.userMessage(state.message),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    val countdown = remainingMs?.let { DexcomOnePlusWarmupCountdown.formatMmSs(it) }
                        ?: stringResource(R.string.dexcom_oneplus_warmup_countdown_unknown)
                    Text(
                        text = stringResource(R.string.dexcom_oneplus_warmup_countdown, countdown),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    val endMs = state.endsAtEpochMs ?: localFallbackEndsAt
                    if (endMs != null) {
                        val endLabel = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endMs))
                        Text(
                            text = stringResource(R.string.dexcom_oneplus_warmup_ends_at, endLabel),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (usingLocalFallback) {
                        Text(
                            text = stringResource(R.string.dexcom_oneplus_warmup_local_fallback_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_no_loop_bg),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    if (sessionUp) {
                        R.string.dexcom_oneplus_warmup_session_up
                    } else {
                        R.string.dexcom_oneplus_warmup_session_down
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
