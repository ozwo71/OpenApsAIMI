package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.annotation.StringRes
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.R
import kotlin.math.abs

internal enum class AimiAutonomyMode(@StringRes val labelResId: Int) {
    Observation(R.string.aimi_control_center_autonomy_observation),
    Recommendations(R.string.aimi_control_center_autonomy_recommendations),
    AssistedApplication(R.string.aimi_control_center_autonomy_assisted),
    ControlledAuthority(R.string.aimi_control_center_autonomy_controlled),
}

internal data class AimiControlCenterDraft(
    val protectionLevel: Int,
    val mealCaptureLevel: Int,
    val stabilityLevel: Int,
    val physioLevel: Int,
    val autonomyMode: AimiAutonomyMode,
)

internal data class AimiControlCenterPendingChanges(
    val familyPlans: List<AimiFamilyWritebackPlan>,
) {
    val changedFamilyCount: Int get() = familyPlans.size
    val changedSettingsCount: Int get() = familyPlans.sumOf { it.changes.size }
    val hasChanges: Boolean get() = familyPlans.isNotEmpty()

    fun familyPlan(id: AimiBehaviorFamilyId): AimiFamilyWritebackPlan? =
        familyPlans.firstOrNull { it.familyId == id }
}

internal data class AimiFamilyWritebackPlan(
    val familyId: AimiBehaviorFamilyId,
    @StringRes val currentLabelResId: Int,
    @StringRes val targetLabelResId: Int,
    @StringRes val noteResId: Int? = null,
    val changes: List<AimiPreferenceChange>,
)

internal data class AimiPreferenceChange(
    @StringRes val titleResId: Int,
    val before: AimiValueDescriptor,
    val after: AimiValueDescriptor,
    val apply: (Preferences) -> Unit,
)

internal data class AimiValueDescriptor(
    val valueText: String? = null,
    @StringRes val valueResId: Int? = null,
)

internal fun readAimiControlCenterDraft(preferences: Preferences): AimiControlCenterDraft {
    val snapshot = buildAimiControlCenterSnapshot(preferences)
    return AimiControlCenterDraft(
        protectionLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.Protection).normalizedScore),
        mealCaptureLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.MealCapture).normalizedScore),
        stabilityLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.Stability).normalizedScore),
        physioLevel = threeStepIndex(snapshot.family(AimiBehaviorFamilyId.Physio).normalizedScore),
        autonomyMode = readAutonomyMode(preferences),
    )
}

