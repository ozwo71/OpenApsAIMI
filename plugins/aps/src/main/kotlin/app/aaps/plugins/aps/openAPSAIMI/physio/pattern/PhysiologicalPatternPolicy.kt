package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import kotlin.math.min

object PhysiologicalPatternPolicy {

    fun aggregate(readings: List<PhysiologicalPatternReading>): PhysiologicalPatternSnapshot {
        if (readings.isEmpty()) return PhysiologicalPatternSnapshot.EMPTY

        val active = readings
            .filter { it.confidence >= 0.35 }
            .sortedByDescending { it.confidence }

        if (active.isEmpty()) return PhysiologicalPatternSnapshot.EMPTY

        var suppressMeal = false
        var suppressHyper = false
        var suppressWavelet = false
        var smbCap: Double? = null

        for (reading in active) {
            val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
            if (def.suppressMealInterpretation && reading.confidence >= 0.45) suppressMeal = true
            if (def.suppressHyperRelease && reading.confidence >= 0.40) suppressHyper = true
            if (def.suppressWaveletBoost && reading.confidence >= 0.40) suppressWavelet = true
            def.smbCapU?.let { cap ->
                smbCap = smbCap?.let { min(it, cap) } ?: cap
            }
        }

        val mealActive = active.any {
            it.id.category == PhysiologicalPatternCategory.MEAL &&
                it.confidence >= 0.70 &&
                it.id != PhysiologicalPatternId.LATE_FAT_PROTEIN
        }
        if (mealActive) {
            suppressMeal = false
            if (active.none { PhysiologicalPatternCatalog.definitionOf(it.id).suppressHyperRelease }) {
                suppressHyper = false
            }
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
}
