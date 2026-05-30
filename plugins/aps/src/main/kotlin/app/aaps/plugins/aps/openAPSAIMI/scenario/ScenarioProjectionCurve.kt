package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskConstants

/**
 * One clamped BG projection path (5-min steps, includes t=0).
 */
data class ScenarioProjectionCurve(
    val kind: ScenarioProjectionKind,
    val pointsMgdl: List<Int>,
    val terminalMgdl: Double,
    val pathMinMgdl: Double,
    val pathMinHitFloor: Boolean,
) {
    init {
        require(pointsMgdl.isNotEmpty()) { "Scenario curve must have at least one point" }
    }

    companion object {
        fun fromRawPoints(kind: ScenarioProjectionKind, raw: List<Double>): ScenarioProjectionCurve {
            val clamped = raw.map { point ->
                point.coerceIn(AimiRiskConstants.NUMERIC_FLOOR_MGDL, AimiRiskConstants.NUMERIC_CEILING_MGDL)
            }
            val ints = clamped.map { it.toInt() }
            val rawMin = raw.filter { it.isFinite() }.minOrNull() ?: clamped.first()
            val clampMin = clamped.minOrNull() ?: clamped.first()
            return ScenarioProjectionCurve(
                kind = kind,
                pointsMgdl = ints,
                terminalMgdl = clamped.last(),
                pathMinMgdl = clampMin,
                pathMinHitFloor = rawMin < AimiRiskConstants.NUMERIC_FLOOR_MGDL,
            )
        }
    }
}
