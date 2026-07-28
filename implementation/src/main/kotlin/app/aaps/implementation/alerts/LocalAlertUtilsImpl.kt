package app.aaps.implementation.alerts

import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.ui.R
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Created by adrian on 17/12/17.
 */
@Singleton
class LocalAlertUtilsImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val smsCommunicator: SmsCommunicator,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val notificationManager: NotificationManager,
    @ApplicationScope private val appScope: CoroutineScope
) : LocalAlertUtils {

    init {
        preferences.registerPreferences(LocalAlertLongKey::class.java)
    }

    private fun missedReadingsThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsStaleDataThreshold).toLong()).msecs()
    }

    private fun pumpUnreachableThreshold(): Long {
        return T.mins(preferences.get(IntKey.AlertsPumpUnreachableThreshold).toLong()).msecs()
    }

    override fun checkPumpUnreachableAlarm(lastConnection: Long, isStatusOutdated: Boolean, isDisconnected: Boolean) {
        val alarmTimeoutExpired = isAlarmTimeoutExpired(lastConnection, pumpUnreachableThreshold())
        val nextAlarmOccurrenceReached = preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < dateUtil.now()
        if (config.APS && isStatusOutdated && alarmTimeoutExpired && nextAlarmOccurrenceReached && !isDisconnected) {
            if (preferences.get(BooleanKey.AlertPumpUnreachable)) {
                aapsLogger.debug(LTag.CORE, "Generating pump unreachable alarm. lastConnection: " + dateUtil.dateAndTimeString(lastConnection) + " isStatusOutdated: true")
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, dateUtil.now() + pumpUnreachableThreshold())
                notificationManager.post(NotificationId.PUMP_UNREACHABLE, R.string.pump_unreachable, soundRes = R.raw.alarm)
                if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS)
                    appScope.launch {
                        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                            therapyEvent = TE.asAnnouncement(rh.gs(R.string.pump_unreachable)),
                            timestamp = dateUtil.now(),
                            action = Action.CAREPORTAL,
                            source = Sources.Aaps,
                            note = rh.gs(R.string.pump_unreachable),
                            listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                        )
                    }
            }
            if (preferences.get(BooleanKey.SmsReportPumpUnreachable))
                smsCommunicator.sendNotificationToAllNumbers(rh.gs(R.string.pump_unreachable))
        }
        if (!isStatusOutdated && !alarmTimeoutExpired) notificationManager.dismiss(NotificationId.PUMP_UNREACHABLE)
    }

    private fun isAlarmTimeoutExpired(lastConnection: Long, unreachableThreshold: Long): Boolean {
        return if (activePlugin.activePump.pumpDescription.hasCustomUnreachableAlertCheck) {
            activePlugin.activePump.isUnreachableAlertTimeoutExceeded(unreachableThreshold)
        } else {
            lastConnection + pumpUnreachableThreshold() < dateUtil.now()
        }
    }

    /*Pre-snoozes the alarms with 5 minutes if no snooze exists.
     * Call only at startup!
     */
    override fun preSnoozeAlarms() {
        if (preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm) < dateUtil.now()) {
            preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, dateUtil.now() + 5 * 60 * 1000)
        }
        if (preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < dateUtil.now()) {
            preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, dateUtil.now() + 5 * 60 * 1000)
        }
    }

    override fun shortenSnoozeInterval() { //shortens alarm times in case of setting changes or future data
        var nextMissedReadingsAlarm = preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)
        nextMissedReadingsAlarm = min(dateUtil.now() + missedReadingsThreshold(), nextMissedReadingsAlarm)
        preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, nextMissedReadingsAlarm)
        var nextPumpDisconnectedAlarm = preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)
        nextPumpDisconnectedAlarm = min(dateUtil.now() + pumpUnreachableThreshold(), nextPumpDisconnectedAlarm)
        preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, nextPumpDisconnectedAlarm)
    }

    override suspend fun reportPumpStatusRead() {
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        if (profile != null) {
            val lastConnection = pump.lastDataTime.value
            val earliestAlarmTime = lastConnection + pumpUnreachableThreshold()
            if (preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm) < earliestAlarmTime) {
                preferences.put(LocalAlertLongKey.NextPumpDisconnectedAlarm, earliestAlarmTime)
            }
        }
    }

    override suspend fun checkStaleBGAlert() {
        val bgReading = persistenceLayer.getLastGlucoseValue() ?: return
        if (preferences.get(BooleanKey.AlertMissedBgReading)
            && bgReading.timestamp + missedReadingsThreshold() < dateUtil.now()
            && preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm) < dateUtil.now()
        ) {
            preferences.put(LocalAlertLongKey.NextMissedReadingsAlarm, dateUtil.now() + missedReadingsThreshold())
            notificationManager.post(NotificationId.BG_READINGS_MISSED, R.string.missed_bg_readings, soundRes = R.raw.alarm)
            if (preferences.get(BooleanKey.NsClientCreateAnnouncementsFromErrors) && config.APS) {
                appScope.launch {
                    persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = TE.asAnnouncement(rh.gs(R.string.missed_bg_readings)),
                        timestamp = dateUtil.now(),
                        action = Action.CAREPORTAL,
                        source = Sources.Aaps,
                        note = rh.gs(R.string.missed_bg_readings),
                        listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                    )
                }
            }
        } else if (dateUtil.isOlderThan(bgReading.timestamp, 5).not()) {
            notificationManager.dismiss(NotificationId.BG_READINGS_MISSED)
        }
    }

    override suspend fun checkGlucoseAlerts() {
        val last = persistenceLayer.getLastGlucoseValue() ?: return
        val now = dateUtil.now()
        // Freshness guard: never alarm on stale data — the stale-data alarm owns that case.
        if (last.timestamp + missedReadingsThreshold() < now) return

        checkHypoAlert(last.value, now)
        checkHyperAlert(last.value, now)
        checkRapidFallAlert(last.value, now)
    }

    private fun checkHypoAlert(bgMgdl: Double, now: Long) {
        if (!preferences.get(BooleanKey.AlertHypo)) return
        val threshold = preferences.get(UnitDoubleKey.AlertHypoThreshold)
        if (bgMgdl <= threshold) {
            if (preferences.get(LocalAlertLongKey.NextHypoAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextHypoAlarm, now + HYPO_REALARM_MS)
                notificationManager.post(NotificationId.BG_HYPO, R.string.alert_hypo_message, profileUtil.fromMgdlToStringWithUnits(bgMgdl), soundRes = R.raw.alarm)
            }
        } else if (bgMgdl >= threshold + HYPO_HYSTERESIS_MGDL) {
            notificationManager.dismiss(NotificationId.BG_HYPO)
            preferences.put(LocalAlertLongKey.NextHypoAlarm, 0L)
        }
    }

    private fun checkHyperAlert(bgMgdl: Double, now: Long) {
        if (!preferences.get(BooleanKey.AlertHyper)) return
        val threshold = preferences.get(UnitDoubleKey.AlertHyperThreshold)
        if (bgMgdl >= threshold) {
            if (preferences.get(LocalAlertLongKey.NextHyperAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextHyperAlarm, now + HYPER_REALARM_MS)
                notificationManager.post(NotificationId.BG_HYPER, R.string.alert_hyper_message, profileUtil.fromMgdlToStringWithUnits(bgMgdl))
            }
        } else if (bgMgdl <= threshold - HYPER_HYSTERESIS_MGDL) {
            notificationManager.dismiss(NotificationId.BG_HYPER)
            preferences.put(LocalAlertLongKey.NextHyperAlarm, 0L)
        }
    }

    private suspend fun checkRapidFallAlert(bgMgdl: Double, now: Long) {
        if (!preferences.get(BooleanKey.AlertRapidFall)) return
        val windowMinutes = preferences.get(IntKey.AlertRapidFallWindow)
        val readings = persistenceLayer.getBgReadingsDataFromTime(now - T.mins(windowMinutes.toLong()).msecs(), ascending = true)
        // Need at least two readings spanning the window; otherwise a data gap makes the slope meaningless.
        if (readings.size < 2) return
        val drop = readings.first().value - readings.last().value
        val dropThreshold = preferences.get(UnitDoubleKey.AlertRapidFallDrop)
        if (drop >= dropThreshold) {
            if (preferences.get(LocalAlertLongKey.NextRapidFallAlarm) < now) {
                preferences.put(LocalAlertLongKey.NextRapidFallAlarm, now + RAPID_FALL_REALARM_MS)
                notificationManager.post(
                    NotificationId.BG_RAPID_FALL,
                    R.string.alert_rapid_fall_message,
                    profileUtil.fromMgdlToStringWithUnits(drop),
                    windowMinutes,
                    soundRes = R.raw.alarm
                )
            }
        } else if (drop <= dropThreshold / 2.0) {
            // Slope has flattened — clear and re-arm for the next fall episode.
            notificationManager.dismiss(NotificationId.BG_RAPID_FALL)
            preferences.put(LocalAlertLongKey.NextRapidFallAlarm, 0L)
        }
    }

    companion object {

        // Re-alarm throttles: while a condition persists, re-post at most this often (anti-spam).
        private val HYPO_REALARM_MS = T.mins(15).msecs()
        private val HYPER_REALARM_MS = T.mins(30).msecs()
        private val RAPID_FALL_REALARM_MS = T.mins(15).msecs()

        // Hysteresis bands (mg/dL): dismiss only once the value has clearly left the alarm zone.
        private const val HYPO_HYSTERESIS_MGDL = 5.0
        private const val HYPER_HYSTERESIS_MGDL = 10.0
    }
}