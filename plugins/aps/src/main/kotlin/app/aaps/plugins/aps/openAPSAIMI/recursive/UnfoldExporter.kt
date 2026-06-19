package app.aaps.plugins.aps.openAPSAIMI.recursive

import org.json.JSONArray
import org.json.JSONObject

internal object UnfoldExporter {

    fun toExport(
        snapshot: RecursiveBeliefSnapshot,
        shadowOnly: Boolean,
        authorityApplied: Boolean,
        waveletBands: WaveletBelief.Bands? = null,
        authorityGate: RecursiveBeliefAuthorityGate.Decision? = null,
    ): RecursiveBeliefExport =
        RecursiveBeliefExport(
            version = 1,
            shadowOnly = shadowOnly,
            authorityApplied = authorityApplied,
            authorityGate = authorityGate?.let {
                AuthorityGateExport(
                    requestedAuthority = it.requestedAuthority.name,
                    maxAllowedAuthority = it.maxAllowedAuthority.name,
                    effectiveAuthority = it.effectiveAuthority.name,
                    readinessScore = it.readinessScore,
                    liftBlend = it.liftBlend,
                    shadowOnly = it.shadowOnly,
                    softLimited = it.softLimited,
                    reasonCodes = it.reasonCodes,
                )
            },
            waveletBands = waveletBands?.let { WaveletExport(it.high, it.mid, it.low) },
            scales = snapshot.scales.map { scale ->
                ScaleExport(
                    tauMin = scale.horizonMinutes,
                    belief = scale.belief,
                    terminalMgdl = scale.terminalMgdl,
                    urgency = scale.urgency,
                    leaves = scale.leaves.map { leaf ->
                        LeafExport(
                            id = leaf.id.name,
                            signal = leaf.signal,
                            weight = leaf.weight,
                            credibility = leaf.credibility,
                            summary = leaf.rawSummary,
                        )
                    },
                )
            },
            tensions = snapshot.tensions.map { t ->
                TensionExport(
                    parentTau = t.parentTauMin,
                    childTau = t.childTauMin,
                    magnitude = t.magnitude,
                    dominant = t.dominantParadoxId?.name,
                )
            },
            paradoxes = snapshot.paradoxes.map { p ->
                ParadoxExport(
                    id = p.id.name,
                    suppressed = p.suppressed,
                    resolution = p.resolution,
                )
            },
            resolution = ResolutionExport(
                smbDemandU = snapshot.resolutions.smbDemandU,
                tbrDemandFraction = snapshot.resolutions.tbrDemandFraction,
                waitBias = snapshot.resolutions.waitBias,
                dominantScaleMin = snapshot.resolutions.dominantScaleMinutes,
                releaseAuthority = snapshot.resolutions.releaseAuthority.name,
                hypoGuardMode = snapshot.resolutions.hypoGuardMode.name,
                suppressTrajBasalShift = snapshot.resolutions.suppressTrajBasalShift,
                hypoMinPredIgnored = snapshot.resolutions.hypoMinPredIgnored,
                reasonCodes = snapshot.resolutions.reasonCodes,
                basalFirstChannel = snapshot.resolutions.basalFirstChannel.name,
                t3cBasalFirst = snapshot.resolutions.t3cBasalFirst?.let {
                    T3cBasalFirstExport(
                        active = it.active,
                        eligible = it.eligible,
                        basalDemandRateUph = it.basalDemandRateUph,
                        boundedRateUph = it.boundedRateUph,
                        maxBasalCapUph = it.maxBasalCapUph,
                        anticipationStrength = it.anticipationStrength,
                        mealConflict = it.mealConflict,
                        postHypoBlock = it.postHypoBlock,
                        exerciseBlock = it.exerciseBlock,
                        hardSafetyBlock = it.hardSafetyBlock,
                        dominantBlocker = it.dominantBlocker,
                        governanceBasalFloorUph = it.governanceBasalFloorUph,
                        governanceAggressivenessFloor = it.governanceAggressivenessFloor,
                        reasonCodes = it.reasonCodes,
                        selectedForProduction = it.selectedForProduction,
                        historicalBypassNeutralized = it.historicalBypassNeutralized,
                        appliedRateUph = it.appliedRateUph,
                        appliedDurationMin = it.appliedDurationMin,
                        runtimeBlocker = it.runtimeBlocker,
                    )
                },
            ),
            loadGovernor = snapshot.loadGovernor,
            mr7Trace = snapshot.mr7Trace,
        )

