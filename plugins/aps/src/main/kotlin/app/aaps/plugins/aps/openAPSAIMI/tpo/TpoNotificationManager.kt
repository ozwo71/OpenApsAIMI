package app.aaps.plugins.aps.openAPSAIMI.tpo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-visible notification when a TPO protection session starts or ends.
 * Controlled by [BooleanKey.OApsAIMITpoNotifyOnApply].
 */
@Singleton
class TpoNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: Preferences,
    private val uiInteraction: UiInteraction,
) {
    companion object {
        private const val CHANNEL_ID = "AIMI_TPO_PROTECTION"
        private const val NOTIFICATION_ID = 8891
        private const val OPENAPS_AIMI_PLUGIN_ROUTE = "plugin_preferences/OpenAPSAIMIPlugin"
        private const val EXTRA_NAVIGATE_ROUTE = "extra_navigate_route"
    }

    init {
        createNotificationChannel()
    }

    fun showSessionStarted(session: TpoSessionDocument) {
        if (!preferences.get(BooleanKey.OApsAIMITpoNotifyOnApply)) return
        if (session.status != TpoSessionStatus.ACTIVE) return

        val ui = TpoUiSupport.buildActiveSessionUi(session, System.currentTimeMillis()) ?: return
        val packLabel = context.getString(ui.packTitleResId)
        val title = context.getString(R.string.aimi_tpo_notification_started_title)
        val text = context.getString(
            R.string.aimi_tpo_notification_started_text,
            packLabel,
            ui.remainingMinutes,
            ui.changedKeyCount,
        )
        val bigText = buildString {
            append(text)
            append('\n')
            append(context.getString(R.string.aimi_tpo_notification_started_tier, ui.tierLabel))
            if (ui.deltaPreviewLines.isNotEmpty()) {
                append('\n')
                ui.deltaPreviewLines.forEach { line ->
                    append(line)
                    append('\n')
                }
            }
            if (ui.extraChangeCount > 0) {
                append(context.getString(R.string.aimi_tpo_extra_changes, ui.extraChangeCount))
            }
        }.trim()

        postNotification(title, text, bigText)
    }

    fun showSessionEnded(reason: TpoEndReason) {
        if (!preferences.get(BooleanKey.OApsAIMITpoNotifyOnApply)) return
        cancelNotification()
        val title = context.getString(R.string.aimi_tpo_notification_ended_title)
        val text = when (reason) {
            TpoEndReason.EXPIRED -> context.getString(R.string.aimi_tpo_notification_ended_expired)
            TpoEndReason.MANUAL_REVERT -> context.getString(R.string.aimi_tpo_notification_ended_manual)
            TpoEndReason.SUPERSEDED -> context.getString(R.string.aimi_tpo_notification_ended_superseded)
        }
        postNotification(title, text, text)
    }

    fun cancelNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun postNotification(title: String, text: String, bigText: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(CoreUiR.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createOpenAimiPrefsIntent())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+
        }
    }

    private fun createOpenAimiPrefsIntent(): PendingIntent {
        val intent = Intent(context, uiInteraction.mainActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAVIGATE_ROUTE, OPENAPS_AIMI_PLUGIN_ROUTE)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.aimi_tpo_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.aimi_tpo_notification_channel_description)
            enableVibration(false)
            setSound(null, null)
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

enum class TpoEndReason {
    EXPIRED,
    MANUAL_REVERT,
    SUPERSEDED,
}
