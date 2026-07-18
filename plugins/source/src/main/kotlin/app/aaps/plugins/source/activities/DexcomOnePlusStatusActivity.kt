package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Daily status skeleton for Dexcom ONE+ native BG source.
 * Opens from plugin preferences; keeps UI simpler than Eversense status.
 */
@AndroidEntryPoint
class DexcomOnePlusStatusActivity : AppCompatActivity() {

    @Inject lateinit var dexcomOnePlusPlugin: DexcomOnePlusPlugin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dexcomOnePlusPlugin.syncDriverFromPrefs()
        setContent {
            AapsTheme {
                DexcomOnePlusStatusScreen(
                    onBack = { finish() },
                    onOpenStart = {
                        startActivity(Intent(this, DexcomOnePlusStartActivity::class.java))
                    },
                    onOpenWarmup = {
                        startActivity(Intent(this, DexcomOnePlusWarmupActivity::class.java))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexcomOnePlusStatusScreen(
    onBack: () -> Unit,
    onOpenStart: () -> Unit,
    onOpenWarmup: () -> Unit,
) {
    val driver = remember { OnePlusCgmDrivers.default() }
    var state by remember { mutableStateOf(driver.warmupState()) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }

    LaunchedEffect(Unit) {
        while (true) {
            state = driver.warmupState()
            sessionUp = driver.isSessionUp()
            delay(1_000L)
        }
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.dexcom_oneplus_status_title)) },
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
        ) {
            Text(
                text = stringResource(R.string.dexcom_oneplus_status_phase, phaseLabel(state.phase)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.dexcom_oneplus_status_session,
                    if (sessionUp) {
                        stringResource(R.string.dexcom_oneplus_session_up)
                    } else {
                        stringResource(R.string.dexcom_oneplus_session_down)
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.dexcom_oneplus_status_message,
                    state.message ?: stringResource(R.string.dexcom_oneplus_status_message_none),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dexcom_oneplus_start_action))
            }
            Button(
                onClick = onOpenWarmup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dexcom_oneplus_warmup_open))
            }
        }
    }
}

@Composable
private fun phaseLabel(phase: OnePlusWarmupState.Phase): String = when (phase) {
    OnePlusWarmupState.Phase.IDLE -> stringResource(R.string.dexcom_oneplus_phase_idle)
    OnePlusWarmupState.Phase.PAIRING -> stringResource(R.string.dexcom_oneplus_phase_pairing)
    OnePlusWarmupState.Phase.WARMING -> stringResource(R.string.dexcom_oneplus_phase_warming)
    OnePlusWarmupState.Phase.READY -> stringResource(R.string.dexcom_oneplus_phase_ready)
    OnePlusWarmupState.Phase.FAILED -> stringResource(R.string.dexcom_oneplus_phase_failed)
}
