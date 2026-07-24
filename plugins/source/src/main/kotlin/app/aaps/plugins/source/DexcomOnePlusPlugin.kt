package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withActivity
import app.aaps.core.ui.compose.icons.IcPluginByoda
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.activities.DexcomOnePlusStartActivity
import app.aaps.plugins.source.activities.DexcomOnePlusStatusActivity
import app.aaps.plugins.source.activities.DexcomOnePlusWarmupActivity
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey
import app.aaps.plugins.source.keys.DexcomOnePlusIntentKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Dexcom ONE+ BG source (in-process BLE driver).
 *
 * Default driver is Stub; engineering pref can select Real skeleton (still fail-closed).
 * See docs/DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md.
 */
@Singleton
class DexcomOnePlusPlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
) : AbstractBgSourcePlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent {
            BgSourceComposeContent(
                title = rh.gs(R.string.dexcom_oneplus_native),
            )
        }
        .icon(IcPluginByoda)
        .pluginName(R.string.dexcom_oneplus_native)
        .shortName(R.string.dexcom_oneplus_short)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_dexcom_oneplus_native),
    ownPreferences = listOf(
        DexcomOnePlusIntentKey::class.java,
        DexcomOnePlusBooleanKey::class.java,
    ),
    aapsLogger,
    rh,
    preferences,
    config,
), BgSource, OnePlusGlucoseWatcher {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val driver
        get() = OnePlusCgmDrivers.default()

    @Volatile
    private var warmupPhase: OnePlusWarmupState.Phase = OnePlusWarmupState.Phase.IDLE

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "dexcom_oneplus_settings",
        titleResId = R.string.dexcom_oneplus_native,
        summaryResId = R.string.dexcom_oneplus_plugin_summary,
        items = listOf(
            DexcomOnePlusIntentKey.Status.withActivity(DexcomOnePlusStatusActivity::class.java),
            DexcomOnePlusIntentKey.Start.withActivity(DexcomOnePlusStartActivity::class.java),
            DexcomOnePlusIntentKey.Warmup.withActivity(DexcomOnePlusWarmupActivity::class.java),
            DexcomOnePlusBooleanKey.UseRealSkeleton,
        ),
        icon = pluginDescription.icon,
    )

    override suspend fun onStart() {
        super.onStart()
        syncDriverFromPrefs()
        val autoResumeQueued = (driver as? OnePlusCgmDriverReal)?.resumeStoredSession() == true
        warmupPhase = driver.warmupState().phase
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_SESSION: plugin start " +
                "realSkeleton=${OnePlusCgmDrivers.useRealSkeleton} autoResumeQueued=$autoResumeQueued",
        )
    }

    override suspend fun onStop() {
        driver.removeWatcher(this)
        driver.shutdown()
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_SESSION: plugin stop")
        super.onStop()
    }

    /**
     * Keep Stub/Real selection aligned with the engineering pref and ensure this plugin
     * watches the active driver. Safe to call from Start UI before connect.
     */
    fun syncDriverFromPrefs() {
        val wantReal = preferences.get(DexcomOnePlusBooleanKey.UseRealSkeleton)
        val selected = OnePlusCgmDrivers.select(useReal = wantReal, watcher = this)
        selected.setContext(context)
    }

    override fun onWarmup(state: OnePlusWarmupState) {
        warmupPhase = state.phase
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_WARMUP: phase=${state.phase} remainingMs=${state.remainingMs} msg=${state.message}",
        )
    }

    /**
     * Watcher callback thread is not guaranteed (stub: caller thread; Real may use bleExecutor).
     *
     * ⚠️ ASYNC IMPACT: GV mapping on the callback thread; [PersistenceLayer.insertCgmSourceData]
     * runs on [ioScope] (IO). Do not block the BLE executor from this callback.
     */
    override fun onGlucose(sample: OnePlusGlucoseSample) {
        if (DexcomOnePlusIngest.isWarmupBlockingIngest(warmupPhase)) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_BG: ignored during WARMING ${sample.mgdl.toInt()} @${sample.timestampMs}",
            )
            return
        }
        if (!DexcomOnePlusIngest.shouldAccept(sample)) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_BG: dedup drop ${sample.mgdl.toInt()} @${sample.timestampMs} seq=${sample.sequence}",
            )
            return
        }
        val glucoseValues = listOf(DexcomOnePlusIngest.mapToGv(sample))
        ioScope.launch {
            val result = persistenceLayer.insertCgmSourceData(
                Sources.DexcomOnePlus,
                glucoseValues,
                emptyList(),
                sensorInsertionTime = null,
            )
            aapsLogger.info(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_BG: insert complete — inserted: ${result.inserted.size}, updated: ${result.updated.size}",
            )
        }
    }

    override fun onSession(up: Boolean, reason: String?) {
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_SESSION: up=$up reason=$reason")
    }

    override fun onError(message: String, fatal: Boolean) {
        aapsLogger.error(LTag.BGSOURCE, "DEXCOM_ONEPLUS_ERROR: fatal=$fatal $message")
    }
}
