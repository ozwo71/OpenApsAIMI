package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator // 🧠 PSE
import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController // 🧮 MPC
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield // 🛡️ CBF
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner // 🎓 Learner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataLake // 🗂️ Data Lake
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller // 🧹 Backfiller
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.MechanismAttentionGate // 🚪 Attention Gate
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveAuditor // 👨‍🏫 Auditor
import app.aaps.plugins.aps.openAPSAIMI.model.*
import app.aaps.core.keys.interfaces.PreferenceKey
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicReference

/**
 * 🧠 Autodrive Engine (iLet-like Architecture)
 * 
 * Moteur unifié de contrôle continu remplissant les fonctions cumulées de TrajectoryGuard,
 * DynamicBasalController, et SMB.
 * Actuellement en mode SHADOW (Fantôme) : il calcule et logge ses décisions sans ordonner à la pompe.
 */
@Singleton
class AutodriveEngine @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val stateEstimator: ContinuousStateEstimator,
    private val mpcController: MpcController,
    private val safetyShield: ControlBarrierShield,
    private val onlineLearner: OnlineLearner,
    private val autodriveAuditor: AutodriveAuditor,
    private val dataLake: AutodriveDataLake,
    private val dataBackfiller: AutodriveDataBackfiller,
    private val attentionGate: MechanismAttentionGate
) {

    private val systemState = AtomicReference<AimiState>(AimiState.Manual)
    private var aggressiveWindowUntilEpochMs: Long = 0L

    private companion object {
        const val AGGRESSIVE_HOLD_MS: Long = 12 * 60 * 1000L
    }

    fun setIsActive(enabled: Boolean) {
        updateState(isActive = enabled)
    }

    fun setShadowMode(enabled: Boolean) {
        updateState(isShadowMode = enabled)
    }

    private fun updateState(isActive: Boolean? = null, isShadowMode: Boolean? = null) {
        val current = systemState.get()
        if (current is AimiState.AutoDrive) {
            systemState.set(current.copy(
                isActive = isActive ?: current.isActive,
                isShadowMode = isShadowMode ?: current.isShadowMode
            ))
        } else {
            systemState.set(AimiState.AutoDrive(
                isActive = isActive ?: false,
                isShadowMode = isShadowMode ?: true,
                controllerType = AimiState.AutoDrive.ControllerType.Hybrid
            ))
        }
    }

    fun getAttentionMultiplier(): Double = attentionGate.lastAttentionMultiplier
    
    fun getHealthScore(): Double = autodriveAuditor.lastHealthScore

    fun onlineLearnerStatus(): OnlineLearner.StatusSnapshot = onlineLearner.statusSnapshot()

    /**
     * Records a training row for a tick where the gate did not engage.
     *
     * Only the dataset is touched — no estimator update, no learner step, no command. The decision
     * columns stay neutral and `Engaged` is 0, so the classifier can condition on engagement rather
     * than the dataset being silently filtered by it.
     */
    fun recordDisengagedSnapshot(state: AutoDriveState, currentEpochMs: Long) {
        dataLake.recordSnapshot(
            state = state,
            rawCommand = null,
            safeCommand = null,
            engaged = false,
            currentTimestamp = currentEpochMs,
        )
    }

    /**
     * T3C basal-only proposal: runs the full Autodrive pipeline and returns TBR demand.
     * Restores prior engine mode afterward. Caller must strip/ignore SMB (never enact bolus).
     *
     * ⚠️ ASYNC IMPACT: temporarily activates Autodrive for one tick compute (learner + DataLake update).
     */
    fun proposeBasalOnlyTbr(
        currentState: AutoDriveState,
        profileBasal: Double,
        profileIsf: Double,
        lgsThreshold: Double,
        hour: Int,
        steps: Int,
        hr: Int,
        rhr: Int,
        currentEpochMs: Long = System.currentTimeMillis(),
    ): BasalOnlyTbrProposal? {
        val previous = systemState.get()
        return try {
            setShadowMode(false)
            setIsActive(true)
            val cmd = tick(
                currentState = currentState,
                profileBasal = profileBasal,
                profileIsf = profileIsf,
                lgsThreshold = lgsThreshold,
                hour = hour,
                steps = steps,
                hr = hr,
                rhr = rhr,
                currentEpochMs = currentEpochMs,
            ) ?: return null
            BasalOnlyTbrProposal(
                tbrUph = cmd.temporaryBasalRate,
                strippedSmbU = cmd.scheduledMicroBolus.coerceAtLeast(0.0),
                reason = cmd.reason,
            )
        } finally {
            systemState.set(previous)
        }
    }

    data class BasalOnlyTbrProposal(
        val tbrUph: Double,
        val strippedSmbU: Double,
        val reason: String,
    )

    /**
     * Point d'entrée principal à chaque Tique (5 min) depuis DetermineBasalAIMI2.
     */
    fun tick(
        currentState: AutoDriveState,
        profileBasal: Double,
        profileIsf: Double,
        lgsThreshold: Double,
        hour: Int,
        steps: Int,
        hr: Int,
        rhr: Int,
        currentEpochMs: Long = System.currentTimeMillis(),
        /**
         * Hyper-trajectory Ra floor, in mg/dL/min, or 0.0 for none.
         *
         * Applied to the **controller's** view only. The barrier shield keeps the estimator's honest
         * value: it reads `estimatedRa` to size its own margins
         * (`ControlBarrierShield.kt:67, 101-103, 115`), and a safety barrier fed an optimistic input
         * is no longer a barrier.
         *
         * Until now this floor was computed, passed in as `AutoDriveState.estimatedRa`, and silently
         * discarded — line 165 overwrote the field before the estimator ran. It reached the recursive
         * belief tree but never the MPC its producer is named after.
         */
        mpcRaFloorMgdlPerMin: Double = 0.0
    ): AutoDriveCommand? {
        val state = systemState.get() as? AimiState.AutoDrive ?: return null
        if (!state.isActive && !state.isShadowMode) return null

        // On injecte les données physiologiques temps réel dans l'état avant traitement
        val stateWithContext = currentState.copy(
            hour = hour,
            steps = steps,
            hr = hr,
            rhr = rhr
        )

        // 0. Le Processus d'apprentissage en ligne s'exécute pour affiner les paramètres
        onlineLearner.learnAndUpdate(stateWithContext, currentEpochMs)

        // 🎓 `learnedSensitivityFactor` is observed, not applied.
        //
        // Its training target is self-described in OnlineLearner as "un mock simple pour valider
        // l'architecture": `bg + velocity * 30`, a linear extrapolation, not the MPC trajectory. The
        // error of a linear extrapolation measures how curved the glucose trace is, not how strongly
        // insulin acts — so accumulating it into a sensitivity is not meaningful.
        //
        // It is also structurally pinned at 1.0 today: the step is `error * 0.001 * 0.005` (~1e-4 per
        // evaluation), it only advances on engaged ticks, and the field is never persisted, so every
        // restart resets it. Persisting it, as the obvious "fix", would let a meaningless quantity
        // accumulate to the ±50 % clamp and multiply `estimatedSI` — which reaches both MpcController
        // and ControlBarrierShield.
        //
        // The learner keeps running and reporting through `onlineLearnerStatus()`, so the signal stays
        // observable. Apply it only once its target is a real MPC trajectory.
        val learningAdjustedState = stateWithContext

        // 1. Attention Gate (Phase 9 - ML On-Device)
        // L'intelligence artificielle vient potentiellement biaiser la sensibilité perçue 
        // pour corriger de manière prédictive le comportement du MPC face à des menaces physio.
        val attentionState = attentionGate.applyAttention(learningAdjustedState)

        // 2. PSE (Physiological State Estimator) Update
        // [FIX CRITIQUE]: On réinjecte le Ra précédemment appris pour garder le momentum de la courbe
        val stateWithMomentum = attentionState.copy(estimatedRa = stateEstimator.getLastRa())
        val estimatedState = stateEstimator.updateAndPredict(stateWithMomentum)

        // 2. MPC (Model Predictive Controller) Calculation
        // The floor lifts what the controller anticipates, never what the estimator believes: it is a
        // feed-forward, not an observation, so it must not ratchet into the next tick's prediction.
        val mpcState = if (
            mpcRaFloorMgdlPerMin.isFinite() && mpcRaFloorMgdlPerMin > estimatedState.estimatedRa
        ) {
            aapsLogger.debug(
                LTag.APS,
                "🍽️ HTR_RA_FLOOR: MPC sees Ra=${"%.2f".format(mpcRaFloorMgdlPerMin)} " +
                    "instead of estimated ${"%.2f".format(estimatedState.estimatedRa)}",
            )
            estimatedState.copy(estimatedRa = mpcRaFloorMgdlPerMin)
        } else {
            estimatedState
        }
        val rawCommand = mpcController.calculateOptimalDose(mpcState, profileBasal, lgsThreshold)

        // 3. CBF (Control Barrier Shield) Safety Check — honest Ra on purpose, see the parameter doc.
        val safeCommand = safetyShield.enforce(rawCommand, estimatedState, profileBasal)

        // 5. Explicabilité de l'IA (Auditor Traducteur)
        val auditedReason = autodriveAuditor.generateHumanReadableReason(
            state = estimatedState,
            baseProfileIsf = profileIsf, // Utilisation de l'ISF réel du profil (Phase 7)
            rawCommand = rawCommand,
            safeCommand = safeCommand
        )
        val auditedCommand = safeCommand.copy(reason = auditedReason)

        // 6. Data Lake CSV persistancy (Pour entraînement V3)
        // L'enregistrement est silencieux et asynchrone par rapport à la boucle de contrôle
        dataLake.recordSnapshot(
            state = estimatedState,
            rawCommand = rawCommand,
            safeCommand = auditedCommand,
            engaged = true,
            currentTimestamp = currentEpochMs
        )

        // 7. Logging & Shadow metrics
        if (state.isShadowMode) {
            logShadowDecision(currentState, auditedCommand, profileBasal)
        }

        if (!state.isActive) return null

        // 8. Quiet Mode Handover (Rollback to AIMI V2 PI Controller)
        // Autodrive V3 (MPC) is mathematically aggressive by nature. For calm waters and slight upstream drifts, 
        // the legacy proportional controller is superior. We yield control (return null) unless V3 is actively fighting.
        // 🚀 T9: Aggression boost based on UAM and CombinedDelta with hysteresis window.
        val aggressiveSignal = estimatedState.estimatedRa > 0.6 ||
            estimatedState.bgVelocity > 1.1 ||
            estimatedState.uamConfidence > 0.65 ||
            estimatedState.combinedDelta > 3.2
        if (aggressiveSignal) {
            aggressiveWindowUntilEpochMs = currentEpochMs + AGGRESSIVE_HOLD_MS
        }
        val inAggressiveWindow = currentEpochMs < aggressiveWindowUntilEpochMs

        val isHigh = estimatedState.bg > 145.0
        val needsSmb = auditedCommand.scheduledMicroBolus > 0.0
        val needsSafetyBrake = auditedCommand.temporaryBasalRate == 0.0
        
        // 🚀 HYBRID SMOOTHING: If the requested correction is minor (< 0.1 U/h delta),
        // let V2 handle the fine-tuning fluidity.
        // Fix #6: Lowered from 0.3 to 0.1 to allow V3 to engage more often and collect ML data.
        val tbrDelta = Math.abs(auditedCommand.temporaryBasalRate - profileBasal)
        val isStrongCorrection = tbrDelta > 0.15

        return if (inAggressiveWindow || isHigh || needsSmb || needsSafetyBrake || isStrongCorrection) {
            auditedCommand
        } else {
            aapsLogger.debug(
                LTag.APS,
                "💤 [AUTODRIVE_V3] Quiet Mode: Delegating prophylactic TBR adjustments to legacy V2 PI Controller."
            )
            null
        }
    }

    private fun logShadowDecision(state: AutoDriveState, autodriveCommand: AutoDriveCommand, profileBasal: Double) {
        aapsLogger.debug(
            LTag.APS,
            "👽 [AUTODRIVE_SHADOW] BG: ${state.bg} (v: ${String.format("%.1f", state.bgVelocity)}) | " +
            "Est_SI: ${String.format("%.2f", state.estimatedSI)} | Est_Ra: ${String.format("%.2f", state.estimatedRa)} || " +
            "Autodrive dictates: TBR=${autodriveCommand.temporaryBasalRate} U/h, " +
            "SMB=${autodriveCommand.scheduledMicroBolus} U | Reason: ${autodriveCommand.reason}"
        )
    }

    /**
     * Advanced Decoupled Execution: Applies verified verdicts to the system.
     */
    fun applyVerdicts(verdicts: List<AimiVerdict>): List<AimiState.SafetyIntervention> {
        val interventions = mutableListOf<AimiState.SafetyIntervention>()
        
        verdicts.forEach { verdict ->
            when (verdict) {
                is AimiVerdict.Confirmed -> enactAction(verdict.action)
                is AimiVerdict.Modified -> enactAction(verdict.modifiedAction)
                is AimiVerdict.Rejected -> {
                    aapsLogger.warn(LTag.APS, "🚫 Action rejected by Auditor: ${verdict.auditorReason}")
                }
            }
        }
        
        return interventions
    }

    private fun enactAction(action: AimiAction) {
        when (action) {
            is AimiAction.TemporaryBasal -> {
                aapsLogger.info(LTag.APS, "🚀 Enacting TBR: ${action.rate} U/h for ${action.durationMinutes}m")
            }
            is AimiAction.SMB -> {
                aapsLogger.info(LTag.APS, "🚀 Enacting SMB: ${action.amount} U")
            }
            is AimiAction.Bolus -> {
                aapsLogger.info(LTag.APS, "🚀 Enacting Bolus: ${action.amount} U")
            }
            is AimiAction.PreferenceUpdate -> {
                aapsLogger.info(LTag.APS, "🚀 Updating Preference: ${action.key.key} -> ${action.newValue}")
            }
            is AimiAction.Notification -> {
                aapsLogger.info(LTag.APS, "🚀 Sending Notification: ${action.title}")
            }
        }
    }
}
