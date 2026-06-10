package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.annotation.StringRes
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import kotlin.math.abs

internal data class AimiControlCenterSnapshot(
    val families: List<AimiBehaviorFamilySnapshot>,
    val contextSection: AimiControlSectionSnapshot,
    val sourceSection: AimiControlSectionSnapshot,
)

internal data class AimiBehaviorFamilySnapshot(
    @StringRes val titleResId: Int,
    @StringRes val questionResId: Int,
    @StringRes val leftAnchorResId: Int,
    @StringRes val rightAnchorResId: Int,
    @StringRes val levelLabelResId: Int,
    val normalizedScore: Float,
    val confidence: Float,
    val rawPreferenceCount: Int,
    val status: AimiProjectionStatus,
    val details: List<AimiControlDetail>,
)

internal data class AimiControlSectionSnapshot(
    @StringRes val titleResId: Int,
    @StringRes val summaryResId: Int,
    val details: List<AimiControlDetail>,
)

internal data class AimiControlDetail(
    @StringRes val titleResId: Int,
    val valueText: String? = null,
    @StringRes val valueResId: Int? = null,
)

internal enum class AimiProjectionStatus(@StringRes val labelResId: Int) {
    EquivalentCurrent(R.string.aimi_control_center_equivalent_current),
    ExpertPersonalized(R.string.aimi_control_center_expert_personalized),
}

internal fun buildAimiControlCenterSnapshot(preferences: Preferences): AimiControlCenterSnapshot =
    AimiControlCenterSnapshot(
        families = listOf(
            buildProtectionFamily(preferences),
            buildMealCaptureFamily(preferences),
            buildStabilityFamily(preferences),
            buildPhysioFamily(preferences),
            buildAutonomyFamily(preferences),
        ),
        contextSection = buildContextSection(preferences),
        sourceSection = buildSourceSection(preferences),
    )

private fun buildProtectionFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    val scores = listOf(
        normalize(preferences.get(DoubleKey.OApsAIMIMaxSMB), DoubleKey.OApsAIMIMaxSMB),
        normalize(preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), DoubleKey.OApsAIMIHighBGMaxSMB),
        normalize(preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor), DoubleKey.OApsAIMIPriorityMaxIobFactor),
        normalize(preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU), DoubleKey.OApsAIMIPriorityMaxIobExtraU),
        normalize(preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor), DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor),
        normalize(preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold), DoubleKey.OApsAIMIRedCarpetRestoreThreshold),
    )
    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        titleResId = R.string.aimi_control_center_protection_title,
        questionResId = R.string.aimi_control_center_protection_question,
        leftAnchorResId = R.string.aimi_control_center_protection_left,
        rightAnchorResId = R.string.aimi_control_center_protection_right,
        levelLabelResId = protectionLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        rawPreferenceCount = 6,
        status = projection.status,
        details = listOf(
            detail(R.string.openapsaimi_maxsmb_title, preferences.get(DoubleKey.OApsAIMIMaxSMB), "U"),
            detail(R.string.openapsaimi_highBG_maxsmb_title, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), "U"),
            detail(R.string.oaps_aimi_priority_max_iob_factor_title, preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor), "x"),
            detail(R.string.oaps_aimi_priority_max_iob_extra_title, preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU), "U"),
            detail(R.string.oaps_aimi_pkpd_relief_factor_title, preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor), null),
            detail(R.string.oaps_aimi_redcarpet_restore_title, preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold), null),
        ),
    )
}

