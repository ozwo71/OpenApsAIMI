package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import kotlin.math.min

object PhysiologicalPatternPolicy {

    /** Meal patterns at or above this confidence override activity caps and hyper suppression. */
    const val MEAL_ACTIVE_CONFIDENCE = 0.70

    /**
     * Pattern SMB caps at or below this threshold are not applied to V3 delivery unless RBT
     * authority is effective — they remain visible in resolver shadow / JSONL only.
     */
    const val RESTRICTIVE_SMB_CAP_SHADOW_THRESHOLD_U = 0.55

    fun aggregate(readings: List<PhysiologicalPatternReading>): PhysiologicalPatternSnapshot {
        if (readings.isEmpty()) return PhysiologicalPatternSnapshot.EMPTY

        val active = readings
            .filter { it.confidence >= 0.35 }
            .sortedByDescending { it.confidence }

        if (active.isEmpty()) return PhysiologicalPatternSnapshot.EMPTY

        var suppressMeal = false
        var suppressHyper = false
        var suppressWavelet = false
        var restrictiveCap: Double? = null

        for (reading in active) {
            val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
            if (def.suppressMealInterpretation && reading.confidence >= 0.45) suppressMeal = true
            if (def.suppressHyperRelease && reading.confidence >= 0.40) suppressHyper = true
            if (def.suppressWaveletBoost && reading.confidence >= 0.40) suppressWavelet = true
            def.smbCapU?.let { cap ->
                restrictiveCap = restrictiveCap?.let { min(it, cap) } ?: cap
            }
        }

        val mealActive = active.any { isMealPatternReading(it) && it.confidence >= MEAL_ACTIVE_CONFIDENCE }
        val smbCap = if (mealActive) {
            suppressMeal = false
            suppressHyper = false
            mealPatternCap(active) ?: restrictiveCap
        } else {
            restrictiveCap
        }

        val dominant = active.first()
        val summary = active.take(4).joinToString(",") { "${it.id.name}@${"%.2f".format(it.confidence)}" }

        return PhysiologicalPatternSnapshot(
            active = active,
            dominant = dominant.id,
            dominantConfidence = dominant.confidence,
            suppressMealInterpretation = suppressMeal,
            suppressHyperRelease = suppressHyper,
            suppressWaveletBoost = suppressWavelet,
            smbCapU = smbCap,
            reasonSummary = summary,
        )
    }

    private fun isMealPatternReading(reading: PhysiologicalPatternReading): Boolean =
        reading.id.category == PhysiologicalPatternCategory.MEAL &&
            reading.id != PhysiologicalPatternId.LATE_FAT_PROTEIN

    private fun mealPatternCap(active: List<PhysiologicalPatternReading>): Double? =
        active
            .filter { isMealPatternReading(it) }
            .mapNotNull { PhysiologicalPatternCatalog.definitionOf(it.id).smbCapU }
            .maxOrNull()
}
