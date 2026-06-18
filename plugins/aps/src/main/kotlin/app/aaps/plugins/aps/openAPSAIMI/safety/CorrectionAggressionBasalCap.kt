package app.aaps.plugins.aps.openAPSAIMI.safety

import java.util.Locale

/**
 * Single authority for basal-rate caps implied by [CorrectionAggressionGate].
 * Apply at every TBR write path (Autodrive V3/V2 direct, meal overlay merge, final basal engine).
 */
object CorrectionAggressionBasalCap {

    const val LOG_PREFIX = "CORRECTION_AGGRESSION_CAP"

    data class Result(
        val cappedRateUph: Double,
        val wasCapped: Boolean,
        val maxAllowedUph: Double?,
    )

    fun apply(
        requestedRateUph: Double,
        profileBasalUph: Double,
        gate: CorrectionAggressionGate.Decision?,
    ): Result {
        if (!requestedRateUph.isFinite() || requestedRateUph <= 0.0) {
            return Result(requestedRateUph, wasCapped = false, maxAllowedUph = null)
        }
        if (gate == null || gate.allowRocketBasalScale) {
            return Result(requestedRateUph, wasCapped = false, maxAllowedUph = null)
        }
        val maxAllowed = profileBasalUph * gate.maxBasalScaleCap
        val capped = requestedRateUph.coerceAtMost(maxAllowed)
        return Result(
            cappedRateUph = capped,
            wasCapped = capped < requestedRateUph - 1e-6,
            maxAllowedUph = maxAllowed,
        )
    }

    fun mergeEngineAndRtRates(
        engineRateUph: Double,
        rtRateUph: Double?,
        gate: CorrectionAggressionGate.Decision?,
    ): Double {
        if (rtRateUph == null) return engineRateUph
        return if (gate != null && !gate.allowRocketBasalScale) {
            minOf(engineRateUph, rtRateUph)
        } else {
            maxOf(engineRateUph, rtRateUph)
        }
    }

    fun formatLogLine(
        source: String,
        requestedUph: Double,
        result: Result,
        tier: CorrectionAggressionGate.Tier?,
    ): String =
        buildString {
            append(LOG_PREFIX)
            append(": source=").append(source)
            append(" tier=").append(tier?.name ?: "n/a")
            append(" ").append(String.format(Locale.US, "%.2f", requestedUph))
            append("→").append(String.format(Locale.US, "%.2f", result.cappedRateUph))
            append(" U/h (max=").append(
                result.maxAllowedUph?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a",
            )
            append(")")
        }
}
