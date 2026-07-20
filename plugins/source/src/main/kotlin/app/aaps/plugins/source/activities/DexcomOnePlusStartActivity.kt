package app.aaps.plugins.source.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
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
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.dexcomoneplus.identity.OnePlusGs1ApplicatorParser
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.dexcomoneplus.session.OnePlusSessionStart
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.OnePlusBlePermissionHelper
import app.aaps.plugins.source.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Sensor start: BLE permissions, LE scan (DX02/FEBC), applicator GS1 / PIN, connect → warm-up.
 */
@AndroidEntryPoint
class DexcomOnePlusStartActivity : AppCompatActivity() {

    @Inject lateinit var dexcomOnePlusPlugin: DexcomOnePlusPlugin
    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val sensorStore = remember { OnePlusSensorStore(context.applicationContext) }
    val storedSession = remember { sensorStore.load() }
    val scanner = remember {
        OnePlusBleScannerAndroid(context.applicationContext, sessionHint = storedSession)
    }
    val focusManager = LocalFocusManager.current

    var applicatorInput by remember {
        mutableStateOf(storedSession?.identity?.rawGs1 ?: storedSession?.identity?.pin.orEmpty())
    }
    var parsedIdentity by remember {
        mutableStateOf(
            storedSession?.identity
                ?: OnePlusGs1ApplicatorParser.parse(applicatorInput),
        )
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember {
        mutableStateOf<OnePlusScanResult?>(
            storedSession?.lastMac?.let { mac ->
                OnePlusScanResult(address = mac, name = storedSession.lastDeviceName, rssi = 0)
            },
        )
    }
    val devices = remember { mutableStateListOf<OnePlusScanResult>() }

    val codeRequired = stringResource(R.string.dexcom_oneplus_pairing_code_required)
    val codeInvalid = stringResource(R.string.dexcom_oneplus_pairing_code_invalid)
    val permissionsNeeded = stringResource(R.string.dexcom_oneplus_permissions_needed)
    val deviceRequired = stringResource(R.string.dexcom_oneplus_device_required)
    val realSkeletonRequired = stringResource(R.string.dexcom_oneplus_real_skeleton_required)
    val serialNone = stringResource(R.string.dexcom_oneplus_applicator_serial_none)

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
            OutlinedTextField(
                value = applicatorInput,
                onValueChange = { text ->
                    applicatorInput = text
                    parsedIdentity = OnePlusGs1ApplicatorParser.parse(text)
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dexcom_oneplus_applicator_gs1_label)) },
                supportingText = {
                    val identity = parsedIdentity
                    if (identity != null) {
                        Text(
                            stringResource(
                                R.string.dexcom_oneplus_applicator_parsed,
                                identity.pin,
                                identity.serial ?: serialNone,
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.dexcom_oneplus_applicator_gs1_hint))
                    }
                },
                singleLine = false,
                minLines = 2,
                isError = errorText != null && parsedIdentity == null,
            )
            storedSession?.lastMac?.let { mac ->
                Text(
                    text = stringResource(R.string.dexcom_oneplus_last_mac_hint, mac),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                        scanner.sessionHint = sensorStore.load()
                        scanner.startScan { hit ->
                            mainHandler.post {
                                val idx = devices.indexOfFirst { it.address == hit.address }
                                if (idx >= 0) devices[idx] = hit else devices.add(hit)
                                autoSelectBest(devices, scanner.sessionHint)?.let { best ->
                                    selected = best
                                }
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
                val hint = scanner.sessionHint
                val ranked = devices.sortedByDescending {
                    OnePlusAdvCandidate.rankScore(it.name, it.address, it.rssi, hint)
                }
                items(ranked, key = { it.address }) { device ->
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
                    val identity = parsedIdentity
                        ?: OnePlusGs1ApplicatorParser.parse(applicatorInput)
                    if (identity == null) {
                        errorText = codeRequired
                        return@Button
                    }
                    val code = OnePlusSessionStart.normalizePairingCode(identity.pin)
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
                    sensorStore.saveIdentity(identity.copy(pin = code))
                    sensorStore.saveLastMac(address)
                    selected?.name?.let { sensorStore.saveLastDeviceName(it) }
                    val activeDriver = onEnsureDriver()
                    activeDriver.setContext(context.applicationContext)
                    (activeDriver as? OnePlusCgmDriverReal)?.saveIdentity(identity.copy(pin = code))
                    if (activeDriver is OnePlusCgmDriverReal) {
                        activeDriver.connect(
                            deviceAddress = address,
                            pairingCode = code,
                            advertisedName = selected?.name,
                        )
                    } else {
                        activeDriver.connect(deviceAddress = address, pairingCode = code)
                    }
                    onStarted()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dexcom_oneplus_connect_follow))
            }
            errorText?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun autoSelectBest(
    devices: List<OnePlusScanResult>,
    session: OnePlusStoredSession?,
): OnePlusScanResult? {
    if (devices.isEmpty()) return null
    return devices.maxByOrNull {
        OnePlusAdvCandidate.rankScore(it.name, it.address, it.rssi, session)
    }
}
