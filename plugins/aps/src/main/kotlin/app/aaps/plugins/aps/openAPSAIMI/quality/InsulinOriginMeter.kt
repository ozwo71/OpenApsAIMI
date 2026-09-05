package app.aaps.plugins.aps.openAPSAIMI.quality

import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Measures **where the delivered insulin came from**: the model, or the floors that sit under it.
 *
 * A tick can end with the same amount of insulin for two very different reasons. Either the model
 * asked for it, or the model asked for nothing and a floor delivered it anyway. The exported
 * `smb_binding_trace` says this for one tick, but one tick answers nothing: the question is what
 * share of a week of insulin the model actually decided. This class keeps that running share.
 *
 * ## Attribution rule
 *
 * For one sample, `raw = max(modelOutputU, autodriveFloorU)`. The floor share of the delivered
 * amount is `finalU * (raw - modelOutputU) / raw`, and the model share is the rest. So:
 *
 *  - model 1.0, floor 0.0 → everything is model,
 *  - model 0.0, floor 1.5 → everything is floor,
 *  - model 0.6, floor 1.2 → half model, half floor,
 *  - both zero → nothing is attributed, and the amount lands in `otherOriginU`.
 *
 * The shares are shares of what was **delivered**, not of what was asked for, so they always sum
 * back to `deliveredU`. Caps and guards that cut the amount after the fact therefore cut both
 * shares in the same proportion, which is the honest reading: a cap does not change who proposed.
 *
 * ## Strictly passive
 *
 * Nothing in the dosing chain may read this. To check that, run:
 *
 * ```
 * grep -rn "InsulinOriginMeter\|insulinOriginMeter" plugins/aps/src/main/kotlin/
 * ```
 *
 * It must return exactly two zones: this file, and the import plus the single field and feeding
 * block in `DetermineBasalAIMI2`. Any other line is a violation.
 *
 * ## No persistence
 *
 * Samples live in memory only. A process restart empties them. `tickCount` is the witness: when it
 * is small, the shares have simply not been rebuilt yet.
 *
 * @param maxTicks how many ticks the window keeps. The default, 2016, is one week of 5 minute ticks.
 */
class InsulinOriginMeter(private val maxTicks: Int = 2016) {

    /**
     * One tick of delivery, as the binding trace saw it.
     *
     * @param timestampMs tick time.
     * @param finalU insulin really delivered by this tick, U.
     * @param modelOutputU what the model asked for before the floors, U. `null` when unknown.
     * @param mpcOutputU what the MPC asked for, U. Carried for the export, not used in the split.
     * @param autodriveFloorU the floor that was in force, U. `null` when there was none.
     * @param bindingStage last stage name of the binding trace, for reading back.
     * @param originOwner which channel owned the amount.
     */
    data class Sample(
        val timestampMs: Long,
        val finalU: Double,
        val modelOutputU: Double?,
        val mpcOutputU: Double?,
        val autodriveFloorU: Double?,
        val bindingStage: String?,
        val originOwner: String,
    )

    /**
     * The running answer over the window.
     *
     * @param tickCount how many samples the window holds.
     * @param deliveredU total insulin delivered over them, U.
     * @param modelOriginU part of it the model asked for, U.
     * @param floorOriginU part of it a floor added on top of the model, U.
     * @param otherOriginU part of it neither could explain, U. Grows when insulin is delivered while
     *   both the model output and the floor are zero or unknown.
     * @param modelZeroDeliveredU insulin delivered on ticks where the model asked for nothing, U.
     * @param modelZeroTickCount how many ticks those were.
     * @param modelOriginShare `modelOriginU / deliveredU`, `null` while nothing was delivered. Null,
     *   never `0.0`: zero would read as "the model decided nothing", which is a different statement.
     * @param floorOriginShare `floorOriginU / deliveredU`, same rule.
     * @param windowStartMs time of the oldest kept sample, so a reading can be aged.
     */
    data class Reading(
        val tickCount: Int,
        val deliveredU: Double,
        val modelOriginU: Double,
        val floorOriginU: Double,
        val otherOriginU: Double,
        val modelZeroDeliveredU: Double,
        val modelZeroTickCount: Int,
        val modelOriginShare: Double?,
        val floorOriginShare: Double?,
        val windowStartMs: Long?,
    ) {

        fun toJsonObject(): JSONObject =
            JSONObject().apply {
                put("tick_count", tickCount)
                put("delivered_u", deliveredU)
                put("model_origin_u", modelOriginU)
                put("floor_origin_u", floorOriginU)
                put("other_origin_u", otherOriginU)
                put("model_zero_delivered_u", modelZeroDeliveredU)
                put("model_zero_tick_count", modelZeroTickCount)
                put("model_origin_share", modelOriginShare ?: JSONObject.NULL)
                put("floor_origin_share", floorOriginShare ?: JSONObject.NULL)
                put("window_start_ms", windowStartMs ?: JSONObject.NULL)
                put("source", "insulin_origin_meter_v1")
            }
    }

