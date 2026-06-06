package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinLoadGovernor
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MR-7 clauses 5–6: TENSION, RESOLVE + priority rules P0–P4.
 */
object RecursiveBeliefResolver {

    data class Input(
        val ctx: RecursiveBeliefTickContext,
        val scales: List<BeliefScaleNode>,
        val authorityEnabled: Boolean,
    )

    fun resolve(input: Input): RecursiveBeliefSnapshot {
        val trace = mutableListOf<String>()
        trace += "OBSERVE:${input.scales.sumOf { it.leaves.size }}"

        val tensions = computeTensions(input.scales, trace)
        val paradoxes = RecursiveBeliefParadox.detect(input.ctx, input.scales, tensions)
        trace += "TENSION:${tensions.maxOfOrNull { it.magnitude } ?: 0.0}"

        val resolution = resolveChannels(input, paradoxes, tensions, trace)
        trace += "RESOLVE:${resolution.releaseAuthority}"
        val loadGovernorExport = resolution.loadGovernorExport

        return RecursiveBeliefSnapshot(
            scales = input.scales,
            tensions = tensions,
            paradoxes = paradoxes,
            resolutions = resolution,
            loadGovernor = loadGovernorExport,
            mr7Trace = trace,
            waveletBands = input.ctx.waveletBands,
        )
    }

    fun computeTensions(scales: List<BeliefScaleNode>): List<ScaleTension> =
        computeTensions(scales, mutableListOf())

    private fun computeTensions(scales: List<BeliefScaleNode>, trace: MutableList<String>): List<ScaleTension> {
        val sorted = scales.sortedBy { it.horizonMinutes }
        val out = mutableListOf<ScaleTension>()
        for (i in 1 until sorted.size) {
            val child = sorted[i - 1]
            val parent = sorted[i]
            val magnitude = abs(child.urgency - parent.urgency) *
                min(child.belief, parent.belief)
            val dominant = RecursiveBeliefParadox.dominantParadox(parent, child)
            out += ScaleTension(parent.horizonMinutes, child.horizonMinutes, magnitude, dominant)
            trace += "BELIEVE:${"%.2f".format(child.belief)}@${child.horizonMinutes}"
        }
        return out
    }

