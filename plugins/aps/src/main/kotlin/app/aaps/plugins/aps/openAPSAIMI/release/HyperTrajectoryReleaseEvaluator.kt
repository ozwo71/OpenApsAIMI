package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.max
import kotlin.math.min

/**
 * Hyper Trajectory Release (HTR): lifts Autodrive V3 SMB when scenario + trajectory confirm hyper rise.
 */
object HyperTrajectoryReleaseEvaluator {

    data class Input(
        val enabled: Boolean,
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val highBgPreferenceMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val combinedDeltaMgdlPer5: Double,
        val floorTerminalMgdl: Double,
        val bestTerminalMgdl: Double,
        val tdd24hU: Double,
        val iobU: Double,
        val maxIobU: Double,
        val maxSmbEffectiveU: Double,
        val v3SmbU: Double,
        val dwellAboveHighBgMinutes: Int,
        val trajectoryType: TrajectoryType?,
        val minPredictedBgMgdl: Double?,
        val aggressive: Boolean = false,
        val isNight: Boolean = false,
        val exerciseLockout: Boolean = false,
        val mealCobG: Double = 0.0,
        val establishedDevOverrideMgdl: Double = 0.0,
        val deepDevOverrideMgdl: Double = 0.0,
    )

    fun evaluate(input: Input): HyperTrajectoryReleaseResult {
        val classification = HyperSeverityClassifier.classify(
            HyperSeverityClassifier.Input(
                bgMgdl = input.bgMgdl,
                targetBgMgdl = input.targetBgMgdl,
                highBgPreferenceMgdl = input.highBgPreferenceMgdl,
                deltaMgdlPer5 = input.deltaMgdlPer5,
                shortAvgDeltaMgdlPer5 = input.shortAvgDeltaMgdlPer5,
                combinedDeltaMgdlPer5 = input.combinedDeltaMgdlPer5,
                floorTerminalMgdl = input.floorTerminalMgdl,
                bestTerminalMgdl = input.bestTerminalMgdl,
                tdd24hU = input.tdd24hU,
                dwellAboveHighBgMinutes = input.dwellAboveHighBgMinutes,
                trajectoryType = input.trajectoryType,
                establishedDevOverrideMgdl = input.establishedDevOverrideMgdl,
                deepDevOverrideMgdl = input.deepDevOverrideMgdl,
            ),
        )

        val hypoMinPredIgnored = !HyperTrajectoryHypoCredibility.isMinPredictedCredible(
            bgMgdl = input.bgMgdl,
            minPredictedBgMgdl = input.minPredictedBgMgdl,
            targetBgMgdl = input.targetBgMgdl,
            highBgPreferenceMgdl = input.highBgPreferenceMgdl,
            tier = classification.tier,
        )

        if (!input.enabled || input.isNight || input.exerciseLockout || !classification.tier.isReleaseEligible) {
            val offReason = when {
                !input.enabled -> "disabled"
                input.isNight -> "night"
                input.exerciseLockout -> "exercise"
                else -> "tier"
            }
            return inactive(classification, input.v3SmbU, hypoMinPredIgnored, offReason)
        }

        val tierWeight = tierWeight(
            classification.tier,
            input.deltaMgdlPer5,
            input.shortAvgDeltaMgdlPer5,
            classification.plateauSustain,
        )
        val absorptionOffset = absorptionOffsetMgdl(classification.tier, classification.plateauSustain)
        val smbBaseU = smbBaseU(input.tdd24hU)
        val projectionLead = max(0.0, input.bestTerminalMgdl - input.bgMgdl)
        var projectionFactor = (0.35 + projectionLead / 55.0).coerceIn(0.65, 1.55)
        if (classification.plateauSustain) {
            projectionFactor = max(projectionFactor, plateauProjectionFactorFloor())
        }
        val riseFactor = riseUrgencyFactor(input.deltaMgdlPer5, input.shortAvgDeltaMgdlPer5)

        var smbFloorU = smbBaseU * tierWeight * projectionFactor * riseFactor
        smbFloorU *= absorptionDoseFactor(classification.tier, classification.plateauSustain)
        if (classification.plateauSustain) {
            smbFloorU *= plateauDwellUrgencyFactor(input.dwellAboveHighBgMinutes)
            smbFloorU = max(
                smbFloorU,
                smbBaseU * plateauMinFloorFraction(
                    devAboveTargetMgdl = classification.devAboveTargetMgdl,
                    deepDevMgdl = classification.deepDevMgdl,
                ),
            )
        }
        if (input.aggressive && !classification.plateauSustain) {
            smbFloorU *= 1.15
        } else if (input.aggressive && classification.plateauSustain) {
            smbFloorU *= 1.08
        }
        if (input.mealCobG >= 15.0) {
            smbFloorU *= 0.88
        }
        val iobHeadroom = max(0.0, input.maxIobU - input.iobU)
        smbFloorU = minOf(smbFloorU, input.maxSmbEffectiveU.coerceAtLeast(0.0), iobHeadroom)

        val v3Before = input.v3SmbU.coerceAtLeast(0.0)
        val v3After = max(v3Before, smbFloorU)
        val suppressTraj = v3Before < smbFloorU * 0.5 && classification.tier.isReleaseEligible

        val reason = buildString {
            append("HTR[${classification.tier.name}] ")
            append("dev=+${classification.devAboveTargetMgdl.toInt()} ")
            append("proj=+${classification.projectedDevMgdl.toInt()} ")
            append("gap=${classification.terminalGapMgdl.toInt()} ")
            append("floor=${"%.2f".format(smbFloorU)}U ")
            append("v3 ${"%.2f".format(v3Before)}→${"%.2f".format(v3After)}U")
            if (absorptionOffset > 0.0) append(" absOff=${absorptionOffset.toInt()}")
            if (classification.plateauSustain) append(" plateauSustain")
            if (hypoMinPredIgnored) append(" minPredIgnored")
            if (suppressTraj) append(" suppressTrajBridge")
        }

        return HyperTrajectoryReleaseResult(
            active = smbFloorU > v3Before + 0.02,
            tier = classification.tier,
            severityWeight = tierWeight,
            smbFloorU = smbFloorU,
            v3SmbBeforeU = v3Before,
            v3SmbAfterU = v3After,
            absorptionOffsetMgdl = absorptionOffset,
            suppressTrajBasalShift = suppressTraj,
            hypoMinPredIgnored = hypoMinPredIgnored,
            reason = reason.trim(),
        )
    }

