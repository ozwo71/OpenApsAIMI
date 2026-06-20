package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.content.Context
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdict
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import java.util.Locale

/**
 * Builds user-visible auditor report text from [AuditorVerdictCache] and tracker status.
 */
object AuditorReportFormatter {

  private const val DEFAULT_CACHE_MAX_AGE_MS = 300_000L

  fun insightCount(maxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS): Int {
    val cached = AuditorVerdictCache.getDisplayable(maxAgeMs) ?: return 0
    val evidenceCount = cached.verdict.evidence.size
    return if (evidenceCount > 0) evidenceCount else 1
  }

  fun hasUnreadVerdict(lastReadTimestampMs: Long, maxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS): Boolean {
    val cached = AuditorVerdictCache.getDisplayable(maxAgeMs) ?: return false
    return cached.timestamp > lastReadTimestampMs
  }

  fun buildNotificationSummary(context: Context, uiState: AuditorUIState): String {
    val count = uiState.insightCount
    return if (count > 1) {
      context.getString(R.string.aimi_auditor_notification_summary_multiple, count)
    } else {
      context.getString(R.string.aimi_auditor_notification_summary_single)
    }
  }

  fun buildNotificationBigText(context: Context, uiState: AuditorUIState): String {
    val summary = buildNotificationSummary(context, uiState)
    val reportBody = buildReportBody(context, includeStatusLine = true)
      ?: uiState.statusMessage
    return buildString {
      append(summary)
      append('\n')
      append('\n')
      append(reportBody)
      append('\n')
      append('\n')
      append(context.getString(R.string.aimi_auditor_notification_tap_to_view))
    }
  }

  fun buildInAppNotificationText(context: Context): String {
    val shortSummary = buildShortVerdictSummary(context)
      ?: context.getString(R.string.aimi_auditor_notification_summary_single)
    return context.getString(R.string.aimi_auditor_in_app_notification_summary, shortSummary)
  }

  fun buildFullReportMessage(context: Context): String {
    return buildReportBody(context, includeStatusLine = true)
      ?: context.getString(R.string.aimi_auditor_report_no_cache)
  }

  private fun buildShortVerdictSummary(context: Context): String? {
    val cached = AuditorVerdictCache.getDisplayable() ?: return null
    return cached.verdict.verdict.name
  }

  private fun buildReportBody(context: Context, includeStatusLine: Boolean): String? {
    val cached = AuditorVerdictCache.getDisplayable() ?: return null
    return formatVerdict(context, cached.verdict, trackerStatusMessageIfNeeded(includeStatusLine))
  }

  private fun trackerStatusMessageIfNeeded(includeStatusLine: Boolean): String? {
    if (!includeStatusLine) return null
    val (status, _) = AuditorStatusTracker.getStatus()
    return if (status.isActive()) status.message else null
  }

  internal fun formatVerdict(
    context: Context,
    verdict: AuditorVerdict,
    statusMessage: String?,
  ): String {
    val confidencePercent = (verdict.confidence * 100.0).toInt().coerceIn(0, 100)
    return buildString {
      append(
        context.getString(
          R.string.aimi_auditor_report_verdict_line,
          verdict.verdict.name,
          confidencePercent,
        )
      )
      statusMessage?.let { status ->
        append('\n')
        append(context.getString(R.string.aimi_auditor_report_status_line, status))
      }
      if (verdict.degradedMode) {
        append('\n')
        append(context.getString(R.string.aimi_auditor_report_degraded_mode))
      }
      if (verdict.evidence.isNotEmpty()) {
        append('\n')
        append('\n')
        append(context.getString(R.string.aimi_auditor_report_evidence_header))
        verdict.evidence.forEach { line ->
          append('\n')
          append("• ")
          append(line)
        }
      }
      if (verdict.riskFlags.isNotEmpty()) {
        append('\n')
        append('\n')
        append(context.getString(R.string.aimi_auditor_report_risk_flags_header))
        verdict.riskFlags.forEach { flag ->
          append('\n')
          append("• ")
          append(flag)
        }
      }
      append('\n')
      append('\n')
      append(context.getString(R.string.aimi_auditor_report_adjustments_header))
      append('\n')
      append(
        context.getString(
          R.string.aimi_auditor_report_smb_factor,
          formatFactor(verdict.boundedAdjustments.smbFactorClamp),
        )
      )
      append('\n')
      append(
        context.getString(
          R.string.aimi_auditor_report_interval_add,
          verdict.boundedAdjustments.intervalAddMin,
        )
      )
      append('\n')
      append(
        if (verdict.boundedAdjustments.preferTbr) {
          context.getString(R.string.aimi_auditor_report_prefer_tbr_yes)
        } else {
          context.getString(R.string.aimi_auditor_report_prefer_tbr_no)
        }
      )
      append('\n')
      append(
        context.getString(
          R.string.aimi_auditor_report_tbr_factor,
          formatFactor(verdict.boundedAdjustments.tbrFactorClamp),
        )
      )
    }
  }

  private fun formatFactor(value: Double): String =
    String.format(Locale.US, "%.2f", value)
}
