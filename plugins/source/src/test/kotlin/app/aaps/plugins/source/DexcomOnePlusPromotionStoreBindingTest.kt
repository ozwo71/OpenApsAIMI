package app.aaps.plugins.source

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.session.OnePlusMacArbiter
import app.aaps.shared.tests.SharedPreferencesMock
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Where the promoted sensor writes its identity once it feeds the loop.
 *
 * Field report: "the pre-soak switch does not work". The promoted driver kept its store in a local
 * captured when its session was built, so after the promotion cleared the pre-soak file it read a
 * file with no PIN (key lost -> a full pairing on every reconnect, i.e. gaps) and wrote the new MAC
 * and key back into the retired pre-soak file. A second pre-soak then shared a file with the sensor
 * feeding the loop, and both links fought over it.
 */
class DexcomOnePlusPromotionStoreBindingTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var config: Config
    @Mock lateinit var context: Context
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var warmupBasalGuard: DexcomOnePlusWarmupBasalGuard

    private val availabilityProvider: DexcomOnePlusAvailabilityProvider = mock()
    private val bleRadioPriority: BleRadioPriority = mock()

    /** Promotion tells the active calibration plugin to drop the retired sensor's fingersticks. */
    private val activeCalibration: Calibration = mock()
    private val activePlugin: ActivePlugin = mock<ActivePlugin>().also { whenever(it.activeCalibration).thenReturn(activeCalibration) }

    private val productionPrefs: SharedPreferences = SharedPreferencesMock()
    private val stagingPrefs: SharedPreferences = SharedPreferencesMock()

    private lateinit var plugin: DexcomOnePlusPlugin

    @BeforeEach
    fun setup() = runTest {
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(PRODUCTION_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(productionPrefs)
        whenever(context.getSharedPreferences(STAGING_PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(stagingPrefs)
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(mock<BluetoothManager>())
        whenever(context.getSystemService(Context.POWER_SERVICE)).thenReturn(mock<PowerManager>())
        whenever(persistenceLayer.insertCgmSourceData(any(), any(), any(), anyOrNull()))
            .thenReturn(PersistenceLayer.TransactionResult())
        plugin = DexcomOnePlusPlugin(
            rh, aapsLogger, preferences, config, context, persistenceLayer, warmupBasalGuard, availabilityProvider, bleRadioPriority, activePlugin,
        )
    }

    /** The driver instances and their MAC claims are process-wide — none of it may travel to the next test. */
    @AfterEach
    fun tearDownDrivers() {
        runCatching { OnePlusCgmDrivers.select(useReal = false) }
        OnePlusMacArbiter.reset()
    }

    /** Pokes state a real staging session reaches only through BLE, which this test does not run. */
    private fun setPrivate(name: String, value: Any?) {
        DexcomOnePlusPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value)
    }

    /**
     * Gives the driver the context and the store that `setContext` would, without calling it:
     * `setContext` also resolves the device profile from `Build.MANUFACTURER`, which is null on the
     * JVM. Everything under test here (rebind on promotion, then read / write) is untouched by that.
     */
    private fun bindStagingDriver(driver: OnePlusCgmDriverReal) {
        OnePlusCgmDriverReal::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.set(driver, context)
        OnePlusCgmDriverReal::class.java.getDeclaredField("sensorStore")
            .apply { isAccessible = true }
            .set(driver, OnePlusSensorStore(context, OnePlusCgmDrivers.STAGING_NAMESPACE))
    }

    private fun readyStaging(mac: String) {
        val stagingStore = OnePlusSensorStore(context, OnePlusCgmDrivers.STAGING_NAMESPACE)
        // A PIN is what makes the stored session loadable at all, so the promotion can adopt it.
        stagingStore.saveIdentity(OnePlusSensorIdentity(pin = "1234", serial = "SOAK1"))
        stagingStore.startSessionForSensor(mac, System.currentTimeMillis() - 60_000L, null)
        setPrivate("stagingPresent", true)
        setPrivate("stagingValidEgvCount", 6)
        setPrivate("stagingLastValueMgdl", 120.0)
        setPrivate("stagingLastValueAtMs", System.currentTimeMillis())
    }

    @Test
    fun `after promotion the live link saves its MAC into the production file, not the wiped pre-soak one`() = runTest {
        val mac = "AA:BB:CC:DD:EE:03"
        readyStaging(mac)
        val promoted = OnePlusCgmDrivers.staging()
        bindStagingDriver(promoted)

        assertThat(plugin.promoteStagingToProduction(allowEarly = true)).isEqualTo(PromotionResult.Ok)

        // What the running link does on its very next reconnect.
        promoted.onAuthSucceeded(mac, ByteArray(16) { 7 })

        assertThat(productionPrefs.getString(KEY_MAC, null)).isEqualTo(mac)
        // The retired pre-soak file must stay empty, or the next pre-soak shares a file with the
        // sensor that now feeds the loop, and the two links fight over it.
        assertThat(stagingPrefs.getString(KEY_MAC, null)).isNull()
        assertThat(stagingPrefs.getString(KEY_PIN, null)).isNull()
    }

    @Test
    fun `after promotion the live link reads the production file, which kept the sensor identity`() = runTest {
        val mac = "AA:BB:CC:DD:EE:04"
        readyStaging(mac)
        val promoted = OnePlusCgmDrivers.staging()
        bindStagingDriver(promoted)

        assertThat(plugin.promoteStagingToProduction(allowEarly = true)).isEqualTo(PromotionResult.Ok)

        // A null store or a null PIN here is the production bug: with no loadable session the link
        // falls back to a full pairing on every single reconnect.
        val session = promoted.sensorStore()?.load()
        assertThat(session?.identity?.pin).isEqualTo("1234")
        assertThat(session?.identity?.serial).isEqualTo("SOAK1")
    }

    @Test
    fun `promotion tells calibration to drop the retired sensor's fingersticks`() = runTest {
        readyStaging("AA:BB:CC:DD:EE:05")
        bindStagingDriver(OnePlusCgmDrivers.staging())

        assertThat(plugin.promoteStagingToProduction(allowEarly = true)).isEqualTo(PromotionResult.Ok)

        // The sensor change written here is dated at the pre-soak start, so without this the fit
        // would pick up every fingerstick taken during the soak, all paired against the old sensor.
        val cutOff = argumentCaptor<Long>()
        verify(activeCalibration).ignoreEntriesBefore(cutOff.capture())
        assertThat(cutOff.firstValue).isAtLeast(System.currentTimeMillis() - 60_000L)
    }

    // ------------ correcting the insertion date ------------

    @Test
    fun `correcting the insertion date moves the driver clock and the sensor change together`() = runTest {
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
        val pairedAt = System.currentTimeMillis() - 2 * HOUR_MS
        val reallyInsertedAt = pairedAt - 6 * HOUR_MS
        OnePlusSensorStore(context, null).startSessionForSensor("AA:BB:CC:DD:EE:06", pairedAt, null)
        // What the plugin itself wrote at pairing time: this is the event that shadows an earlier
        // one added by hand, because the dashboard only ever reads the newest sensor change.
        val autoEvent = TE(id = 42L, timestamp = pairedAt, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL)
        whenever(persistenceLayer.getTherapyEventDataFromToTime(any(), any())).thenReturn(listOf(autoEvent))

        val verdict = plugin.correctProductionSensorStart(reallyInsertedAt)

        assertThat(verdict).isEqualTo(DexcomOnePlusSensorStartCorrection.Verdict.Accepted)
        assertThat(productionPrefs.getLong(KEY_SESSION_START, 0L)).isEqualTo(reallyInsertedAt)
        verify(persistenceLayer).invalidateTherapyEvent(eq(42L), any(), any(), anyOrNull(), any())
        verify(persistenceLayer).insertCgmSourceData(any(), any(), any(), eq(reallyInsertedAt))
    }

    @Test
    fun `a refused date changes nothing`() = runTest {
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
        val pairedAt = System.currentTimeMillis() - 2 * HOUR_MS
        OnePlusSensorStore(context, null).startSessionForSensor("AA:BB:CC:DD:EE:07", pairedAt, null)

        val verdict = plugin.correctProductionSensorStart(System.currentTimeMillis() + HOUR_MS)

        assertThat(verdict).isEqualTo(DexcomOnePlusSensorStartCorrection.Verdict.InFuture)
        assertThat(productionPrefs.getLong(KEY_SESSION_START, 0L)).isEqualTo(pairedAt)
        verify(persistenceLayer, never()).invalidateTherapyEvent(any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `with sensor change events off only the driver clock is corrected`() = runTest {
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(false)
        val pairedAt = System.currentTimeMillis() - 2 * HOUR_MS
        val reallyInsertedAt = pairedAt - 3 * HOUR_MS
        OnePlusSensorStore(context, null).startSessionForSensor("AA:BB:CC:DD:EE:08", pairedAt, null)

        assertThat(plugin.correctProductionSensorStart(reallyInsertedAt))
            .isEqualTo(DexcomOnePlusSensorStartCorrection.Verdict.Accepted)

        assertThat(productionPrefs.getLong(KEY_SESSION_START, 0L)).isEqualTo(reallyInsertedAt)
        // The user's own entries are not this source's to remove.
        verify(persistenceLayer, never()).invalidateTherapyEvent(any(), any(), any(), anyOrNull(), any())
    }

    companion object {

        private const val HOUR_MS = 60L * 60L * 1000L
        private const val KEY_SESSION_START = "session_start_ms"
        private const val PRODUCTION_PREFS_NAME = "dexcom_oneplus_sensor"
        private const val STAGING_PREFS_NAME = "dexcom_oneplus_sensor_staging"
        private const val KEY_MAC = "last_mac"
        private const val KEY_PIN = "pin"
    }
}
