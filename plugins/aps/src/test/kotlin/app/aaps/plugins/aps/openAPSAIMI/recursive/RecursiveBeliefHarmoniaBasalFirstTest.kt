package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecursiveBeliefHarmoniaBasalFirstTest {

    @Test
    fun resolve_selectsHarmoniaBasalFirst_whenEligibleAndT3cAbsent() {
        val snapshot = resolveWith(
            RbtExtendedSignals(
                harmoniaActive = true,
                harmoniaSimulationEligible = true,
                harmoniaAction = "BASAL_FIRST",
                harmoniaBranch = "RESISTANCE_PROBABLE",
                harmoniaBasalDemandRateUph = 1.8,
                harmoniaBasalMaxRateUph = 5.0,
            ),
        )

        assertThat(snapshot.resolutions.basalFirstChannel)
            .isEqualTo(BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST)
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.eligible).isTrue()
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.boundedRateUph).isEqualTo(1.8)
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.reasonCodes)
            .contains("HARMONIA_BASAL_FIRST_READY")
    }

    @Test
    fun resolve_keepsT3cPriority_whenBothBasalFirstBranchesAreEligible() {
        val snapshot = resolveWith(
            RbtExtendedSignals(
                t3cActive = true,
                t3cBasalDemandRateUph = 2.0,
                t3cBasalMaxRateUph = 5.0,
                harmoniaActive = true,
                harmoniaSimulationEligible = true,
                harmoniaAction = "BASAL_FIRST",
                harmoniaBranch = "RESISTANCE_PROBABLE",
                harmoniaBasalDemandRateUph = 1.8,
                harmoniaBasalMaxRateUph = 5.0,
            ),
        )

        assertThat(snapshot.resolutions.basalFirstChannel)
            .isEqualTo(BasalFirstChannel.T3C_BASAL_FIRST)
        assertThat(snapshot.resolutions.t3cBasalFirst?.eligible).isTrue()
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.eligible).isTrue()
    }

    @Test
    fun export_containsHarmoniaBasalFirstBlocker() {
        val snapshot = resolveWith(
            RbtExtendedSignals(
                harmoniaActive = true,
                harmoniaSimulationEligible = true,
                harmoniaAction = "BASAL_FIRST",
                harmoniaBranch = "RECOVERY",
                harmoniaBasalDemandRateUph = 1.4,
                harmoniaBasalMaxRateUph = 5.0,
                harmoniaPostHypoBlock = true,
                harmoniaBlockReason = "POST_HYPO",
            ),
        )
        val export = UnfoldExporter.toJsonObject(
            UnfoldExporter.toExport(
                snapshot = snapshot,
                shadowOnly = false,
                authorityApplied = false,
            ),
        )
        val harmonia = export
            .getJSONObject("resolution")
            .getJSONObject("harmonia_basal_first")

        assertThat(snapshot.resolutions.basalFirstChannel).isEqualTo(BasalFirstChannel.NONE)
        assertThat(harmonia.getBoolean("eligible")).isFalse()
        assertThat(harmonia.getString("dominant_blocker")).isEqualTo("POST_HYPO")
        assertThat(harmonia.getJSONArray("reason_codes").toString())
            .contains("HARMONIA_POST_HYPO_BLOCK")
    }

    private fun resolveWith(ext: RbtExtendedSignals): RecursiveBeliefSnapshot {
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = 0.0,
            replaceHtrRelease = false,
            hypoMinPredIgnored = false,
            extended = ext,
        )
        val scales = listOf(
            beliefScale(15, belief = 0.45, urgency = 0.1, terminal = 135.0),
            beliefScale(60, belief = 0.50, urgency = 0.1, terminal = 145.0),
            beliefScale(180, belief = 0.35, urgency = 0.0, terminal = 125.0),
        )
        return RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(
                ctx = ctx,
                scales = scales,
                authorityEnabled = true,
            ),
        )
    }

    private fun beliefScale(tau: Int, belief: Double, urgency: Double, terminal: Double) =
        BeliefScaleNode(
            horizonMinutes = tau,
            belief = belief,
            terminalMgdl = terminal,
            urgency = urgency,
            leaves = emptyList(),
        )
}
