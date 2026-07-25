package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.recursive.BeliefLeafId

/**
 * Soft catalog caps are proposals for Harmonia arbitration; hard caps bind via min().
 * See [docs/AIMI_HARMONIA_SMB_ARBITRATION.md].
 */
enum class PatternCapKind {
    SOFT,
    HARD,
}

/**
 * Aggregated meal/protective SMB cap with explicit soft vs hard semantics.
 */
data class PatternCapProposal(
    val proposedCapU: Double,
    val kind: PatternCapKind,
    val sourceId: PhysiologicalPatternId? = null,
)

data class PhysiologicalPatternReading(
    val id: PhysiologicalPatternId,
    val confidence: Double,
    val reason: String,
)

/**
 * Aggregated pattern view for one loop tick — feeds RBT ctx, HTR caps, and JSONL export.
 */
data class PhysiologicalPatternSnapshot(
    val active: List<PhysiologicalPatternReading>,
    val dominant: PhysiologicalPatternId?,
    val dominantConfidence: Double,
    val suppressMealInterpretation: Boolean,
    val suppressHyperRelease: Boolean,
    val suppressWaveletBoost: Boolean,
    val smbCapU: Double?,
    val smbCapKind: PatternCapKind? = null,
    val mealPatternCap: PatternCapProposal? = null,
    val reasonSummary: String,
) {
    fun capsHtrRelease(): Boolean = suppressHyperRelease

    /**
     * Binding cap for min() paths.
     * Soft meal proposals never bind here, but any active HARD protective pattern still does —
     * including when a meal soft proposal is the primary [smbCapU] (exercise, stacking, post-hypo…).
     * Null [smbCapKind] on a bare [smbCapU] is treated as HARD (legacy).
     */
    fun hardBindingCapU(): Double? {
        var hard: Double? = null
        for (reading in active) {
            if (reading.confidence < 0.35) continue
            val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
            if (def.capKind != PatternCapKind.HARD) continue
            val cap = def.smbCapU ?: continue
            hard = hard?.let { minOf(it, cap) } ?: cap
        }
        if (smbCapKind == null || smbCapKind == PatternCapKind.HARD) {
            smbCapU?.let { cap -> hard = hard?.let { minOf(it, cap) } ?: cap }
        }
        return hard
    }

    /** Soft proposal exposed to Harmonia; never a silent hard mute. */
    fun softProposedCapU(): Double? =
        smbCapU?.takeIf { smbCapKind == PatternCapKind.SOFT }
            ?: mealPatternCap?.proposedCapU?.takeIf { mealPatternCap.kind == PatternCapKind.SOFT }

    fun credibilityScaleFor(leafId: BeliefLeafId): Double {
        var scale = 1.0
        for (reading in active) {
            if (reading.confidence < 0.35) continue
            val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
            scale = minOf(scale, def.leafCredibilityScale(leafId, reading.confidence))
        }
        return scale.coerceIn(0.05, 1.0)
    }

    fun weightScaleFor(leafId: BeliefLeafId): Double {
        var scale = 1.0
        for (reading in active) {
            if (reading.confidence < 0.35) continue
            val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
            scale = minOf(scale, def.leafWeightScale(leafId, reading.confidence))
        }
        return scale.coerceIn(0.05, 1.0)
    }

    companion object {
        val EMPTY = PhysiologicalPatternSnapshot(
            active = emptyList(),
            dominant = null,
            dominantConfidence = 0.0,
            suppressMealInterpretation = false,
            suppressHyperRelease = false,
            suppressWaveletBoost = false,
            smbCapU = null,
            smbCapKind = null,
            mealPatternCap = null,
            reasonSummary = "none",
        )
    }
}

