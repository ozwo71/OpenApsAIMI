package app.aaps.plugins.source.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.os.HandlerCompat
import android.os.Looper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.dexcomoneplus.session.OnePlusSessionStart
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.OnePlusBlePermissionHelper
import app.aaps.plugins.source.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Sensor start: BLE permissions, LE scan (DXC/FEBC), pairing code, connect → warm-up.
 */
@AndroidEntryPoint
class DexcomOnePlusStartActivity : AppCompatActivity() {

    @Inject lateinit var dexcomOnePlusPlugin: DexcomOnePlusPlugin
    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Align Stub/Real with eng pref and keep plugin watcher on the active driver.
        dexcomOnePlusPlugin.syncDriverFromPrefs()
        val driver = OnePlusCgmDrivers.default()
        driver.setContext(applicationContext)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    DexcomOnePlusStartScreen(
                        onBack = { finish() },
                        onEnsureDriver = {
                            dexcomOnePlusPlugin.syncDriverFromPrefs()
                            OnePlusCgmDrivers.default()
                        },
                        onStarted = {
                            startActivity(Intent(this, DexcomOnePlusWarmupActivity::class.java))
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexcomOnePlusStartScreen(
    onBack: () -> Unit,
    onEnsureDriver: () -> OnePlusCgmDriver,
    onStarted: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainHandler = remember { HandlerCompat.createAsync(Looper.getMainLooper()) }
    val scanner = remember { OnePlusBleScannerAndroid(context.applicationContext) }
    val focusManager = LocalFocusManager.current

    var pairingCode by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OnePlusScanResult?>(null) }
    val devices = remember { mutableStateListOf<OnePlusScanResult>() }

    val codeRequired = stringResource(R.string.dexcom_oneplus_pairing_code_required)
    val codeInvalid = stringResource(R.string.dexcom_oneplus_pairing_code_invalid)
    val permissionsNeeded = stringResource(R.string.dexcom_oneplus_permissions_needed)
    val deviceRequired = stringResource(R.string.dexcom_oneplus_device_required)
    val realSkeletonRequired = stringResource(R.string.dexcom_oneplus_real_skeleton_required)

    DisposableEffect(Unit) {
        onDispose {
            scanner.stopScan()
        }
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.dexcom_oneplus_start_title)) },
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
                .padding(AapsSpacing.extraLarge)
                .clearFocusOnTap(focusManager),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        ) {
            Text(
                text = stringResource(R.string.dexcom_oneplus_start_steps),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.dexcom_oneplus_permissions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
                Button(
                    onClick = { OnePlusBlePermissionHelper.requestMissing(activity) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_request_permissions))
                }
            }
            Text(
                text = stringResource(R.string.dexcom_oneplus_dexcom_recovery_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.dexcom_oneplus_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                OutlinedButton(
                    onClick = {
                        if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
                            errorText = permissionsNeeded
                            OnePlusBlePermissionHelper.requestMissing(activity)
                            return@OutlinedButton
                        }
                        devices.clear()
                        selected = null
                        scanner.startScan { hit ->
                            mainHandler.post {
                                val idx = devices.indexOfFirst { it.address == hit.address }
                                if (idx >= 0) devices[idx] = hit else devices.add(hit)
                            }
                        }
                        scanning = true
                        errorText = null
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !scanning,
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_scan_start))
                }
                OutlinedButton(
                    onClick = {
                        scanner.stopScan()
                        scanning = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = scanning,
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_scan_stop))
                }
            }
            Text(
                text = if (scanning) {
                    stringResource(R.string.dexcom_oneplus_scan_scanning)
                } else {
                    stringResource(R.string.dexcom_oneplus_scan_idle, devices.size)
                },
                style = MaterialTheme.typography.labelMedium,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = AapsSpacing.xxLarge * 9),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                items(devices, key = { it.address }) { device ->
                    val selectedHere = selected?.address == device.address
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = device
                                errorText = null
                            }
                            .padding(vertical = AapsSpacing.small),
                    ) {
                        Text(
                            text = device.name ?: stringResource(R.string.dexcom_oneplus_scan_unnamed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedHere) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = stringResource(
                                R.string.dexcom_oneplus_scan_device_line,
                                device.address,
                                device.rssi,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = pairingCode,
                onValueChange = {
                    pairingCode = it.filter { ch -> ch.isDigit() }.take(OnePlusSessionStart.EXPECTED_CODE_LENGTH)
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dexcom_oneplus_pairing_code_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = errorText != null,
                supportingText = errorText?.let { msg ->
                    { Text(msg) }
                },
            )
            if (!OnePlusCgmDrivers.useRealSkeleton) {
                Text(
                    text = stringResource(R.string.dexcom_oneplus_real_skeleton_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    if (!OnePlusCgmDrivers.useRealSkeleton) {
                        errorText = realSkeletonRequired
                        return@Button
                    }
                    val code = OnePlusSessionStart.normalizePairingCode(pairingCode)
                    if (code.isEmpty()) {
                        errorText = codeRequired
                        return@Button
                    }
                    if (!OnePlusSessionStart.isValidPairingCode(code)) {
                        errorText = codeInvalid
                        return@Button
                    }
                    if (activity != null && !OnePlusBlePermissionHelper.hasAll(activity)) {
                        errorText = permissionsNeeded
                        OnePlusBlePermissionHelper.requestMissing(activity)
                        return@Button
                    }
                    val address = selected?.address
                    if (address.isNullOrBlank()) {
                        errorText = deviceRequired
                        return@Button
                    }
                    scanner.stopScan()
                    scanning = false
                    val activeDriver = onEnsureDriver()
                    activeDriver.setContext(context.applicationContext)
                    activeDriver.connect(deviceAddress = address, pairingCode = code)
                    onStarted()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dexcom_oneplus_connect_follow))
            }
        }
    }
}
