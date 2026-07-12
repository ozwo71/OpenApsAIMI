package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.orchestration.CausalContextView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.EffectiveKinetics
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinKineticsView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinActivityView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.toView
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior

/**
 * Single façade for insulin kinetics per tick — accounting vs prediction arrays,
 * structural learned params, and effective DIA/peak after governors + causal modulation.
 */
object InsulinKineticsAuthority {

    data class ResolveInput(
        val accountingIobArray: Array<IobTotal>,
        val profile: OapsProfileAimi,
        val effectiveProfile: Profile,
        val pkpdRuntime: PkPdRuntime?,
        val peakGovernor: TapPeakGovernorResult?,
        val causalPosterior: CausalStatePosterior?,
        val physioPeakShiftMinutes: Int = 0,
        val sitePeakShiftMinutes: Double = 0.0,
        val trajectoryPeakNudgeMinutes: Double = 0.0,
        val preferences: Preferences,
        val iobCobCalculator: IobCobCalculator,
        val learningDiagnostics: PkpdLearningDiagnostics,
    )

    fun resolve(input: ResolveInput): InsulinKineticsView {
        val pkpdEnabled = input.preferences.get(BooleanKey.OApsAIMIPkpdEnabled)
        val structural = input.pkpdRuntime?.params
            ?: PkPdParams(
                diaHrs = input.profile.dia,
                peakMin = input.profile.peakTime.toDouble(),
            )
        val causalMod = CausalKineticsModulator.modulate(input.causalPosterior)
        val profileDia = input.profile.dia
        val diaBoundsMin = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
        val diaBoundsMax = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
        val diaGovernor = DiaGovernor.resolve(
            profileDiaHours = profileDia,
            contextualDiaShiftHours = causalMod.diaShiftHours,
            pkpdLearnedDiaHours = if (pkpdEnabled) structural.diaHrs else null,
            pkpdEnabled = pkpdEnabled,
            governorEnabled = input.preferences.get(BooleanKey.OApsAIMIDiaGovernorEnabled),
            diaMinBound = diaBoundsMin,
            diaMaxBound = diaBoundsMax,
            learnedBlendWeight = input.preferences.get(DoubleKey.OApsAIMIDiaGovernorLearnedWeight),
        )
        val peakGovernor = input.peakGovernor ?: TapPeakGovernor.resolve(
            insulinPeakMinutes = input.effectiveProfile.iCfg.peak,
            physioPeakShiftMinutes = input.physioPeakShiftMinutes,
            sitePeakShiftMinutes = input.sitePeakShiftMinutes,
            pkpdLearnedPeak = if (pkpdEnabled) structural.peakMin else null,
            pkpdEnabled = pkpdEnabled,
            governorEnabled = input.preferences.get(BooleanKey.OApsAIMIPeakGovernorEnabled),
            peakMinBound = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
            peakMaxBound = input.preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
            learnedBlendWeight = input.preferences.get(DoubleKey.OApsAIMIPeakGovernorLearnedWeight),
            trajectoryMinutesNudge = input.trajectoryPeakNudgeMinutes + causalMod.peakShiftMinutes,
        )
        val effective = EffectiveKinetics(
            diaHours = diaGovernor.effectiveDiaHours,
            peakMinutes = peakGovernor.effectivePeakMinutes,
            profileDiaHours = profileDia,
            profilePeakMinutes = input.effectiveProfile.iCfg.peak,
        )
        val predictionUsesLearned = input.preferences.get(BooleanKey.OApsAIMIPkpdPredictionKinetics)
        val predictionIobArray = if (predictionUsesLearned) {
            buildPredictionIobArray(
                effectiveProfile = input.effectiveProfile,
                effectiveDiaHours = effective.diaHours,
                effectivePeakMinutes = effective.peakMinutes,
                iobCobCalculator = input.iobCobCalculator,
            ) ?: input.accountingIobArray
        } else {
            input.accountingIobArray
        }
        val runtime = input.pkpdRuntime
        val activityView = if (runtime != null) {
            runtime.activity.toView(runtime.tailFraction * causalMod.tailScale.coerceIn(0.85, 1.25))
        } else {
            InsulinActivityView(
                tailFraction = 0.0,
                stage = ActivityStage.EXHAUSTED.name,
                relativeActivity = 0.0,
            )
        }
        return InsulinKineticsView(
            accountingIobArray = input.accountingIobArray,
            predictionIobArray = predictionIobArray,
            structural = structural,
            effective = effective,
            learning = input.learningDiagnostics,
            activity = activityView,
            peakGovernor = peakGovernor,
            diaGovernor = diaGovernor,
            predictionUsesLearnedKinetics = predictionUsesLearned && predictionIobArray !== input.accountingIobArray,
        )
    }

    fun buildPredictionIobArray(
        effectiveProfile: Profile,
        effectiveDiaHours: Double,
        effectivePeakMinutes: Double,
        iobCobCalculator: IobCobCalculator,
    ): Array<IobTotal>? {
        val diaHrs = effectiveDiaHours.takeIf { it.isFinite() && it in 3.0..12.0 } ?: return null
        val peakMin = effectivePeakMinutes.takeIf { it.isFinite() && it in 20.0..240.0 } ?: return null
        return try {
            val learnedICfg: ICfg = effectiveProfile.iCfg.copy(
                insulinEndTime = (diaHrs * 3_600_000.0).toLong(),
                insulinPeakTime = (peakMin * 60_000.0).toLong(),
            )
            if (learnedICfg.insulinPeakTime * 2 >= learnedICfg.insulinEndTime) return null
            val learnedProfile = object : Profile by effectiveProfile { override val iCfg: ICfg = learnedICfg }
            iobCobCalculator.calculateIobArrayInDia(learnedProfile)
        } catch (_: Exception) {
            null
        }
    }

    fun buildCausalContext(causalPosterior: CausalStatePosterior?): CausalContextView {
        val mod = CausalKineticsModulator.modulate(causalPosterior)
        return CausalContextView(
            learningQuality = causalPosterior?.learningQuality ?: 1.0,
            learningGatePass = causalPosterior?.learningContextClean() ?: true,
            dominant = causalPosterior?.dominant?.name ?: "UNKNOWN",
            dominantConfidence = causalPosterior?.dominantConfidence ?: 0.0,
            causalModulationReason = mod.reason,
        )
    }
}