private fun buildMealCaptureFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    val autoDrive = preferences.get(BooleanKey.OApsAIMIautoDrive)
    val autoDriveActive = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
    val hyperTrajectory = autoDriveActive && preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease)
    val recursiveShadow = autoDriveActive && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow)
    val recursiveAuthority = recursiveShadow && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority)

    val scores = mutableListOf<Float>()
    scores += when {
        autoDriveActive -> 0.76f
        autoDrive       -> 0.44f
        else            -> 0.12f
    }
    if (autoDriveActive) {
        scores += boolScore(hyperTrajectory, whenFalse = 0.34f, whenTrue = 0.82f)
        scores += boolScore(recursiveShadow, whenFalse = 0.38f, whenTrue = 0.70f)
        scores += boolScore(recursiveAuthority, whenFalse = 0.50f, whenTrue = 0.95f)
        scores += normalize(preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep), DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep)
        scores += normalize(preferences.get(DoubleKey.OApsAIMIautodrivePrebolus), DoubleKey.OApsAIMIautodrivePrebolus)
        scores += normalize(preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus), DoubleKey.OApsAIMIautodrivesmallPrebolus)
        scores += inverseNormalize(preferences.get(DoubleKey.OApsAIMIHyperEstablishedDevMgdl), DoubleKey.OApsAIMIHyperEstablishedDevMgdl)
        scores += inverseNormalize(preferences.get(DoubleKey.OApsAIMIHyperDeepDevMgdl), DoubleKey.OApsAIMIHyperDeepDevMgdl)
    }
    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        titleResId = R.string.aimi_control_center_meal_title,
        questionResId = R.string.aimi_control_center_meal_question,
        leftAnchorResId = R.string.aimi_control_center_meal_left,
        rightAnchorResId = R.string.aimi_control_center_meal_right,
        levelLabelResId = mealLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        rawPreferenceCount = 8,
        status = projection.status,
        details = listOf(
            boolDetail(R.string.oaps_aimi_enableMlautoDrive_title, autoDrive),
            boolDetail(R.string.oaps_aimi_enableMlautoDriveActive_title, autoDriveActive),
            boolDetail(BooleanKey.OApsAIMIHyperTrajectoryRelease, isEnabled = hyperTrajectory),
            boolDetail(BooleanKey.OApsAIMIRecursiveBeliefShadow, isEnabled = recursiveShadow),
            boolDetail(BooleanKey.OApsAIMIRecursiveBeliefAuthority, isEnabled = recursiveAuthority),
            detail(R.string.aimi_mpc_u_per_kg_title, preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep), "U/kg/5m"),
            detail(R.string.prebolus_autodrive_mode_title, preferences.get(DoubleKey.OApsAIMIautodrivePrebolus), "U"),
            detail(R.string.prebolussmall_autodrive_mode_title, preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus), "U"),
        ),
    )
}

private fun buildStabilityFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    val scores = listOf(
        normalize(preferences.get(DoubleKey.OApsAIMISmbTailDamping), DoubleKey.OApsAIMISmbTailDamping),
        normalize(preferences.get(DoubleKey.OApsAIMISmbExerciseDamping), DoubleKey.OApsAIMISmbExerciseDamping),
        normalize(preferences.get(DoubleKey.OApsAIMISmbLateFatDamping), DoubleKey.OApsAIMISmbLateFatDamping),
        boolScore(preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled), whenFalse = 0.35f, whenTrue = 0.66f),
        boolScore(preferences.get(BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled), whenFalse = 0.32f, whenTrue = 0.72f),
        normalize(preferences.get(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction), DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction),
    )
    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        titleResId = R.string.aimi_control_center_stability_title,
        questionResId = R.string.aimi_control_center_stability_question,
        leftAnchorResId = R.string.aimi_control_center_stability_left,
        rightAnchorResId = R.string.aimi_control_center_stability_right,
        levelLabelResId = stabilityLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        rawPreferenceCount = 6,
        status = projection.status,
        details = listOf(
            detail(R.string.oaps_aimi_smb_tail_damping_title, preferences.get(DoubleKey.OApsAIMISmbTailDamping), null),
            detail(R.string.oaps_aimi_smb_exercise_damping_title, preferences.get(DoubleKey.OApsAIMISmbExerciseDamping), null),
            detail(R.string.oaps_aimi_smb_late_fat_damping_title, preferences.get(DoubleKey.OApsAIMISmbLateFatDamping), null),
            boolDetail(R.string.oaps_aimi_adaptive_basal_title, preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)),
            boolDetail(BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled),
            detail(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, preferences.get(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction), null),
        ),
    )
}

