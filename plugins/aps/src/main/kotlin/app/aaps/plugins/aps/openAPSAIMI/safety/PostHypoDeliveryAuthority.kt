package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import java.util.Locale

/**
 * Transversal authority when post-hypo rebound guard is active: arbitrates basal (via
 * [CorrectionAggressionBasalCap]), meal interpretation uplift, and SMB delivery.
 *
 * Scoped to [CorrectionAggressionGate.Tier.REBOUND_GUARD] + [PatientMode.POST_HYPO_RECOVERY]
 * without independent meal evidence — real meals escape via gate bg exit or COB/mode.
 */
object PostHypoDeliveryAuthority {

    const val LOG_PREFIX = "POST_HYPO_DELIVERY"

    data class Input(
        val gate: CorrectionAggressionGate.Decision?,
        val patientMode: PatientMode?,
        val aggressionInput: CorrectionAggressionGate.Input,
    )

    data class Decision(
        val active: Boolean,
        val forceMealInterpretationSuppressed: Boolean,
        val suppressMealDelivery: Boolean,
        val maxSmbU: Double,
        val reasonTag: String,
    ) {
        fun capSmbU(requestedU: Double): Double =
            if (!active || !suppressMealDelivery) requestedU else minOf(requestedU, maxSmbU)
    }

    val INACTIVE = Decision(
        active = false,
        forceMealInterpretationSuppressed = false,
        suppressMealDelivery = false,
        maxSmbU = Double.POSITIVE_INFINITY,
        reasonTag = "inactive",
    )

    fun evaluate(input: Input): Decision {
        if (input.gate?.tier != CorrectionAggressionGate.Tier.REBOUND_GUARD) return INACTIVE
        if (input.patientMode != PatientMode.POST_HYPO_RECOVERY) return INACTIVE
        if (CorrectionAggressionGate.hasIndependentMealEvidence(input.aggressionInput)) return INACTIVE
        // Aggressive rise past target+30 with Δ>15 → act normally (product 2026-07-17).
        if (
            PostHypoAggressiveRiseExit.shouldExit(
                bgMgdl = input.aggressionInput.bg,
                targetBgMgdl = input.aggressionInput.targetBg,
                deltaMgdl5m = input.aggressionInput.deltaMgdl5m,
            )
        ) {
            return Decision(
                active = false,
                forceMealInterpretationSuppressed = false,
                suppressMealDelivery = false,
                maxSmbU = Double.POSITIVE_INFINITY,
                reasonTag = PostHypoAggressiveRiseExit.REASON_CODE.lowercase(Locale.US),
            )
        }
        return Decision(
            active = true,
            forceMealInterpretationSuppressed = true,
            suppressMealDelivery = true,
            maxSmbU = 0.0,
            reasonTag = "rebound_guard_post_hypo_arbitration",
        )
    }

    fun formatLogLine(decision: Decision): String =
        buildString {
            append(LOG_PREFIX)
            append(": active=").append(decision.active)
            append(" mealSupp=").append(decision.forceMealInterpretationSuppressed)
            append(" smbCap=").append(
                if (decision.maxSmbU.isFinite()) {
                    String.format(Locale.US, "%.2f", decision.maxSmbU)
                } else {
                    "none"
                },
            )
            append(" tag=").append(decision.reasonTag)
        }
}
