package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecursiveBeliefHarmoniaSmbTest {

    @Test
    fun resolve_blocksHarmoniaSmb_whenRbtHasNoSmbAuthority() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(),
            releaseScales = false,
        )

        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.NONE)
        assertThat(snapshot.resolutions.smbDemandU).isEqualTo(0.0)
        assertThat(snapshot.resolutions.harmoniaSmb?.eligible).isFalse()
        assertThat(snapshot.resolutions.harmoniaSmb?.dominantBlocker)
            .isEqualTo("BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST")
    }

    @Test
    fun resolve_liftsRbtSmbDemand_whenHarmoniaMealSupportIsSafeAndAuthorityExists() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(targetSmbU = 1.8, maxSmbU = 3.0),
            releaseScales = true,
        )

        val harmonia = snapshot.resolutions.harmoniaSmb
        assertThat(snapshot.resolutions.releaseAuthority).isNotEqualTo(ReleaseAuthority.NONE)
        assertThat(harmonia?.eligible).isTrue()
        assertThat(harmonia?.appliedToRbtDemand).isTrue()
        assertThat(harmonia?.demandAfterU).isAtLeast(1.8)
        assertThat(harmonia?.reasonCodes).contains("HARMONIA_SMB_APPLIED_TO_RBT")
        assertThat(snapshot.resolutions.reasonCodes).contains("HARMONIA_SMB_SUPPORT")
    }

    @Test
    fun resolve_blocksHarmoniaSmb_whenPostHypoGuardIsActive() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(postHypoBlock = true),
            releaseScales = true,
        )

        assertThat(snapshot.resolutions.harmoniaSmb?.eligible).isFalse()
        assertThat(snapshot.resolutions.harmoniaSmb?.dominantBlocker).isEqualTo("POST_HYPO")
        assertThat(snapshot.resolutions.harmoniaSmb?.reasonCodes)
            .contains("HARMONIA_SMB_POST_HYPO_BLOCK")
    }

    @Test
    fun export_containsHarmoniaSmbDecisionDetails() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(targetSmbU = 1.5, maxSmbU = 2.0),
            releaseScales = true,
        )
        val export = UnfoldExporter.toJsonObject(
            UnfoldExporter.toExport(
                snapshot = snapshot,
                shadowOnly = false,
                authorityApplied = true,
            ),
        )
        val harmonia = export
            .getJSONObject("resolution")
            .getJSONObject("harmonia_smb")

        assertThat(harmonia.getBoolean("active")).isTrue()
        assertThat(harmonia.getBoolean("eligible")).isTrue()
        assertThat(harmonia.getBoolean("applied_to_rbt_demand")).isTrue()
        assertThat(harmonia.getDouble("bounded_smb_u")).isAtMost(2.0)
        assertThat(harmonia.getJSONArray("reason_codes").toString())
            .contains("HARMONIA_SMB_SUPPORT_READY")
    }

    private fun harmoniaMealSupport(
        targetSmbU: Double = 1.6,
        maxSmbU: Double = 3.0,
        postHypoBlock: Boolean = false,
    ): RbtExtendedSignals =
        RbtExtendedSignals(
            harmoniaActive = true,
            harmoniaDecisionEligible = true,
            harmoniaAction = "MEAL_SUPPORT",
            harmoniaBranch = "MEAL_RISE",
            harmoniaBasalDemandRateUph = 1.6,
            harmoniaBasalMaxRateUph = 5.0,
            harmoniaSmbDemandU = targetSmbU,
            harmoniaSmbMaxU = maxSmbU,
            harmoniaPostHypoBlock = postHypoBlock,
            harmoniaBlockReason = if (postHypoBlock) "POST_HYPO" else null,
        )

    private fun resolveWith(
        ext: RbtExtendedSignals,
        releaseScales: Boolean,
    ): RecursiveBeliefSnapshot {
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            bg = 226.0,
            delta = if (releaseScales) 5.0 else 0.5,
            iob = 1.0,
            maxIob = 12.0,
            replaceHtrRelease = false,
            hypoMinPredIgnored = false,
            extended = ext,
        )
        val scales = if (releaseScales) {
            listOf(
                beliefScale(15, belief = 0.72, urgency = 0.75, terminal = 245.0),
                beliefScale(60, belief = 0.80, urgency = 1.25, terminal = 285.0),
                beliefScale(180, belief = 0.40, urgency = 0.10, terminal = 180.0),
            )
        } else {
            listOf(
                beliefScale(15, belief = 0.45, urgency = 0.10, terminal = 135.0),
                beliefScale(60, belief = 0.50, urgency = 0.10, terminal = 145.0),
                beliefScale(180, belief = 0.35, urgency = 0.00, terminal = 125.0),
            )
        }
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