private fun buildPhysioFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    val sleepEnabled = preferences.get(BooleanKey.AimiPhysioSleepDataEnable)
    val hrvEnabled = preferences.get(BooleanKey.AimiPhysioHRVDataEnable)
    val assistantEnabled = preferences.get(BooleanKey.AimiPhysioAssistantEnable)
    val ouraConfigured = preferences.get(AimiStringKey.OuraPersonalAccessToken).isNotBlank()
    val sourceMode = preferences.get(AimiStringKey.ActivitySourceMode)
    val sourceEnabled = sourceMode != UnifiedActivityProviderMTR.MODE_DISABLED

    val scores = listOf(
        boolScore(assistantEnabled, whenFalse = 0.38f, whenTrue = 0.82f),
        boolScore(sleepEnabled, whenFalse = 0.22f, whenTrue = 0.72f),
        boolScore(hrvEnabled, whenFalse = 0.22f, whenTrue = 0.78f),
        boolScore(sourceEnabled, whenFalse = 0.20f, whenTrue = 0.70f),
        boolScore(ouraConfigured, whenFalse = 0.48f, whenTrue = 0.92f),
    )
    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        titleResId = R.string.aimi_control_center_physio_title,
        questionResId = R.string.aimi_control_center_physio_question,
        leftAnchorResId = R.string.aimi_control_center_physio_left,
        rightAnchorResId = R.string.aimi_control_center_physio_right,
        levelLabelResId = physioLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        rawPreferenceCount = 5,
        status = projection.status,
        details = listOf(
            boolDetail(R.string.aimi_physio_enable_title, assistantEnabled),
            boolDetail(R.string.aimi_physio_sleep_enable_title, sleepEnabled),
            boolDetail(R.string.aimi_physio_hrv_enable_title, hrvEnabled),
            AimiControlDetail(
                titleResId = AimiStringKey.ActivitySourceMode.titleResId,
                valueText = sourceMode,
                valueResId = AimiStringKey.ActivitySourceMode.entries[sourceMode],
            ),
            AimiControlDetail(
                titleResId = AimiStringKey.OuraPersonalAccessToken.titleResId,
                valueResId = if (ouraConfigured) {
                    R.string.aimi_control_center_configured
                } else {
                    R.string.aimi_control_center_not_configured
                },
            ),
        ),
    )
}

private fun buildAutonomyFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    val autoDrive = preferences.get(BooleanKey.OApsAIMIautoDrive)
    val autoDriveActive = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
    val authoritative = autoDriveActive && preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative)
    val recursiveAuthority = autoDriveActive && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
    val mlTraining = preferences.get(BooleanKey.OApsAIMIMLtraining)
    val auditorEnabled = preferences.get(BooleanKey.AimiAuditorEnabled)

    val levelLabelResId = when {
        !autoDrive && !autoDriveActive -> R.string.aimi_control_center_autonomy_observation
        autoDrive && !autoDriveActive  -> R.string.aimi_control_center_autonomy_recommendations
        authoritative || recursiveAuthority -> R.string.aimi_control_center_autonomy_controlled
        else -> R.string.aimi_control_center_autonomy_assisted
    }
    val score = when (levelLabelResId) {
        R.string.aimi_control_center_autonomy_observation -> 0.12f
        R.string.aimi_control_center_autonomy_recommendations -> 0.42f
        R.string.aimi_control_center_autonomy_assisted -> 0.72f
        else -> 0.95f
    }

    return AimiBehaviorFamilySnapshot(
        titleResId = R.string.aimi_control_center_autonomy_title,
        questionResId = R.string.aimi_control_center_autonomy_question,
        leftAnchorResId = R.string.aimi_control_center_autonomy_left,
        rightAnchorResId = R.string.aimi_control_center_autonomy_right,
        levelLabelResId = levelLabelResId,
        normalizedScore = score,
        confidence = 1.0f,
        rawPreferenceCount = 6,
        status = AimiProjectionStatus.EquivalentCurrent,
        details = listOf(
            boolDetail(R.string.oaps_aimi_enableMlautoDrive_title, autoDrive),
            boolDetail(R.string.oaps_aimi_enableMlautoDriveActive_title, autoDriveActive),
            boolDetail(BooleanKey.OApsAIMIautoDriveAuthoritative, isEnabled = authoritative),
            boolDetail(BooleanKey.OApsAIMIRecursiveBeliefAuthority, isEnabled = recursiveAuthority),
            boolDetail(R.string.oaps_aimi_enableMlTraining_title, mlTraining),
            boolDetail(R.string.aimi_auditor_enabled_title, auditorEnabled),
        ),
    )
}