internal fun buildAimiControlCenterPendingChanges(
    preferences: Preferences,
    currentDraft: AimiControlCenterDraft,
    targetDraft: AimiControlCenterDraft,
): AimiControlCenterPendingChanges {
    val plans = mutableListOf<AimiFamilyWritebackPlan>()
    if (currentDraft.protectionLevel != targetDraft.protectionLevel) {
        val plan = buildProtectionPlan(preferences, currentDraft.protectionLevel, targetDraft.protectionLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.mealCaptureLevel != targetDraft.mealCaptureLevel) {
        val plan = buildMealCapturePlan(preferences, currentDraft.mealCaptureLevel, targetDraft.mealCaptureLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.stabilityLevel != targetDraft.stabilityLevel) {
        val plan = buildStabilityPlan(preferences, currentDraft.stabilityLevel, targetDraft.stabilityLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.physioLevel != targetDraft.physioLevel) {
        val plan = buildPhysioPlan(preferences, currentDraft.physioLevel, targetDraft.physioLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.autonomyMode != targetDraft.autonomyMode) {
        val plan = buildAutonomyPlan(preferences, currentDraft.autonomyMode, targetDraft.autonomyMode)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    return AimiControlCenterPendingChanges(familyPlans = plans)
}

internal fun applyAimiControlCenterPendingChanges(
    preferences: Preferences,
    pendingChanges: AimiControlCenterPendingChanges,
) {
    pendingChanges.familyPlans
        .flatMap { it.changes }
        .forEach { it.apply(preferences) }
}

internal fun projectionStatusSummaryResId(status: AimiProjectionStatus): Int =
    when (status) {
        AimiProjectionStatus.CoherentProfile -> R.string.aimi_control_center_status_coherent_summary
        AimiProjectionStatus.MixedLegacy -> R.string.aimi_control_center_status_mixed_summary
        AimiProjectionStatus.ExpertPersonalized -> R.string.aimi_control_center_status_expert_summary
    }

internal fun AimiControlCenterSnapshot.family(id: AimiBehaviorFamilyId): AimiBehaviorFamilySnapshot =
    families.first { it.id == id }

private fun readAutonomyMode(preferences: Preferences): AimiAutonomyMode {
    val autoDrive = preferences.get(BooleanKey.OApsAIMIautoDrive)
    val autoDriveActive = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
    val authoritative = autoDriveActive && preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative)
    val recursiveShadow = autoDriveActive && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow)
    val recursiveAuthority = recursiveShadow && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
    return when {
        !autoDrive && !autoDriveActive -> AimiAutonomyMode.Observation
        autoDrive && !autoDriveActive -> AimiAutonomyMode.Recommendations
        authoritative || recursiveAuthority -> AimiAutonomyMode.ControlledAuthority
        else -> AimiAutonomyMode.AssistedApplication
    }
}

private fun buildProtectionPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel.coerceIn(0, 4)) {
        0 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMIMaxSMB, 0.80, R.string.openapsaimi_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, 1.00, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, 1.05, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, 0.50, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.60, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, 0.60, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        1 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMIMaxSMB, 1.00, R.string.openapsaimi_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, 1.25, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, 1.10, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, 1.00, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.68, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, 0.68, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        2 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMIMaxSMB, 1.30, R.string.openapsaimi_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, 1.60, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, 1.20, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, 2.00, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.75, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, 0.75, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        3 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMIMaxSMB, 1.80, R.string.openapsaimi_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, 2.20, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, 1.35, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, 3.00, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.82, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, 0.82, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        else -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMIMaxSMB, 2.40, R.string.openapsaimi_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, 3.00, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, 1.50, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            doubleChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, 4.00, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.90, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, 0.90, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Protection,
        currentLabelResId = protectionLevelLabelForIndex(currentLevel),
        targetLabelResId = protectionLevelLabelForIndex(targetLevel),
        changes = changes,
    )
}

private fun buildMealCapturePlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val targetAutodriveMaxBasal = directionalMealCaptureCap(
        currentValue = preferences.get(DoubleKey.autodriveMaxBasal),
        currentLevel = currentLevel,
        targetLevel = targetLevel,
        recommendedValue = mealCaptureAutodriveMaxBasalForLevel(targetLevel),
    )
    val targetMealModeMaxBasal = directionalMealCaptureCap(
        currentValue = preferences.get(DoubleKey.meal_modes_MaxBasal),
        currentLevel = currentLevel,
        targetLevel = targetLevel,
        recommendedValue = mealCaptureMealModeMaxBasalForLevel(targetLevel),
    )
    val changes = when (targetLevel.coerceIn(0, 4)) {
        0 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, false),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, false),
            doubleChange(preferences, DoubleKey.autodriveMaxBasal, targetAutodriveMaxBasal, DoubleKey.autodriveMaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.meal_modes_MaxBasal, targetMealModeMaxBasal, DoubleKey.meal_modes_MaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, 0.045, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, 0.50, R.string.prebolus_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, 0.05, R.string.prebolussmall_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, 22.0, DoubleKey.OApsAIMIHyperEstablishedDevMgdl.titleResId, "mg/dL"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, 38.0, DoubleKey.OApsAIMIHyperDeepDevMgdl.titleResId, "mg/dL"),
        )
        1 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, false),
            doubleChange(preferences, DoubleKey.autodriveMaxBasal, targetAutodriveMaxBasal, DoubleKey.autodriveMaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.meal_modes_MaxBasal, targetMealModeMaxBasal, DoubleKey.meal_modes_MaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, 0.060, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, 0.80, R.string.prebolus_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, 0.10, R.string.prebolussmall_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, 18.0, DoubleKey.OApsAIMIHyperEstablishedDevMgdl.titleResId, "mg/dL"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, 32.0, DoubleKey.OApsAIMIHyperDeepDevMgdl.titleResId, "mg/dL"),
        )
        2 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, false),
            doubleChange(preferences, DoubleKey.autodriveMaxBasal, targetAutodriveMaxBasal, DoubleKey.autodriveMaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.meal_modes_MaxBasal, targetMealModeMaxBasal, DoubleKey.meal_modes_MaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, 0.075, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, 1.20, R.string.prebolus_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, 0.20, R.string.prebolussmall_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, 15.0, DoubleKey.OApsAIMIHyperEstablishedDevMgdl.titleResId, "mg/dL"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, 28.0, DoubleKey.OApsAIMIHyperDeepDevMgdl.titleResId, "mg/dL"),
        )
        3 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, true),
            doubleChange(preferences, DoubleKey.autodriveMaxBasal, targetAutodriveMaxBasal, DoubleKey.autodriveMaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.meal_modes_MaxBasal, targetMealModeMaxBasal, DoubleKey.meal_modes_MaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, 0.090, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, 1.80, R.string.prebolus_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, 0.35, R.string.prebolussmall_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, 12.0, DoubleKey.OApsAIMIHyperEstablishedDevMgdl.titleResId, "mg/dL"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, 24.0, DoubleKey.OApsAIMIHyperDeepDevMgdl.titleResId, "mg/dL"),
        )
        else -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, true),
            doubleChange(preferences, DoubleKey.autodriveMaxBasal, targetAutodriveMaxBasal, DoubleKey.autodriveMaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.meal_modes_MaxBasal, targetMealModeMaxBasal, DoubleKey.meal_modes_MaxBasal.titleResId, "U/h"),
            doubleChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, 0.105, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, 2.80, R.string.prebolus_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, 0.60, R.string.prebolussmall_autodrive_mode_title, "U"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, 10.0, DoubleKey.OApsAIMIHyperEstablishedDevMgdl.titleResId, "mg/dL"),
            doubleChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, 20.0, DoubleKey.OApsAIMIHyperDeepDevMgdl.titleResId, "mg/dL"),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.MealCapture,
        currentLabelResId = mealLevelLabelForIndex(currentLevel),
        targetLabelResId = mealLevelLabelForIndex(targetLevel),
        noteResId = R.string.aimi_control_center_meal_apply_note,
        changes = changes,
    )
}