data class PhysiologicalPatternInput(
    val bgMgdl: Double,
    val targetBgMgdl: Double,
    val highBgBandMgdl: Double,
    val deltaMgdlPer5: Double,
    val shortAvgDeltaMgdlPer5: Double,
    val combinedDeltaMgdlPer5: Double,
    val mealCobG: Double,
    val hourOfDay: Int,
    val stepsLast15m: Int,
    val heartRateBpm: Int,
    val restingHeartRateBpm: Int,
    val iobU: Double,
    val maxIobU: Double,
    val bestTerminalMgdl: Double,
    val floorTerminalMgdl: Double,
    val phaseOutput: PhysiologicalPhaseClassifier.Output?,
    val physioContext: PhysioContextMTR?,
    val sleepDebtMinutes: Int,
    val sleepEfficiency: Double,
    val mealAbsorptionPhase: MealAbsorptionPhase,
    val mealDeliveryPriority: Boolean,
    val stackingSurveillance: Boolean,
    val endogenousCounterRegulatory: Boolean,
    val postHypoOrdinal: Int?,
    val exerciseLockout: Boolean,
    val sportTime: Boolean,
    val sleepTime: Boolean,
    val contextIllness: Boolean,
    val contextStress: Boolean,
    val contextActivity: Boolean,
    val compressionImpossibleRise: Boolean,
    val dwellAboveHighBgMinutes: Int,
    val trajectoryRelevanceScore: Double,
    val nowMs: Long,
)

data class PatternDefinition(
    val id: PhysiologicalPatternId,
    val category: PhysiologicalPatternCategory,
    val dominantScaleMinutes: Int,
    val suppressMealInterpretation: Boolean = false,
    val suppressHyperRelease: Boolean = false,
    val suppressWaveletBoost: Boolean = false,
    val smbCapU: Double? = null,
    val capKind: PatternCapKind = PatternCapKind.HARD,
    private val mealLeafCredScale: Double = 1.0,
    private val hyperLeafCredScale: Double = 1.0,
    private val waveletCredScale: Double = 1.0,
    private val uamLeafCredScale: Double = 1.0,
) {
    fun leafCredibilityScale(leafId: BeliefLeafId, confidence: Double): Double {
        val blend = 1.0 - (1.0 - confidence.coerceIn(0.0, 1.0)) * 0.5
        return when (leafId) {
            BeliefLeafId.MEAL_PHASE,
            BeliefLeafId.MEAL_AGGR,
            BeliefLeafId.MEAL_MEMORY,
            BeliefLeafId.MEAL_ADVISOR,
            BeliefLeafId.SCEN_MEAL_CTX,
            BeliefLeafId.SCEN_ADVISOR_COB,
            BeliefLeafId.PKPD_UAM,
            BeliefLeafId.UAM_CONF,
            -> minOf(1.0, mealLeafCredScale + (1.0 - mealLeafCredScale) * (1.0 - blend))

            BeliefLeafId.PKPD_BEST,
            BeliefLeafId.HTR_RELEASE,
            BeliefLeafId.HTR_TIER,
            BeliefLeafId.MPC_IMPLIED,
            BeliefLeafId.SCEN_TRAJ_RISE,
            -> minOf(1.0, hyperLeafCredScale + (1.0 - hyperLeafCredScale) * (1.0 - blend))

            BeliefLeafId.BG_DERIV,
            BeliefLeafId.TRAJ_GEOM,
            BeliefLeafId.MPC_HORIZON,
            -> minOf(1.0, waveletCredScale + (1.0 - waveletCredScale) * (1.0 - blend))

            else -> 1.0
        }
    }

    fun leafWeightScale(leafId: BeliefLeafId, confidence: Double): Double =
        leafCredibilityScale(leafId, confidence)
}

fun PhysiologicalPhase.toPatternId(): PhysiologicalPatternId? = when (this) {
    PhysiologicalPhase.DAWN_CORTISOL -> PhysiologicalPatternId.DAWN_CORTISOL
    PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL -> PhysiologicalPatternId.MALE_CIRCADIAN_HORMONAL
    PhysiologicalPhase.FEMALE_CYCLE_HORMONAL -> PhysiologicalPatternId.FEMALE_CYCLE_HORMONAL
    PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY -> PhysiologicalPatternId.ENDOGENOUS_COUNTER_REGULATORY
    PhysiologicalPhase.STRESS_CORTISOL -> PhysiologicalPatternId.STRESS_CORTISOL_ACUTE
    PhysiologicalPhase.MEAL_DECLARED -> PhysiologicalPatternId.MEAL_DECLARED
    PhysiologicalPhase.MEAL_UNDECLARED -> PhysiologicalPatternId.MEAL_UNDECLARED_FAST
    PhysiologicalPhase.HYPER_INSTALLED -> PhysiologicalPatternId.HYPER_INSTALLED
    PhysiologicalPhase.OFF -> null
}