private fun buildContextSection(preferences: Preferences): AimiControlSectionSnapshot =
    AimiControlSectionSnapshot(
        titleResId = R.string.aimi_control_center_context_title,
        summaryResId = R.string.aimi_control_center_context_summary,
        details = listOf(
            detail(R.string.oaps_aimi_weight_title, preferences.get(DoubleKey.OApsAIMIweight), "kg"),
            detail(R.string.oaps_aimi_cho_title, preferences.get(DoubleKey.OApsAIMICHO), "g"),
            detail(R.string.oaps_aimi_tdd7_title, preferences.get(DoubleKey.OApsAIMITDD7), "U"),
            AimiControlDetail(
                titleResId = R.string.OApsAIMI_Enable_pregnancy,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIpregnancy)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.OApsAIMI_Enable_honeymoon,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIhoneymoon)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_cycle_module_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIwcycle)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.oaps_aimi_thyroid_enabled_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIThyroidEnabled)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.endo_enable_title,
                valueResId = if (preferences.get(BooleanKey.AimiEndometriosisEnable)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.oaps_aimi_ngr_enabled_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMINightGrowthEnabled)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        ),
    )

private fun buildSourceSection(preferences: Preferences): AimiControlSectionSnapshot {
    val sourceMode = preferences.get(AimiStringKey.ActivitySourceMode)
    val sourceResId = AimiStringKey.ActivitySourceMode.entries[sourceMode]
    val ouraConfigured = preferences.get(AimiStringKey.OuraPersonalAccessToken).isNotBlank()
    return AimiControlSectionSnapshot(
        titleResId = R.string.aimi_control_center_sources_title,
        summaryResId = R.string.aimi_control_center_sources_summary,
        details = listOf(
            boolDetail(R.string.aimi_physio_enable_title, preferences.get(BooleanKey.AimiPhysioAssistantEnable)),
            AimiControlDetail(
                titleResId = AimiStringKey.ActivitySourceMode.titleResId,
                valueText = sourceMode,
                valueResId = sourceResId,
            ),
            boolDetail(R.string.aimi_physio_sleep_enable_title, preferences.get(BooleanKey.AimiPhysioSleepDataEnable)),
            boolDetail(R.string.aimi_physio_hrv_enable_title, preferences.get(BooleanKey.AimiPhysioHRVDataEnable)),
            AimiControlDetail(
                titleResId = AimiStringKey.OuraPersonalAccessToken.titleResId,
                valueResId = if (ouraConfigured) R.string.aimi_control_center_configured else R.string.aimi_control_center_not_configured,
            ),
        ),
    )
}

private data class AimiScoreProjection(
    val score: Float,
    val confidence: Float,
    val status: AimiProjectionStatus,
)

private fun project(scores: List<Float>): AimiScoreProjection {
    val safeScores = scores.ifEmpty { listOf(0.5f) }
    val score = safeScores.average().toFloat().coerceIn(0f, 1f)
    val meanDistance = safeScores.map { abs(it - score) }.average().toFloat()
    val confidence = (1f - meanDistance * 1.75f).coerceIn(0.42f, 1f)
    val spread = (safeScores.maxOrNull() ?: score) - (safeScores.minOrNull() ?: score)
    val status = if (confidence < 0.58f && spread > 0.45f) {
        AimiProjectionStatus.ExpertPersonalized
    } else {
        AimiProjectionStatus.EquivalentCurrent
    }
    return AimiScoreProjection(score = score, confidence = confidence, status = status)
}

