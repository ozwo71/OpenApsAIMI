package app.aaps.plugins.aps.openAPSAIMI.wcycle

/**
 * Builds [WCycleBelief] from adjuster output + clinical risk context.
 * Hypo-load dampen affects **effective** amps for tree/Harmonia only in Lot A.
 */
object EndocrineAmplitudeGovernor {

    fun from(
        info: WCycleInfo?,
        prefs: WCyclePreferences,
        hypoLoad: Double = 0.0,
        hypoGuardActive: Boolean = false,
        hourOfDay: Int = java.time.LocalTime.now().hour,
    ): WCycleBelief {
        if (info == null || !info.enabled) return WCycleBelief.DISABLED

        val trackingMode = prefs.trackingMode()
        val contraceptive = prefs.contraceptive()
        val thyroid = prefs.thyroid()
        val verneuil = prefs.verneuil()
        val ampContraceptive = WCycleDefaults.amplitudeScale(contraceptive)
        val ampTrackingMode = when (trackingMode) {
            CycleTrackingMode.PERIMENOPAUSE -> 0.8
            CycleTrackingMode.NO_MENSES_LARC -> 0.6
            else -> 1.0
        }
        val ampCombined = ampContraceptive * ampTrackingMode
        val dawnBias =
            if (info.phase == CyclePhase.LUTEAL && hourOfDay in 4..7) 1.10 else 1.0

        val applicationMode = when {
            prefs.shadow() -> EndocrineApplicationMode.SHADOW
            prefs.requireConfirm() -> EndocrineApplicationMode.CONFIRM_PENDING
            info.applied -> EndocrineApplicationMode.APPLIED
            else -> EndocrineApplicationMode.SHADOW
        }

        val intendedBasal = info.baseBasalMultiplier * info.learnedBasalMultiplier
        val intendedSmb = info.baseSmbMultiplier * info.learnedSmbMultiplier
        val intendedIc = if (info.applied) {
            info.icMultiplier
        } else {
            val ic0 = WCycleDefaults.icMultiplier(info.phase)
            (1.0 + (ic0 - 1.0) * ampCombined).coerceIn(prefs.clampMin(), prefs.clampMax())
        }

        val load = hypoLoad.coerceIn(0.0, 1.0)
        // Stronger dampen under HYPO_GUARD: luteal uplift collapses toward 1.0 as hypo load rises.
        val guardFloor = if (hypoGuardActive) 0.15 else 0.0
        val hypoDampen = (1.0 - load * (0.85 + guardFloor)).coerceIn(0.0, 1.0)

        fun dampTowardUnity(amp: Double): Double =
            1.0 + (amp - 1.0) * hypoDampen

        val inflamBudget = WCycleDefaults.verneuilBump(verneuil).first
        val effectiveBasal = dampTowardUnity(intendedBasal)
        val effectiveSmb = dampTowardUnity(intendedSmb)
        val effectiveIc = dampTowardUnity(intendedIc)

        val phaseConfidence = when (info.phase) {
            CyclePhase.LUTEAL -> 0.72
            CyclePhase.OVULATION -> 0.55
            CyclePhase.MENSTRUATION -> 0.40
            CyclePhase.FOLLICULAR -> 0.28
            CyclePhase.UNKNOWN -> 0.10
        }
        val ampSignal = ((effectiveBasal - 1.0).coerceAtLeast(0.0) / 0.35).coerceIn(0.0, 1.0)
        val confidence = (phaseConfidence * 0.65 + ampSignal * 0.35).coerceIn(0.0, 1.0)

        val reasons = buildList {
            add("phase=${info.phase.name}")
            add("day=${info.dayInCycle}")
            add("mode=${applicationMode.name}")
            add("intended_basal=${fmt(intendedBasal)}")
            add("effective_basal=${fmt(effectiveBasal)}")
            add("legacy_basal=${fmt(info.basalMultiplier)}")
            add("hypo_dampen=${fmt(hypoDampen)}")
            if (dawnBias > 1.0) add("dawn_bias=${fmt(dawnBias)}")
            if (inflamBudget > 1.0) add("verneuil_budget=${fmt(inflamBudget)}")
            if (hypoGuardActive) add("hypo_guard")
            add("dose_path=${EndocrineDosePathOwner.LEGACY_DIRECT_SCALE.name}")
        }

        return WCycleBelief(
            enabled = true,
            phase = info.phase,
            dayInCycle = info.dayInCycle,
            trackingMode = trackingMode,
            contraceptive = contraceptive,
            thyroid = thyroid,
            verneuil = verneuil,
            applicationMode = applicationMode,
            ampContraceptive = ampContraceptive,
            ampTrackingMode = ampTrackingMode,
            ampCombined = ampCombined,
            dawnBias = dawnBias,
            intendedBasalAmp = intendedBasal,
            intendedSmbAmp = intendedSmb,
            intendedIcAmp = intendedIc,
            hypoLoad = load,
            hypoLoadDampen = hypoDampen,
            hypoGuardActive = hypoGuardActive,
            inflamSharedBudgetHint = inflamBudget,
            effectiveBasalAmp = effectiveBasal,
            effectiveSmbAmp = effectiveSmb,
            effectiveIcAmp = effectiveIc,
            legacyDoseBasalAmp = info.basalMultiplier,
            legacyDoseSmbAmp = info.smbMultiplier,
            legacyDoseIcAmp = info.icMultiplier,
            dosePathOwner = EndocrineDosePathOwner.LEGACY_DIRECT_SCALE,
            confidence = confidence,
            reasons = reasons,
        )
    }

    private fun fmt(x: Double): String = String.format("%.3f", x)
}
