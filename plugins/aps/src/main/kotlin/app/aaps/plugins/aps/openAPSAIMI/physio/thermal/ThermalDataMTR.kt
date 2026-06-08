package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

/**
 * Single skin-temperature sample (Health Connect delta timeline).
 */
data class ThermalSampleMTR(
    val timestampMs: Long,
    val deltaCelsius: Double,
    val measurementLocation: String,
    val dataOrigin: String,
)

/**
 * Latest basal body temperature reading (cycle tracking — Oura, Garmin, manual).
 */
data class BasalBodyTemperatureMTR(
    val timestampMs: Long,
    val temperatureCelsius: Double,
    val dataOrigin: String,
)

internal data class ThermalDataWindowMTR(
    val skinSamples: List<ThermalSampleMTR> = emptyList(),
    val basalBodyTemperature: BasalBodyTemperatureMTR? = null,
    val fetchedAtMs: Long = 0L,
) {
    fun hasSkinData(): Boolean = skinSamples.isNotEmpty()
}
