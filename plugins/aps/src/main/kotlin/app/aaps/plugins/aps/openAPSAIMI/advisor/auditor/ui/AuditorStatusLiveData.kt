package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive layer transforming [AuditorStatusTracker.Status] → [AuditorUIState].
 */
@Singleton
class AuditorStatusLiveData @Inject constructor() {

  private val _uiState = MutableLiveData(AuditorUIState.idle())
  val uiState: LiveData<AuditorUIState> = _uiState

  @Volatile
  private var lastReadTimestampMs: Long = 0L

  fun notifyUpdate() {
    val (status, ageMs) = AuditorStatusTracker.getStatus()
    val newState = transformStatusToUIState(status, ageMs)
    _uiState.postValue(newState)
  }

  private fun transformStatusToUIState(
    status: AuditorStatusTracker.Status,
    ageMs: Long?,
  ): AuditorUIState {
    if (status.name.contains("PROCESSING")) {
      return AuditorUIState.processing()
    }

    val displayableVerdict = AuditorVerdictCache.getDisplayable()
    if (displayableVerdict != null) {
      return uiStateFromDisplayableVerdict(displayableVerdict.verdict.verdict)
    }

    if (ageMs != null && ageMs > 300_000) {
      return AuditorUIState.idle()
    }

    return when {
      status == AuditorStatusTracker.Status.OFF -> AuditorUIState.idle()

      status.isSkipped() -> AuditorUIState.idle()

      status.isOffline() -> AuditorUIState.error(getOfflineMessage(status))

      status.isError() -> AuditorUIState.error(getErrorMessage(status))

      status.isActive() -> AuditorUIState.idle()

      else -> AuditorUIState.idle()
    }
  }

  fun markAsRead() {
    val verdictTimestamp = AuditorVerdictCache.getDisplayable()?.timestamp
    lastReadTimestampMs = verdictTimestamp ?: System.currentTimeMillis()
    notifyUpdate()
  }

  private fun uiStateFromDisplayableVerdict(verdictType: VerdictType): AuditorUIState {
    val insightCount = AuditorReportFormatter.insightCount()
    val shouldNotify = AuditorReportFormatter.hasUnreadVerdict(lastReadTimestampMs)
    return when (verdictType) {
      VerdictType.Confirm -> AuditorUIState.ready(insightCount, shouldNotify)
      VerdictType.Soften,
      VerdictType.ShiftToTbr,
      -> AuditorUIState.warning(
        message = "Important: ${verdictType.name}",
        shouldNotify = shouldNotify,
      )
    }
  }

  private fun getOfflineMessage(status: AuditorStatusTracker.Status): String {
    return when (status) {
      AuditorStatusTracker.Status.OFFLINE_NO_APIKEY -> "No API key configured"
      AuditorStatusTracker.Status.OFFLINE_NO_NETWORK -> "No network connection"
      AuditorStatusTracker.Status.OFFLINE_NO_ENDPOINT -> "API endpoint unavailable"
      AuditorStatusTracker.Status.OFFLINE_DNS_FAIL -> "DNS resolution failed"
      else -> "Offline"
    }
  }

  private fun getErrorMessage(status: AuditorStatusTracker.Status): String {
    return when (status) {
      AuditorStatusTracker.Status.ERROR_TIMEOUT -> "Request timeout"
      AuditorStatusTracker.Status.ERROR_PARSE -> "Parse error"
      AuditorStatusTracker.Status.ERROR_HTTP -> "HTTP error"
      AuditorStatusTracker.Status.ERROR_EXCEPTION -> "Exception occurred"
      else -> "Error"
    }
  }

  fun forceUpdate() {
    notifyUpdate()
  }

  fun reset() {
    lastReadTimestampMs = 0L
    _uiState.postValue(AuditorUIState.idle())
  }
}
