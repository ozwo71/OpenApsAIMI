package app.aaps.ui.compose.afrezzaDialog

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.afrezza.AfrezzaMaxBasalState
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.IDs
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.insulin.InsulinType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@Stable
class AfrezzaDialogViewModel @Inject constructor(
    private val insulinManager: InsulinManager,
    private val persistenceLayer: PersistenceLayer,
    private val uel: UserEntryLogger,
    private val dateUtil: DateUtil,
    private val rh: ResourceHelper,
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val profileFunction: ProfileFunction,
    private val preferences: Preferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(AfrezzaDialogUiState())
    val uiState: StateFlow<AfrezzaDialogUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data class ShowMessage(val message: String) : SideEffect()
        data object DoseLogged : SideEffect()
        data object OpenWizard : SideEffect()
    }

    private val _sideEffect = MutableSharedFlow<SideEffect>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

    init {
        val afrezzaIcfg = findAfrezzaIcfg()
        val maxBasalRate = preferences.get(DoubleKey.AfrezzaMaxBasalRate)
        _uiState.update {
            AfrezzaDialogUiState(
                afrezzaIcfg = afrezzaIcfg,
                isConfigured = afrezzaIcfg != null,
                maxBasalRate = maxBasalRate,
                maxBasalActive = AfrezzaMaxBasalState.isActive,
                maxBasalRemainingMinutes = AfrezzaMaxBasalState.remainingMinutes
            )
        }
    }

    private fun findAfrezzaIcfg(): ICfg? {
        val afrezzaPeak = InsulinType.OREF_INHALED_AFREZZA.insulinPeakTime
        return insulinManager.insulins.firstOrNull { it.insulinPeakTime == afrezzaPeak }
            ?: insulinManager.insulins.firstOrNull {
                val template = InsulinType.fromPeak(it.insulinPeakTime)
                template.isInhaled
            }
    }

    fun selectCartridge(units: Int) {
        _uiState.update { it.copy(selectedCartridge = units, showConfirmation = true) }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(showConfirmation = false, selectedCartridge = null) }
    }

    fun confirmAndLog() {
        val state = _uiState.value
        val units = state.selectedCartridge ?: return
        val iCfg = state.afrezzaIcfg ?: return

        _uiState.update { it.copy(isLogging = true) }

        viewModelScope.launch {
            try {
                val now = dateUtil.now()
                val bolus = BS(
                    timestamp = now,
                    amount = units.toDouble(),
                    type = BS.Type.NORMAL,
                    notes = rh.gs(R.string.afrezza_inhaled),
                    iCfg = iCfg,
                    ids = IDs(pumpId = now)
                )

                persistenceLayer.insertOrUpdateBolus(
                    bolus = bolus,
                    action = Action.BOLUS,
                    source = Sources.AfrezzaDialog,
                    note = rh.gs(R.string.afrezza_inhaled)
                )

                uel.log(
                    Action.BOLUS,
                    Sources.AfrezzaDialog,
                    rh.gs(R.string.afrezza_inhaled),
                    ValueWithUnit.Insulin(units.toDouble())
                )

                aapsLogger.info(LTag.UI, "Afrezza ${units}U logged with ICfg: ${iCfg.insulinLabel}")

                _sideEffect.tryEmit(SideEffect.ShowMessage(rh.gs(R.string.afrezza_logged, units)))
                _uiState.update { it.copy(isLogging = false, showConfirmation = false, showMaxBasalPrompt = true) }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to log Afrezza dose", e)
                _uiState.update { it.copy(isLogging = false, showConfirmation = false, selectedCartridge = null) }
            }
        }
    }

    fun dismissMaxBasalPrompt() {
        _uiState.update { it.copy(showMaxBasalPrompt = false, showCarbPrompt = true) }
    }

    fun acceptMaxBasalPrompt() {
        _uiState.update { it.copy(showMaxBasalPrompt = false, showDurationSelector = true) }
    }

    fun dismissDurationSelector() {
        _uiState.update { it.copy(showDurationSelector = false, showCarbPrompt = true) }
    }

    fun cancelMaxBasal() {
        AfrezzaMaxBasalState.cancel()
        _uiState.update { it.copy(maxBasalActive = false, maxBasalRemainingMinutes = 0) }
        _sideEffect.tryEmit(SideEffect.ShowMessage(rh.gs(R.string.afrezza_max_basal_cancelled)))
    }

    fun openWizard() {
        _uiState.update { it.copy(showCarbPrompt = false, selectedCartridge = null) }
        _sideEffect.tryEmit(SideEffect.OpenWizard)
    }

    fun dismissCarbPrompt() {
        _uiState.update { it.copy(showCarbPrompt = false, selectedCartridge = null) }
        _sideEffect.tryEmit(SideEffect.DoseLogged)
    }

    fun applyMaxBasal(durationMinutes: Int) {
        val maxBasalRate = preferences.get(DoubleKey.AfrezzaMaxBasalRate)
        viewModelScope.launch {
            try {
                val profile = profileFunction.getProfile()
                if (profile == null) {
                    aapsLogger.error(LTag.UI, "No active profile — cannot set temp basal")
                    _sideEffect.tryEmit(SideEffect.ShowMessage(rh.gs(R.string.afrezza_no_profile)))
                    return@launch
                }
                val result = commandQueue.tempBasalAbsolute(
                    absoluteRate = maxBasalRate,
                    durationInMinutes = durationMinutes,
                    enforceNew = true,
                    profile = profile,
                    tbrType = PumpSync.TemporaryBasalType.NORMAL
                )
                if (result.success) {
                    aapsLogger.info(LTag.UI, "Max basal $maxBasalRate U/h set for ${durationMinutes} min after Afrezza")
                    AfrezzaMaxBasalState.activate(maxBasalRate, durationMinutes)
                    _sideEffect.tryEmit(SideEffect.ShowMessage(rh.gs(R.string.afrezza_max_basal_set, maxBasalRate, durationMinutes)))
                    _uiState.update {
                        it.copy(
                            maxBasalActive = true,
                            maxBasalRemainingMinutes = AfrezzaMaxBasalState.remainingMinutes
                        )
                    }
                } else {
                    aapsLogger.error(LTag.UI, "Failed to set max basal: ${result.comment}")
                    _sideEffect.tryEmit(SideEffect.ShowMessage(rh.gs(R.string.afrezza_max_basal_failed)))
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Exception setting max basal", e)
                _sideEffect.tryEmit(SideEffect.ShowMessage(rh.gs(R.string.afrezza_max_basal_failed)))
            } finally {
                _uiState.update { it.copy(showDurationSelector = false, showCarbPrompt = true) }
            }
        }
    }
}