private fun buildStabilityPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel.coerceIn(0, 4)) {
        0 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMISmbTailDamping, 0.20, R.string.oaps_aimi_smb_tail_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, 0.30, R.string.oaps_aimi_smb_exercise_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, 0.40, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, false, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, false),
            doubleChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, 0.02, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction.titleResId, null),
        )
        1 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMISmbTailDamping, 0.35, R.string.oaps_aimi_smb_tail_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, 0.45, R.string.oaps_aimi_smb_exercise_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, 0.55, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, false, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, false),
            doubleChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, 0.04, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction.titleResId, null),
        )
        2 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMISmbTailDamping, 0.50, R.string.oaps_aimi_smb_tail_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, 0.60, R.string.oaps_aimi_smb_exercise_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, 0.70, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, false, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, false),
            doubleChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, 0.06, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction.titleResId, null),
        )
        3 -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMISmbTailDamping, 0.65, R.string.oaps_aimi_smb_tail_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, 0.72, R.string.oaps_aimi_smb_exercise_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, 0.80, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, true),
            doubleChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, 0.08, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction.titleResId, null),
        )
        else -> listOfNotNull(
            doubleChange(preferences, DoubleKey.OApsAIMISmbTailDamping, 0.80, R.string.oaps_aimi_smb_tail_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, 0.85, R.string.oaps_aimi_smb_exercise_damping_title, null),
            doubleChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, 0.90, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, true),
            doubleChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, 0.10, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction.titleResId, null),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Stability,
        currentLabelResId = stabilityLevelLabelForIndex(currentLevel),
        targetLabelResId = stabilityLevelLabelForIndex(targetLevel),
        changes = changes,
    )
}