    private fun inactive(
        classification: HyperSeverityClassifier.Output,
        v3SmbU: Double,
        hypoMinPredIgnored: Boolean,
        offReason: String = "tier",
    ): HyperTrajectoryReleaseResult =
        HyperTrajectoryReleaseResult(
            active = false,
            tier = classification.tier,
            severityWeight = 0.0,
            smbFloorU = 0.0,
            v3SmbBeforeU = v3SmbU,
            v3SmbAfterU = v3SmbU,
            absorptionOffsetMgdl = 0.0,
            suppressTrajBasalShift = false,
            hypoMinPredIgnored = hypoMinPredIgnored,
            reason = "HTR off ($offReason) tier=${classification.tier.name}",
        )

    internal fun absorptionDoseFactor(tier: HyperSeverityTier, plateauSustain: Boolean = false): Double =
        when {
            plateauSustain -> 1.0
            tier == HyperSeverityTier.DEEP -> 0.88
            tier == HyperSeverityTier.ESTABLISHED -> 0.94
            else -> 1.0
        }

    internal fun plateauProjectionFactorFloor(): Double = 0.90

    internal fun plateauMinFloorFraction(devAboveTargetMgdl: Double, deepDevMgdl: Double): Double =
        if (devAboveTargetMgdl >= deepDevMgdl) 0.72 else 0.65

    internal fun plateauDwellUrgencyFactor(dwellAboveHighBgMinutes: Int): Double =
        when {
            dwellAboveHighBgMinutes >= 90 -> 1.12
            dwellAboveHighBgMinutes >= 45 -> 1.06
            else -> 1.0
        }

    internal fun smbBaseU(tdd24hU: Double): Double {
        if (!tdd24hU.isFinite() || tdd24hU <= 0.0) return 0.85
        return (tdd24hU * 0.015).coerceIn(0.70, 1.40)
    }

    internal fun tierWeight(
        tier: HyperSeverityTier,
        delta: Double,
        shortAvg: Double,
        plateauSustain: Boolean = false,
    ): Double {
        val base = when (tier) {
            HyperSeverityTier.ANTICIPATORY -> 1.0
            HyperSeverityTier.EMERGING -> 1.1
            HyperSeverityTier.ESTABLISHED -> if (plateauSustain) 1.20 else 1.25
            HyperSeverityTier.DEEP -> if (delta < 2.5 && shortAvg < 2.5) 0.75 else 1.0
            HyperSeverityTier.OFF -> 0.0
        }
        return base * riseUrgencyFactor(delta, shortAvg).coerceAtMost(1.35)
    }

    internal fun riseUrgencyFactor(delta: Double, shortAvg: Double): Double =
        when {
            delta >= 4.5 || shortAvg >= 4.5 -> 1.25
            delta >= 1.8 || shortAvg >= 1.5 -> 1.1
            else -> 1.0
        }

    internal fun absorptionOffsetMgdl(tier: HyperSeverityTier, plateauSustain: Boolean = false): Double =
        when {
            plateauSustain -> 8.0
            tier == HyperSeverityTier.ESTABLISHED -> 15.0
            tier == HyperSeverityTier.DEEP -> 25.0
            else -> 0.0
        }
}
