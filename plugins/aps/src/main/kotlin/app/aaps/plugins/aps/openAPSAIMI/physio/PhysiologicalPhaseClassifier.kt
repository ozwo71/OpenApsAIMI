package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import kotlin.math.abs

/**
 * Classifies the dominant physiological phase for behavioral risk (HTR / MPC / scenario).
 */
object PhysiologicalPhaseClassifier {

    data class Input(
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val highBgPreferenceMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val combinedDeltaMgdlPer5: Double,
        val mealCobG: Double,
        val hourOfDay: Int,
        val stepsLast15m: Int,
        val heartRateBpm: Int,
        val restingHeartRateBpm: Int,
        val bestTerminalMgdl: Double,
        val floorTerminalMgdl: Double,
        val dwellAboveHighBgMinutes: Int,
        val wCycleEnabled: Boolean,
        val wCycleTrackingMode: CycleTrackingMode?,
        val wCyclePhase: CyclePhase?,
        val htrTier: HyperSeverityTier = HyperSeverityTier.OFF,
        val plateauSustain: Boolean = false,
    )

    data class Output(
        val phase: PhysiologicalPhase,
        val confidence: Double,
        val policy: BehavioralRiskPolicy,
    )

    fun classify(input: Input): Output {
        val highBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(
            input.targetBgMgdl,
            input.highBgPreferenceMgdl,
        )
        val dev = input.bgMgdl - input.targetBgMgdl
        val projectionLead = input.bestTerminalMgdl - input.bgMgdl

        if (input.mealCobG >= 5.0) {
            return out(PhysiologicalPhase.MEAL_DECLARED, 0.95, "COB>=5g")
        }

        val mealLikeRise = isMealLikeRise(input, highBand, projectionLead)
        if (mealLikeRise) {
            return out(PhysiologicalPhase.MEAL_UNDECLARED, 0.88, "mealLike Δ/gap/proj")
        }

        if (isStressCortisol(input)) {
            return out(PhysiologicalPhase.STRESS_CORTISOL, 0.82, "stress Δ+HR COB=0")
        }

        if (input.plateauSustain ||
            input.htrTier == HyperSeverityTier.ESTABLISHED ||
            input.htrTier == HyperSeverityTier.DEEP
        ) {
            if (dev >= highBand * 1.5 || input.dwellAboveHighBgMinutes >= 45) {
                return out(PhysiologicalPhase.HYPER_INSTALLED, 0.80, "hyperInstalled tier/dwell")
            }
        }

        val hormonalKinetic = isHormonalKinetic(input, highBand, dev, projectionLead)
        if (hormonalKinetic) {
            val inDawnWindow = input.hourOfDay in 4..10
            if (inDawnWindow) {
                if (isFemaleCycleHormonal(input)) {
                    return out(
                        PhysiologicalPhase.FEMALE_CYCLE_HORMONAL,
                        0.85,
                        "wCycle ${input.wCyclePhase} dawnWindow",
                    )
                }
                if (isMaleCircadianProfile(input)) {
                    return out(
                        PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL,
                        0.84,
                        "maleCircadian h=${input.hourOfDay}",
                    )
                }
                return out(PhysiologicalPhase.DAWN_CORTISOL, 0.83, "dawnWindow COB=0 slowRamp")
            }
        }

        return out(PhysiologicalPhase.OFF, 0.5, "noDominantPhase")
    }

    private fun out(phase: PhysiologicalPhase, confidence: Double, reason: String): Output {
        val conf = confidence.coerceIn(0.0, 1.0)
        return Output(
            phase = phase,
            confidence = conf,
            policy = BehavioralRiskPolicy.forPhase(phase, conf, reason),
        )
    }

    internal fun isMealLikeRise(input: Input, highBand: Double, projectionLead: Double): Boolean {
        if (input.mealCobG >= 1.0) return false
        val fastRise = input.deltaMgdlPer5 >= 2.5 ||
            input.shortAvgDeltaMgdlPer5 >= 2.2 ||
            input.combinedDeltaMgdlPer5 >= 3.2
        val strongProjection = projectionLead >= highBand * 1.1 &&
            input.bestTerminalMgdl >= input.bgMgdl + highBand * 0.35
        val gap = input.bestTerminalMgdl - input.floorTerminalMgdl
        val gapMin = HyperSeverityClassifier.gapMinMgdl(55.0)
        val projectionHyper = gap >= gapMin && strongProjection
        return fastRise && (strongProjection || projectionHyper)
    }

    internal fun isHormonalKinetic(
        input: Input,
        highBand: Double,
        dev: Double,
        projectionLead: Double,
    ): Boolean {
        if (input.mealCobG >= 1.0) return false
        if (dev >= highBand * 1.25) return false
        val slowRamp = input.deltaMgdlPer5 < 4.0 &&
            input.shortAvgDeltaMgdlPer5 < 3.5 &&
            input.combinedDeltaMgdlPer5 < 3.5
        val nearTarget = dev < highBand
        val projectionNotMealLike = projectionLead <= highBand * 1.35 ||
            input.bestTerminalMgdl <= input.bgMgdl + 55.0
        return slowRamp && nearTarget && projectionNotMealLike
    }

    internal fun isStressCortisol(input: Input): Boolean {
        if (input.mealCobG >= 1.0) return false
        val hrElevated = input.heartRateBpm > input.restingHeartRateBpm + 12
        val acute = input.deltaMgdlPer5 >= 4.0 || input.combinedDeltaMgdlPer5 >= 4.5
        return hrElevated && acute
    }

    internal fun isFemaleCycleHormonal(input: Input): Boolean {
        if (!input.wCycleEnabled) return false
        val mode = input.wCycleTrackingMode ?: return false
        if (mode == CycleTrackingMode.MENOPAUSE || mode == CycleTrackingMode.NO_MENSES_LARC) {
            return false
        }
        val phase = input.wCyclePhase ?: return false
        return phase == CyclePhase.LUTEAL || phase == CyclePhase.OVULATION
    }

    /** Male / non-cycle-tracking: circadian hormonal morning profile. */
    internal fun isMaleCircadianProfile(input: Input): Boolean {
        if (!input.wCycleEnabled) return true
        val mode = input.wCycleTrackingMode ?: return true
        return mode == CycleTrackingMode.MENOPAUSE
    }

    fun capHtrTier(tier: HyperSeverityTier, maxTier: HyperSeverityTier): HyperSeverityTier {
        if (!tier.isReleaseEligible) return tier
        if (!maxTier.isReleaseEligible) return HyperSeverityTier.OFF
        return if (tier.ordinal > maxTier.ordinal) maxTier else tier
    }

    fun isExtendedDawnGuardActive(policy: BehavioralRiskPolicy?, hour: Int, cob: Double): Boolean {
        if (policy?.extendedDawnGuard != true) return false
        if (cob >= 0.1) return false
        return hour in 4..10
    }
}
