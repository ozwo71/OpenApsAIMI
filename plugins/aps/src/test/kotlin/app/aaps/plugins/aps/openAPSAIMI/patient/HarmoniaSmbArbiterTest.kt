package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmoniaSmbArbiterTest {

    @Test
    fun lifts_within_maxsmbhb_when_rise_soft_meal_and_mpc_above_proposal() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.20,
            mpcDemandU = 2.20,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE)
        assertThat(decision.smbU).isWithin(1e-9).of(2.20)
        assertThat(decision.addsSmbAuthority).isTrue()
        assertThat(decision.reasons).contains("LIFT_WITHIN_ENVELOPE")
    }

    @Test
    fun hard_protective_catalog_does_not_lift() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 0.40,
            mpcDemandU = 2.20,
            catalogProposedCapU = 0.40,
            catalogCapKind = PatternCapKind.HARD,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.ACCEPT)
        assertThat(decision.smbU).isWithin(1e-9).of(0.40)
        assertThat(decision.addsSmbAuthority).isFalse()
    }

    @Test
    fun protective_block_reduces() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.80,
            mpcDemandU = 2.20,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.PROTECTIVE,
            harmoniaAction = HarmoniaAction.PROTECTIVE_REDUCTION.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = true,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.REDUCE)
        assertThat(decision.smbU).isAtMost(1.20)
        assertThat(decision.addsSmbAuthority).isTrue()
    }
}
