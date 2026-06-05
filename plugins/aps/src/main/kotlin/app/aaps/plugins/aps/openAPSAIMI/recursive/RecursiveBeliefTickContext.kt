package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelope
import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminals
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis

/**
 * Aggregated tick inputs for RBT leaf collection — populated from [DetermineBasalaimiSMB2].
 */
data class RecursiveBeliefTickContext(
    val bgMgdl: Double,
    val targetBgMgdl: Double,
    val highBgPreferenceMgdl: Double,
    val deltaMgdlPer5: Double,
    val shortAvgDeltaMgdlPer5: Double,
    val combinedDeltaMgdlPer5: Double,
    val iobU: Double,
    val maxIobU: Double,
    val maxSmbEffectiveU: Double,
    val tdd24hU: Double,
    val curves: AdvancedPredictionCurves,
    val scenario: ScenarioProjectionPair,
    val mealAbsorption: MealAbsorptionPhaseEngine.Output?,
    val physioPhase: PhysiologicalPhaseClassifier.Output?,
    val behavioralRisk: BehavioralRiskPolicy?,
    val trajectoryAnalysis: TrajectoryAnalysis?,
    val trajectoryRelevanceScore: Double,
    val safetyTerminals: SafetyPredictionTerminals?,
    val riskEnvelope: AimiRiskEnvelope?,
    val stackingStance: InsulinStackingStance.Evaluation?,
    val uamConfidence: Double,
    val contextSmbFactor: Float,
    val contextActivityActive: Boolean,
    val stepsLast15m: Int,
    val heartRateBpm: Int,
    val isNight: Boolean,
    val exerciseLockout: Boolean,
    val hypoMinPredIgnored: Boolean,
    val minPredictedBgMgdl: Double?,
    val dwellAboveHighBgMinutes: Int,
    val trajBridgePending: Boolean,
    val tubeAdvisorCapScale: Double?,
    // Late-tick (Autodrive path) — optional until MPC runs
    val v3SmbU: Double? = null,
    val htrResult: HyperTrajectoryReleaseResult? = null,
    val htrClassification: HyperSeverityClassifier.Output? = null,
    val tier1Hypo: Boolean = false,
    // Extended leaves (Phase 3–5)
    val bgHistoryMgdl: List<Double> = emptyList(),
    val waveletBands: WaveletBelief.Bands? = null,
    val physioMultipliers: PhysioMultipliersMTR? = null,
    val wCycleBasalMult: Double? = null,
    val wCycleSmbMult: Double? = null,
    val ngrSmbMult: Double? = null,
    val bgDerivShort: Double? = null,
    val insulinActivityStageOrdinal: Int? = null,
    val autodriveV3GateOpen: Boolean? = null,
    val correctionAggressionLevel: Double? = null,
    val endogenousCounterRegulatory: Boolean = false,
    val pkpdTailDamping: Double? = null,
    val shadowComparatorDeltaU: Double? = null,
    val shadowAuditorConfidence: Double? = null,
    val tuningContextLabel: String? = null,
    val shadowOrchestratorActive: Boolean = false,
    /** When true, resolver uses [RecursiveBeliefReleaseCalculator] instead of legacy HTR floor. */
    val replaceHtrRelease: Boolean = false,
    val extended: RbtExtendedSignals = RbtExtendedSignals.EMPTY,
)
