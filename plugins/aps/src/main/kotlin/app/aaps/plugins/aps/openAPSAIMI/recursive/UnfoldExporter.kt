package app.aaps.plugins.aps.openAPSAIMI.recursive

import org.json.JSONArray
import org.json.JSONObject

object UnfoldExporter {

    fun toExport(
        snapshot: RecursiveBeliefSnapshot,
        shadowOnly: Boolean,
        authorityApplied: Boolean,
        waveletBands: WaveletBelief.Bands? = null,
    ): RecursiveBeliefExport =
        RecursiveBeliefExport(
            version = 1,
            shadowOnly = shadowOnly,
            authorityApplied = authorityApplied,
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
            ),
            mr7Trace = snapshot.mr7Trace,
        )

    fun toJsonObject(export: RecursiveBeliefExport): JSONObject {
        val root = JSONObject()
        root.put("version", export.version)
        root.put("shadow_only", export.shadowOnly)
        root.put("authority_applied", export.authorityApplied)
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
        })
        root.put("mr7_trace", JSONArray(export.mr7Trace))
        return root
    }

    fun formatLogLine(snapshot: RecursiveBeliefSnapshot): String {
        val r = snapshot.resolutions
        return "🌳 RBT: auth=${r.releaseAuthority} smb=${"%.2f".format(r.smbDemandU)}U " +
            "tbr×${"%.2f".format(r.tbrDemandFraction)} paradoxes=${snapshot.paradoxes.size} " +
            "τ*=${r.dominantScaleMinutes}"
    }
}
