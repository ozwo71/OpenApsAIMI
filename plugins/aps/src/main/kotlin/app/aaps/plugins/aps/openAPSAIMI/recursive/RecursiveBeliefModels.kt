package app.aaps.plugins.aps.openAPSAIMI.recursive

enum class ReleaseAuthority {
    NONE,
    SOFT,
    HARD,
}

enum class HypoGuardMode {
    FULL,
    PARTIAL,
    IGNORE_MINPRED,
}

enum class AutodriveModeHint {
    V3,
    V2,
    SKIP,
}

enum class MealChannelHint {
    PRIORITY,
    NORMAL,
    SUPPRESS,
}

data class BeliefLeafReading(
    val id: BeliefLeafId,
    val signal: Double,
    val weight: Double,
    val credibility: Double,
    val rawSummary: String,
)

data class ScaleTension(
    val parentTauMin: Int,
    val childTauMin: Int,
    val magnitude: Double,
    val dominantParadoxId: BeliefParadoxId?,
)

data class BeliefScaleNode(
    val horizonMinutes: Int,
    val belief: Double,
    val terminalMgdl: Double,
    val urgency: Double,
    val leaves: List<BeliefLeafReading>,
)

data class BeliefParadox(
    val id: BeliefParadoxId,
    val suppressed: Boolean,
    val resolution: String,
)

data class DoseChannelResolution(
    val smbDemandU: Double,
    val tbrDemandFraction: Double,
    val waitBias: Double,
    val dominantScaleMinutes: Int,
    val releaseAuthority: ReleaseAuthority,
    val hypoGuardMode: HypoGuardMode,
    val autodriveModeHint: AutodriveModeHint,
    val mealChannel: MealChannelHint,
    val suppressTrajBasalShift: Boolean,
    val hypoMinPredIgnored: Boolean,
    val reasonCodes: List<String>,
)

data class RecursiveBeliefSnapshot(
    val scales: List<BeliefScaleNode>,
    val tensions: List<ScaleTension>,
    val paradoxes: List<BeliefParadox>,
    val resolutions: DoseChannelResolution,
    val mr7Trace: List<String>,
    val waveletBands: WaveletBelief.Bands? = null,
)

data class RecursiveBeliefExport(
    val version: Int,
    val shadowOnly: Boolean,
    val authorityApplied: Boolean,
    val waveletBands: WaveletExport?,
    val scales: List<ScaleExport>,
    val tensions: List<TensionExport>,
    val paradoxes: List<ParadoxExport>,
    val resolution: ResolutionExport,
    val mr7Trace: List<String>,
)

data class ScaleExport(
    val tauMin: Int,
    val belief: Double,
    val terminalMgdl: Double,
    val urgency: Double,
    val leaves: List<LeafExport>,
)

data class LeafExport(
    val id: String,
    val signal: Double,
    val weight: Double,
    val credibility: Double,
    val summary: String,
)

data class TensionExport(
    val parentTau: Int,
    val childTau: Int,
    val magnitude: Double,
    val dominant: String?,
)

data class ParadoxExport(
    val id: String,
    val suppressed: Boolean,
    val resolution: String,
)

data class ResolutionExport(
    val smbDemandU: Double,
    val tbrDemandFraction: Double,
    val waitBias: Double,
    val dominantScaleMin: Int,
    val releaseAuthority: String,
    val hypoGuardMode: String,
    val suppressTrajBasalShift: Boolean,
    val hypoMinPredIgnored: Boolean,
    val reasonCodes: List<String>,
)

data class WaveletExport(
    val high: Double,
    val mid: Double,
    val low: Double,
)