private fun normalize(value: Double, key: DoublePreferenceKey): Float =
    normalize(value = value, min = key.min, max = key.max)

private fun normalize(value: Double, min: Double, max: Double): Float {
    if (max <= min) return 0.5f
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

private fun inverseNormalize(value: Double, key: DoublePreferenceKey): Float =
    (1f - normalize(value = value, key = key)).coerceIn(0f, 1f)

private fun boolScore(enabled: Boolean, whenFalse: Float, whenTrue: Float): Float =
    if (enabled) whenTrue else whenFalse

private fun detail(key: DoublePreferenceKey, value: Double, unit: String?): AimiControlDetail =
    AimiControlDetail(
        titleResId = key.titleResId,
        valueText = formatValue(value = value, unit = unit),
    )

private fun detail(
    @StringRes titleResId: Int,
    value: Double,
    unit: String?,
): AimiControlDetail =
    AimiControlDetail(
        titleResId = titleResId,
        valueText = formatValue(value = value, unit = unit),
    )

private fun boolDetail(
    key: BooleanPreferenceKey,
    isEnabled: Boolean? = null,
): AimiControlDetail =
    AimiControlDetail(
        titleResId = key.titleResId,
        valueResId = if (isEnabled ?: false) CoreUiR.string.yes else CoreUiR.string.no,
    )

private fun boolDetail(
    @StringRes titleResId: Int,
    enabled: Boolean,
): AimiControlDetail =
    AimiControlDetail(
        titleResId = titleResId,
        valueResId = if (enabled) CoreUiR.string.yes else CoreUiR.string.no,
    )

private fun formatValue(value: Double, unit: String?): String {
    val formatted = when {
        abs(value - value.toInt().toDouble()) < 0.005 -> value.toInt().toString()
        value >= 10.0 -> String.format("%.1f", value)
        else -> String.format("%.2f", value)
    }
    return if (unit.isNullOrBlank()) formatted else "$formatted $unit"
}

@StringRes
internal fun protectionLevelLabel(score: Float): Int =
    when {
        score < 0.18f -> R.string.aimi_control_center_protection_level_very_protective
        score < 0.36f -> R.string.aimi_control_center_protection_level_protective
        score < 0.60f -> R.string.aimi_control_center_protection_level_balanced
        score < 0.80f -> R.string.aimi_control_center_protection_level_corrective
        else -> R.string.aimi_control_center_protection_level_very_corrective
    }

@StringRes
internal fun mealLevelLabel(score: Float): Int =
    when {
        score < 0.18f -> R.string.aimi_control_center_meal_level_prudent
        score < 0.36f -> R.string.aimi_control_center_meal_level_standard
        score < 0.60f -> R.string.aimi_control_center_meal_level_active
        score < 0.80f -> R.string.aimi_control_center_meal_level_assertive
        else -> R.string.aimi_control_center_meal_level_very_assertive
    }

@StringRes
internal fun stabilityLevelLabel(score: Float): Int =
    when {
        score < 0.18f -> R.string.aimi_control_center_stability_level_very_smooth
        score < 0.36f -> R.string.aimi_control_center_stability_level_smooth
        score < 0.60f -> R.string.aimi_control_center_stability_level_balanced
        score < 0.80f -> R.string.aimi_control_center_stability_level_responsive
        else -> R.string.aimi_control_center_stability_level_very_responsive
    }

@StringRes
internal fun physioLevelLabel(score: Float): Int =
    when {
        score < 0.34f -> R.string.aimi_control_center_physio_level_low
        score < 0.68f -> R.string.aimi_control_center_physio_level_moderate
        else -> R.string.aimi_control_center_physio_level_strong
    }