    private fun resolveChannels(
        input: Input,
        paradoxes: List<BeliefParadox>,
        tensions: List<ScaleTension>,
        trace: MutableList<String>,
    ): DoseChannelResolution {
        val ctx = input.ctx
        val byTau = input.scales.associateBy { it.horizonMinutes }
        val u15 = byTau[15]?.urgency ?: 0.0
        val u60 = byTau[60]?.urgency ?: 0.0
        val belief15 = byTau[15]?.belief ?: 0.0

        // P0 — Tier-1 hypo (non-negotiable)
        if (ctx.tier1Hypo) {
            return DoseChannelResolution(
                smbDemandU = 0.0,
                tbrDemandFraction = 0.0,
                waitBias = 1.0,
                dominantScaleMinutes = 15,
                releaseAuthority = ReleaseAuthority.NONE,
                hypoGuardMode = HypoGuardMode.FULL,
                autodriveModeHint = AutodriveModeHint.SKIP,
                mealChannel = MealChannelHint.NORMAL,
                suppressTrajBasalShift = true,
                hypoMinPredIgnored = ctx.hypoMinPredIgnored,
                reasonCodes = listOf("P0"),
            )
        }

        var releaseAuthority = ReleaseAuthority.NONE
        val reasonCodes = mutableListOf<String>()
        val mealHypothesisProb = max(
            ctx.extended.uamMealProb ?: ctx.extended.latentMealProb ?: 0.0,
            (ctx.extended.uamLateFatProb ?: 0.0) * 0.88,
        )
        val nonMealHypothesisProb = max(
            ctx.extended.uamEndogenousProb ?: ctx.extended.latentEndogenousGlucoseDrive ?: 0.0,
            max(
                ctx.extended.uamStressProb ?: 0.0,
                ctx.extended.uamPostHypoProb ?: 0.0,
            ),
        )
        val suppressMealInterpretation =
            ctx.extended.uamSuppressMealInterpretation ||
                (
                    nonMealHypothesisProb >= mealHypothesisProb + 0.08 &&
                        nonMealHypothesisProb >= 0.60
                    )
        val mealWaveBoostAllowed =
            !suppressMealInterpretation &&
                mealHypothesisProb >= nonMealHypothesisProb &&
                mealHypothesisProb >= 0.45

        // P2 — short-scale dominance
        val hyperVsClearance = paradoxes.any { it.id == BeliefParadoxId.HYPER_VS_CLEARANCE && !it.suppressed }
        if (hyperVsClearance && belief15 >= 0.6 && u15 > 0.8) {
            releaseAuthority = ReleaseAuthority.HARD
            reasonCodes += "P2"
        } else if (u60 > 1.0 && u15 > 0.5) {
            releaseAuthority = ReleaseAuthority.SOFT
            reasonCodes += "P2_SOFT"
        }

        // Paradox-driven authority adjustments (§15.3)
        if (paradoxes.any { it.id == BeliefParadoxId.NGR_VS_HYPER }) {
            if (releaseAuthority == ReleaseAuthority.HARD) {
                releaseAuthority = ReleaseAuthority.SOFT
            } else {
                releaseAuthority = ReleaseAuthority.NONE
            }
            reasonCodes += "NGR_DAMP"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.WCYCLE_VS_STABLE }) {
            releaseAuthority = ReleaseAuthority.NONE
            reasonCodes += "WCYCLE_STABLE"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.THYROID_VS_AGGRESS }) {
            if (releaseAuthority == ReleaseAuthority.HARD) releaseAuthority = ReleaseAuthority.SOFT
            reasonCodes += "THYROID_GUARD"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.ENDOG_VS_CORRECTION }) {
            if (ctx.behavioralRisk?.capsHtrRelease() == true || ctx.physiologicalPatterns?.suppressHyperRelease == true) {
                releaseAuthority = ReleaseAuthority.NONE
                reasonCodes += "ENDOG_CAP"
            }
        }
        if (ctx.behavioralRisk?.capsHtrRelease() == true || ctx.physiologicalPatterns?.suppressHyperRelease == true) {
            releaseAuthority = ReleaseAuthority.NONE
            reasonCodes += "PHYSIO_RISK_CAP"
        }
        if (suppressMealInterpretation && releaseAuthority != ReleaseAuthority.NONE) {
            releaseAuthority = if (releaseAuthority == ReleaseAuthority.HARD) {
                ReleaseAuthority.SOFT
            } else {
                ReleaseAuthority.NONE
            }
            reasonCodes += "UAM_ALT_${ctx.extended.uamHypothesisDominant ?: "NON_MEAL"}"
        }

        // P1 — hypo credibility
        val hypoGuardMode = when {
            ctx.hypoMinPredIgnored -> HypoGuardMode.IGNORE_MINPRED
            paradoxes.any { it.id == BeliefParadoxId.FLOOR_VS_REALITY } -> HypoGuardMode.PARTIAL
            else -> HypoGuardMode.FULL
        }
        if (ctx.hypoMinPredIgnored) reasonCodes += "P1"

        // SMB demand: native RBT calculator when replacing HTR, else legacy HTR floor
        var smbDemandU = when {
            ctx.replaceHtrRelease && releaseAuthority != ReleaseAuthority.NONE ->
                RecursiveBeliefReleaseCalculator.smbFloor(ctx, releaseAuthority, u15, u60)
            else -> ctx.htrResult?.smbFloorU ?: 0.0
        }
        if (smbDemandU <= 0.0 && releaseAuthority != ReleaseAuthority.NONE) {
            smbDemandU = smbFromUrgency(ctx, u15, u60)
        }
        val v3 = ctx.v3SmbU ?: 0.0
        if (releaseAuthority != ReleaseAuthority.NONE) {
            val patternCap = ctx.physiologicalPatterns?.smbCapU
            val v3Lift = listOfNotNull(
                ctx.behavioralRisk?.takeIf { it.capsHtrRelease() }?.smbFloorCapU,
                patternCap,
            ).minOrNull()?.let { min(v3, it) } ?: v3
            smbDemandU = max(smbDemandU, v3Lift)
        }
        if (ctx.mealAbsorption?.phase == MealAbsorptionPhase.SECOND_WAVE && ctx.deltaMgdlPer5 > 0 &&
            mealWaveBoostAllowed &&
            ctx.behavioralRisk?.capsHtrRelease() != true
        ) {
            smbDemandU = max(smbDemandU, 1.5)
            reasonCodes += "SECOND_WAVE"
        }
        if (ctx.mealAbsorption?.phase == MealAbsorptionPhase.FIRST_WAVE && ctx.deltaMgdlPer5 >= 2.5 &&
            mealWaveBoostAllowed &&
            ctx.behavioralRisk?.capsHtrRelease() != true
        ) {
            smbDemandU = max(smbDemandU, 1.2)
            reasonCodes += "FIRST_WAVE"
        }
        if (paradoxes.any { it.id == BeliefParadoxId.AUDITOR_VS_RELEASE }) {
            smbDemandU = min(smbDemandU, 0.5)
            reasonCodes += "SENTINEL_CAP"
        }
        if (ctx.stackingStance?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) {
            smbDemandU = min(smbDemandU, ctx.stackingStance.smbAbsoluteCapU)
            reasonCodes += "STACK_CAP"
        }
        if (ctx.physiologicalPatterns?.suppressMealInterpretation == true &&
            ctx.mealAbsorption?.mealDeliveryPriority == true
        ) {
            reasonCodes += "PATTERN_MEAL_SUPPRESS"
        }
        if (suppressMealInterpretation && ctx.mealAbsorption?.mealDeliveryPriority == true) {
            reasonCodes += "UAM_MEAL_SUPPRESS"
        }
        ctx.behavioralRisk?.takeIf { it.capsHtrRelease() }?.let { risk ->
            smbDemandU = min(smbDemandU, risk.smbFloorCapU)
            reasonCodes += "PHYSIO_SMB_CAP"
        }
        ctx.physiologicalPatterns?.smbCapU?.let { cap ->
            smbDemandU = min(smbDemandU, cap)
            reasonCodes += "PATTERN_SMB_CAP"
        }

        val smbBeforeLoadGovernor = smbDemandU
        val loadGovernorEval = InsulinLoadGovernor.evaluate(
            InsulinLoadGovernor.Input(
                iobU = ctx.iobU,
                tdd24hU = ctx.tdd24hU,
                patientWeightKg = ctx.patientWeightKg,
                deltaMgdlPer5 = ctx.deltaMgdlPer5,
                shortAvgDeltaMgdlPer5 = ctx.shortAvgDeltaMgdlPer5,
                deltaPrevMgdlPer5 = ctx.deltaPrevMgdlPer5,
                bgDerivShort = ctx.bgDerivShort,
                bgMgdl = ctx.bgMgdl,
                targetBgMgdl = ctx.targetBgMgdl,
                bestTerminalMgdl = ctx.scenario.scenarioBest.terminalMgdl,
                minPredictedBgMgdl = ctx.minPredictedBgMgdl,
                eventualBgMgdl = ctx.eventualBgMgdl,
                trajectoryEnergy = ctx.trajectoryAnalysis?.metrics?.energyBalance,
                trajectoryCoherence = ctx.trajectoryAnalysis?.metrics?.coherence,
                insulinActivityStageOrdinal = ctx.insulinActivityStageOrdinal,
                insulinActivityNow = ctx.insulinActivityNow,
                mealAbsorptionPhase = ctx.mealAbsorption?.phase ?: MealAbsorptionPhase.NONE,
                mealDeliveryPriority =
                    ctx.mealAbsorption?.mealDeliveryPriority == true &&
                        !suppressMealInterpretation,
                lastMultiplierG = ctx.lastLoadGovernorMultiplierG,
            ),
        )
        val loadGovernorApplied = input.authorityEnabled && loadGovernorEval.multiplierG < 0.999
        if (loadGovernorApplied) {
            smbDemandU *= loadGovernorEval.multiplierG
            if (loadGovernorEval.smbTickCapU.isFinite()) {
                smbDemandU = min(smbDemandU, loadGovernorEval.smbTickCapU)
            }
            reasonCodes += "LOAD_GOV_${loadGovernorEval.tier.name}"
        }
        trace += "LOADGOV:${"%.2f".format(loadGovernorEval.multiplierG)}"
        val loadGovernorExport = LoadGovernorExport(
            tier = loadGovernorEval.tier.name,
            multiplierG = loadGovernorEval.multiplierG,
            rawMultiplierG = loadGovernorEval.rawMultiplierG,
            smbTickCapU = loadGovernorEval.smbTickCapU,
            physBudgetU = loadGovernorEval.physBudgetU,
            stackScore = loadGovernorEval.stackScore,
            riseScore = loadGovernorEval.riseScore,
            deltaDecelScore = loadGovernorEval.deltaDecelScore,
            smbDemandBeforeU = smbBeforeLoadGovernor,
            smbDemandAfterU = if (loadGovernorApplied) smbDemandU else smbBeforeLoadGovernor * loadGovernorEval.multiplierG,
            applied = loadGovernorApplied,
            reasonCodes = loadGovernorEval.reasonCodes,
            summary = loadGovernorEval.summary,
        )

        val iobHeadroom = max(0.0, ctx.maxIobU - ctx.iobU)
        smbDemandU = minOf(smbDemandU, ctx.maxSmbEffectiveU.coerceAtLeast(0.0), iobHeadroom)

        // P3 — macro prudence TBR
        var tbrFraction = 1.0
        val u180 = byTau[180]?.urgency ?: 0.0
        if (u180 < 0.0 && ctx.iobU > ctx.tdd24hU * 0.15) {
            tbrFraction = 0.85
            reasonCodes += "P3"
        }
        if (loadGovernorApplied &&
            (
                loadGovernorEval.tier == InsulinLoadGovernor.Tier.SURVEILLANCE ||
                    loadGovernorEval.tier == InsulinLoadGovernor.Tier.WAIT
                )
        ) {
            tbrFraction = max(tbrFraction, 1.08)
            reasonCodes += "LOAD_GOV_TBR"
        }

        // Channel interference — Traj-Bridge vs HTR (§6.4 discrete optimizer)
        var suppressTraj = ctx.htrResult?.suppressTrajBasalShift == true
        if (paradoxes.any { it.id == BeliefParadoxId.TRAJ_TBR_VS_HTR_SMB }) {
            val tensionSum = tensions.sumOf { it.magnitude }
            val optimized = ChannelInterferenceOptimizer.optimize(
                bgMgdl = ctx.bgMgdl,
                targetBgMgdl = ctx.targetBgMgdl,
                smbDemandU = smbDemandU,
                tbrDemandFraction = tbrFraction,
                tensionSum = tensionSum,
                maxSmbU = ctx.maxSmbEffectiveU.coerceAtLeast(0.0),
            )
            smbDemandU = optimized.smbU
            tbrFraction = optimized.tbrFraction
            if (releaseAuthority == ReleaseAuthority.HARD) {
                suppressTraj = true
            }
            reasonCodes += "INTERFERENCE"
            trace += "CHANNEL:${"%.2f".format(optimized.cost)}"
        }

        if (ctx.trajBridgePending && releaseAuthority == ReleaseAuthority.NONE) {
            tbrFraction = min(tbrFraction, 0.7)
        }

        val mealChannel = when {
            suppressMealInterpretation -> MealChannelHint.SUPPRESS
            ctx.mealAbsorption?.mealDeliveryPriority == true -> MealChannelHint.PRIORITY
            ctx.behavioralRisk?.suppressMealLikeScenario == true -> MealChannelHint.SUPPRESS
            else -> MealChannelHint.NORMAL
        }

        if (!input.authorityEnabled) {
            releaseAuthority = ReleaseAuthority.NONE
            reasonCodes += "SHADOW"
        }

        if (ctx.isNight || ctx.exerciseLockout) {
            releaseAuthority = ReleaseAuthority.NONE
            smbDemandU = v3
            reasonCodes += "OFF_NIGHT_EXERCISE"
        }

        val dominantScale = when {
            releaseAuthority == ReleaseAuthority.HARD -> 15
            releaseAuthority == ReleaseAuthority.SOFT -> 60
            u180 < 0 -> 180
            else -> 60
        }

        val waitBias = if (ctx.stackingStance?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) {
            0.65
        } else {
            0.15
        }.coerceIn(0.0, 1.0)

        return DoseChannelResolution(
            smbDemandU = smbDemandU,
            smbDemandBeforeLoadGovernorU = smbBeforeLoadGovernor,
            tbrDemandFraction = tbrFraction,
            waitBias = waitBias,
            dominantScaleMinutes = dominantScale,
            releaseAuthority = releaseAuthority,
            hypoGuardMode = hypoGuardMode,
            autodriveModeHint = AutodriveModeHint.V3,
            mealChannel = mealChannel,
            suppressTrajBasalShift = suppressTraj,
            hypoMinPredIgnored = ctx.hypoMinPredIgnored,
            reasonCodes = reasonCodes,
            loadGovernorExport = loadGovernorExport,
        )
    }

    private fun smbFromUrgency(ctx: RecursiveBeliefTickContext, u15: Double, u60: Double): Double {
        val base = max(0.5, ctx.tdd24hU * 0.025)
        val factor = max(u15, u60 * 0.85).coerceIn(0.0, 2.5)
        return base * factor
    }
}