    /** One kept sample, already split, so [read] never has to walk the window. */
    private data class Entry(
        val timestampMs: Long,
        val deliveredU: Double,
        val modelU: Double,
        val floorU: Double,
        val otherU: Double,
        val modelZeroU: Double,
        val modelZero: Boolean,
    )

    private val entries = ArrayDeque<Entry>()

    private var deliveredSum = 0.0
    private var modelSum = 0.0
    private var floorSum = 0.0
    private var otherSum = 0.0
    private var modelZeroSum = 0.0
    private var modelZeroTicks = 0

    /** Below this, the model is treated as having asked for nothing. Half a pump step. */
    private val modelZeroThresholdU = 0.01

    /** Guard against dividing by a raw amount that is zero in all but name. */
    private val epsilon = 1e-9

    /** Records one tick. Amortised O(1): one push, and at most one pop per push. */
    fun observe(s: Sample) {
        val delivered = s.finalU.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val model = s.modelOutputU?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val floor = s.autodriveFloorU?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val raw = maxOf(model, floor)

        val floorPart: Double
        val modelPart: Double
        val otherPart: Double
        if (delivered <= 0.0) {
            floorPart = 0.0
            modelPart = 0.0
            otherPart = 0.0
        } else if (raw > epsilon) {
            floorPart = delivered * ((raw - model) / raw)
            modelPart = delivered - floorPart
            otherPart = 0.0
        } else {
            // Insulin was delivered while neither the model nor a floor claimed it. Never silently
            // credit that to the model: it is exactly the case this meter exists to expose.
            floorPart = 0.0
            modelPart = 0.0
            otherPart = delivered
        }

        val modelZero = (s.modelOutputU ?: 0.0) <= modelZeroThresholdU
        val entry = Entry(
            timestampMs = s.timestampMs,
            deliveredU = delivered,
            modelU = modelPart,
            floorU = floorPart,
            otherU = otherPart,
            modelZeroU = if (modelZero) delivered else 0.0,
            modelZero = modelZero,
        )
        entries.addLast(entry)
        deliveredSum += entry.deliveredU
        modelSum += entry.modelU
        floorSum += entry.floorU
        otherSum += entry.otherU
        modelZeroSum += entry.modelZeroU
        if (entry.modelZero) modelZeroTicks++

        while (entries.size > maxTicks) {
            val old = entries.removeFirst()
            deliveredSum -= old.deliveredU
            modelSum -= old.modelU
            floorSum -= old.floorU
            otherSum -= old.otherU
            modelZeroSum -= old.modelZeroU
            if (old.modelZero) modelZeroTicks--
        }
    }

    /**
     * Reads the running answer. O(1): the sums are kept by [observe].
     *
     * @param nowMs current time. Kept in the signature so the caller does not have to reach for a
     *   clock of its own, and so a future age-based window can be added without a call site change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun read(nowMs: Long): Reading {
        val delivered = deliveredSum.coerceAtLeast(0.0)
        val hasDelivery = delivered > epsilon
        return Reading(
            tickCount = entries.size,
            deliveredU = delivered,
            modelOriginU = modelSum,
            floorOriginU = floorSum,
            otherOriginU = otherSum,
            modelZeroDeliveredU = modelZeroSum,
            modelZeroTickCount = modelZeroTicks,
            modelOriginShare = if (hasDelivery) modelSum / delivered else null,
            floorOriginShare = if (hasDelivery) floorSum / delivered else null,
            windowStartMs = entries.peekFirst()?.timestampMs,
        )
    }
}