    fun toJsonObject(export: RecursiveBeliefExport): JSONObject {
        val root = JSONObject()
        root.put("version", export.version)
        root.put("shadow_only", export.shadowOnly)
        root.put("authority_applied", export.authorityApplied)
        export.authorityGate?.let { gate ->
            root.put("authority_gate", JSONObject().apply {
                put("requested_authority", gate.requestedAuthority)
                put("max_allowed_authority", gate.maxAllowedAuthority)
                put("effective_authority", gate.effectiveAuthority)
                put("readiness_score", gate.readinessScore)
                put("lift_blend", gate.liftBlend)
                put("shadow_only", gate.shadowOnly)
                put("soft_limited", gate.softLimited)
                put("reason_codes", JSONArray(gate.reasonCodes))
            })
        }
        export.waveletBands?.let { bands ->
            root.put("wavelet_bands", JSONObject().apply {
                put("high", bands.high)
                put("mid", bands.mid)
                put("low", bands.low)
            })
        }
        root.put("scales", JSONArray(export.scales.map { scale ->
            JSONObject().apply {
                put("tau_min", scale.tauMin)
                put("belief", scale.belief)
                put("terminal_mgdl", scale.terminalMgdl)
                put("urgency", scale.urgency)
                put("leaves", JSONArray(scale.leaves.map { leaf ->
                    JSONObject().apply {
                        put("id", leaf.id)
                        put("signal", leaf.signal)
                        put("weight", leaf.weight)
                        put("credibility", leaf.credibility)
                        put("summary", leaf.summary)
                    }
                }))
            }
        }))
        root.put("tensions", JSONArray(export.tensions.map { t ->
            JSONObject().apply {
                put("parent_tau", t.parentTau)
                put("child_tau", t.childTau)
                put("magnitude", t.magnitude)
                put("dominant", t.dominant ?: JSONObject.NULL)
            }
        }))
        root.put("paradoxes", JSONArray(export.paradoxes.map { p ->
            JSONObject().apply {
                put("id", p.id)
                put("suppressed", p.suppressed)
                put("resolution", p.resolution)
            }
        }))
        root.put("resolution", JSONObject().apply {
            put("smb_demand_u", export.resolution.smbDemandU)
            put("tbr_demand_fraction", export.resolution.tbrDemandFraction)
            put("wait_bias", export.resolution.waitBias)
            put("dominant_scale_min", export.resolution.dominantScaleMin)
            put("release_authority", export.resolution.releaseAuthority)
            put("hypo_guard_mode", export.resolution.hypoGuardMode)
            put("suppress_traj_basal_shift", export.resolution.suppressTrajBasalShift)
            put("hypo_min_pred_ignored", export.resolution.hypoMinPredIgnored)
            put("reason_codes", JSONArray(export.resolution.reasonCodes))
            put("basal_first_channel", export.resolution.basalFirstChannel)
            export.resolution.t3cBasalFirst?.let { t3c ->
                put("t3c_basal_first", JSONObject().apply {
                    put("active", t3c.active)
                    put("eligible", t3c.eligible)
                    put("basal_demand_rate_uph", t3c.basalDemandRateUph)
                    put("bounded_rate_uph", t3c.boundedRateUph)
                    put("max_basal_cap_uph", t3c.maxBasalCapUph)
                    put("anticipation_strength", t3c.anticipationStrength)
                    put("meal_conflict", t3c.mealConflict)
                    put("post_hypo_block", t3c.postHypoBlock)
                    put("exercise_block", t3c.exerciseBlock)
                    put("hard_safety_block", t3c.hardSafetyBlock)
                    put("dominant_blocker", t3c.dominantBlocker ?: JSONObject.NULL)
                    put("governance_basal_floor_uph", t3c.governanceBasalFloorUph ?: JSONObject.NULL)
                    put("governance_aggressiveness_floor", t3c.governanceAggressivenessFloor ?: JSONObject.NULL)
                    put("reason_codes", JSONArray(t3c.reasonCodes))
                    put("selected_for_production", t3c.selectedForProduction)
                    put("historical_bypass_neutralized", t3c.historicalBypassNeutralized)
                    put("applied_rate_uph", t3c.appliedRateUph ?: JSONObject.NULL)
                    put("applied_duration_min", t3c.appliedDurationMin ?: JSONObject.NULL)
                    put("runtime_blocker", t3c.runtimeBlocker ?: JSONObject.NULL)
                })
            }
        })
        export.loadGovernor?.let { lg ->
            root.put("load_governor", JSONObject().apply {
                put("tier", lg.tier)
                put("multiplier_g", lg.multiplierG)
                put("raw_multiplier_g", lg.rawMultiplierG)
                put("smb_tick_cap_u", lg.smbTickCapU)
                put("phys_budget_u", lg.physBudgetU)
                put("stack_score", lg.stackScore)
                put("rise_score", lg.riseScore)
                put("delta_decel_score", lg.deltaDecelScore)
                put("smb_demand_before_u", lg.smbDemandBeforeU)
                put("smb_demand_after_u", lg.smbDemandAfterU)
                put("applied", lg.applied)
                put("reason_codes", JSONArray(lg.reasonCodes))
                put("summary", lg.summary)
                put("tuning_reference", "InsulinLoadGovernor.kt; applied when RBT authority ON")
            })
        }
        root.put("mr7_trace", JSONArray(export.mr7Trace))
        return root
    }

    fun formatLogLine(snapshot: RecursiveBeliefSnapshot): String {
        val r = snapshot.resolutions
        val lg = snapshot.loadGovernor
        val lgNote = lg?.let {
            " LG=${it.tier} g=${"%.2f".format(it.multiplierG)}" +
                if (it.applied) "✓" else "shadow"
        } ?: ""
        val t3cNote = r.t3cBasalFirst?.let { t3c ->
            when {
                t3c.selectedForProduction -> " bf=T3C_APPLIED@${"%.2f".format(t3c.appliedRateUph ?: t3c.boundedRateUph)}U/h"
                t3c.eligible -> " bf=T3C_READY@${"%.2f".format(t3c.boundedRateUph)}U/h"
                t3c.active -> " bf=T3C_BLOCK(${t3c.runtimeBlocker ?: t3c.dominantBlocker ?: "blocked"})"
                else -> ""
            }
        } ?: ""
        return "🌳 RBT: auth=${r.releaseAuthority} smb=${"%.2f".format(r.smbDemandU)}U " +
            "tbr×${"%.2f".format(r.tbrDemandFraction)} paradoxes=${snapshot.paradoxes.size} " +
            "τ*=${r.dominantScaleMinutes}$lgNote$t3cNote"
    }
}