private fun buildPhysioPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel.coerceIn(0, 2)) {
        0 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, false, R.string.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, false, R.string.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, false, R.string.aimi_physio_hrv_enable_title),
        )
        1 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, true, R.string.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, true, R.string.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, false, R.string.aimi_physio_hrv_enable_title),
        )
        else -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, true, R.string.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, true, R.string.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, true, R.string.aimi_physio_hrv_enable_title),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Physio,
        currentLabelResId = physioLevelLabelForIndex(currentLevel),
        targetLabelResId = physioLevelLabelForIndex(targetLevel),
        noteResId = R.string.aimi_control_center_physio_apply_note,
        changes = changes,
    )
}

private fun directionalMealCaptureCap(
    currentValue: Double,
    currentLevel: Int,
    targetLevel: Int,
    recommendedValue: Double,
): Double =
    if (targetLevel > currentLevel) maxOf(currentValue, recommendedValue) else recommendedValue

private fun mealCaptureAutodriveMaxBasalForLevel(level: Int): Double =
    when (level.coerceIn(0, 4)) {
        0 -> 3.0
        1 -> 4.5
        2 -> 6.0
        3 -> 7.5
        else -> 9.0
    }

private fun mealCaptureMealModeMaxBasalForLevel(level: Int): Double =
    when (level.coerceIn(0, 4)) {
        0 -> 4.0
        1 -> 5.5
        2 -> 7.0
        3 -> 8.5
        else -> 10.0
    }

private fun buildAutonomyPlan(
    preferences: Preferences,
    currentLevel: AimiAutonomyMode,
    targetLevel: AimiAutonomyMode,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel) {
        AimiAutonomyMode.Observation -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDrive, false, R.string.oaps_aimi_enableMlautoDrive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, false, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefShadow, false),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.Recommendations -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDrive, true, R.string.oaps_aimi_enableMlautoDrive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, false, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefShadow, false),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.AssistedApplication -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDrive, true, R.string.oaps_aimi_enableMlautoDrive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefShadow, true),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.ControlledAuthority -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDrive, true, R.string.oaps_aimi_enableMlautoDrive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefShadow, true),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, true),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, true),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Autonomy,
        currentLabelResId = currentLevel.labelResId,
        targetLabelResId = targetLevel.labelResId,
        noteResId = R.string.aimi_control_center_autonomy_apply_note,
        changes = changes,
    )
}

private fun booleanChange(
    preferences: Preferences,
    key: BooleanPreferenceKey,
    targetValue: Boolean,
    @StringRes titleResId: Int = key.titleResId,
): AimiPreferenceChange? {
    val currentValue = preferences.get(key)
    if (currentValue == targetValue) return null
    return AimiPreferenceChange(
        titleResId = titleResId,
        before = AimiValueDescriptor(valueResId = if (currentValue) CoreUiR.string.yes else CoreUiR.string.no),
        after = AimiValueDescriptor(valueResId = if (targetValue) CoreUiR.string.yes else CoreUiR.string.no),
        apply = { prefs -> prefs.put(key, targetValue) },
    )
}

private fun doubleChange(
    preferences: Preferences,
    key: DoublePreferenceKey,
    targetValue: Double,
    @StringRes titleResId: Int,
    unit: String?,
): AimiPreferenceChange? {
    val clampedTarget = targetValue.coerceIn(key.min, key.max)
    val currentValue = preferences.get(key)
    if (abs(currentValue - clampedTarget) < 0.0001) return null
    return AimiPreferenceChange(
        titleResId = titleResId,
        before = AimiValueDescriptor(valueText = formatControlCenterDoubleValue(currentValue, unit)),
        after = AimiValueDescriptor(valueText = formatControlCenterDoubleValue(clampedTarget, unit)),
        apply = { prefs -> prefs.put(key, clampedTarget) },
    )
}
