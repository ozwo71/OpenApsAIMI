package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Explicit SMB arbitration modes for Harmonia after catalog proposals + ML demand.
 * Auditor may CONFIRM/SOFTEN only — never LIFT.
 */
enum class HarmoniaSmbAuthorityMode {
    ACCEPT,
    LIFT_WITHIN_ENVELOPE,
    REDUCE,
}

/**
 * Tree-deployed insulin intent consumed by Harmonia SMB arbitration.
 */
enum class InsulinIntent {
    NONE,
    STABILIZE,
    MEAL_SUPPORT,
    NEED_MORE_INSULIN,
    PROTECTIVE,
}

/**
 * Single SMB authority decision for one tick — produced before finalizeAndCapSMB.
 */
data class HarmoniaSmbAuthorityDecision(
    val mode: HarmoniaSmbAuthorityMode,
    val smbU: Double,
    val demandBeforeU: Double,
    val mpcDemandU: Double,
    val catalogProposedCapU: Double?,
    val catalogCapKind: PatternCapKind?,
    val envelopeMaxU: Double,
    val insulinIntent: InsulinIntent,
    val reasons: List<String>,
    val addsSmbAuthority: Boolean,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("mode", mode.name)
            put("smb_u", smbU)
            put("demand_before_u", demandBeforeU)
            put("mpc_demand_u", mpcDemandU)
            put("catalog_proposed_cap_u", catalogProposedCapU ?: JSONObject.NULL)
            put("catalog_cap_kind", catalogCapKind?.name ?: JSONObject.NULL)
            put("envelope_max_u", envelopeMaxU)
            put("insulin_intent", insulinIntent.name)
            put("reasons", JSONArray(reasons))
            put("adds_smb_authority", addsSmbAuthority)
            put("source", "harmonia_smb_authority_v1")
        }
}

/**
 * Pure arbiter: soft meal proposal + confirmed rise → may lift toward MPC within hard envelope.
 *
 * ⚠️ ASYNC IMPACT: this runs same-tick on the dose path. External Auditor remains advisory only
 * (CONFIRM/SOFTEN next bias) and must never be required to authorize a lift.
 */
object HarmoniaSmbArbiter {

    fun decide(
        demandBeforeU: Double,
        mpcDemandU: Double,
        catalogProposedCapU: Double?,
        catalogCapKind: PatternCapKind?,
        envelopeMaxU: Double,
        insulinIntent: InsulinIntent,
        harmoniaAction: String?,
        riseConfirmed: Boolean,
        mealCertaintySupports: Boolean,
        protectiveBlock: Boolean,
    ): HarmoniaSmbAuthorityDecision {
        val envelope = envelopeMaxU.coerceAtLeast(0.0)
        val before = demandBeforeU.coerceAtLeast(0.0)
        val mpc = mpcDemandU.coerceAtLeast(0.0)
        val softMeal = catalogCapKind == PatternCapKind.SOFT && catalogProposedCapU != null
        val softCap = catalogProposedCapU ?: 0.0
        val reasons = mutableListOf<String>()

        if (protectiveBlock ||
            harmoniaAction == HarmoniaAction.PROTECTIVE_REDUCTION.name ||
            insulinIntent == InsulinIntent.PROTECTIVE
        ) {
            val reduced = if (softMeal) min(before, softCap) else before
            val bounded = min(reduced, envelope)
            reasons += "PROTECTIVE_OR_BLOCK"
            if (bounded + 1e-6 < before) reasons += "HARMONIA_SMB_REDUCE"
            return HarmoniaSmbAuthorityDecision(
                mode = if (bounded + 1e-6 < before) HarmoniaSmbAuthorityMode.REDUCE else HarmoniaSmbAuthorityMode.ACCEPT,
                smbU = bounded,
                demandBeforeU = before,
                mpcDemandU = mpc,
                catalogProposedCapU = catalogProposedCapU,
                catalogCapKind = catalogCapKind,
                envelopeMaxU = envelope,
                insulinIntent = insulinIntent,
                reasons = reasons,
                addsSmbAuthority = bounded + 1e-6 < before,
            )
        }

        val wantsMoreInsulin =
            insulinIntent == InsulinIntent.NEED_MORE_INSULIN ||
                insulinIntent == InsulinIntent.MEAL_SUPPORT ||
                harmoniaAction == HarmoniaAction.MEAL_SUPPORT.name
        val liftEligible =
            softMeal &&
                riseConfirmed &&
                mealCertaintySupports &&
                wantsMoreInsulin &&
                max(mpc, before) > softCap + 1e-6

        if (liftEligible) {
            val lifted = min(max(mpc, before), envelope)
            reasons += "RISE_CONFIRMED"
            reasons += "CATALOG_SOFT_PROPOSAL"
            reasons += "MPC_ABOVE_SOFT_CAP"
            reasons += "LIFT_WITHIN_ENVELOPE"
            catalogProposedCapU?.let { reasons += "CATALOG_SOFT_${"%.2f".format(it)}" }
            reasons += "ENVELOPE_${"%.2f".format(envelope)}"
            return HarmoniaSmbAuthorityDecision(
                mode = HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE,
                smbU = lifted,
                demandBeforeU = before,
                mpcDemandU = mpc,
                catalogProposedCapU = catalogProposedCapU,
                catalogCapKind = catalogCapKind,
                envelopeMaxU = envelope,
                insulinIntent = insulinIntent,
                reasons = reasons,
                // Arbitration is active on LIFT even when demand was already at envelope.
                addsSmbAuthority = true,
            )
        }

        val accepted = min(before, envelope)
        if (softMeal) reasons += "ACCEPT_SOFT_PROPOSAL_CONTEXT"
        else if (catalogCapKind == PatternCapKind.HARD) reasons += "HARD_CATALOG_ALREADY_BOUND"
        else reasons += "ACCEPT_CURRENT_DEMAND"
        return HarmoniaSmbAuthorityDecision(
            mode = HarmoniaSmbAuthorityMode.ACCEPT,
            smbU = accepted,
            demandBeforeU = before,
            mpcDemandU = mpc,
            catalogProposedCapU = catalogProposedCapU,
            catalogCapKind = catalogCapKind,
            envelopeMaxU = envelope,
            insulinIntent = insulinIntent,
            reasons = reasons,
            addsSmbAuthority = false,
        )
    }
}
