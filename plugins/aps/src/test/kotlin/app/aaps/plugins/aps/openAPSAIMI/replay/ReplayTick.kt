package app.aaps.plugins.aps.openAPSAIMI.replay

import org.json.JSONObject

/**
 * One loop tick projected from an `AIMI_Decisions_Last24h.jsonl` support package.
 *
 * The fixture format is a **flat** projection: one JSON object per line, short keys, only the
 * fields the harness consumes. A full package is 8–13 MB per day, which is not something to
 * version; the projection is around 150 KB and is the input contract of the harness rather than a
 * partial dump of the export.
 *
 * Fields added after a fixture was captured are simply absent, which is why every property is
 * nullable. That is deliberate: an old fixture must stay loadable so a regression can be compared
 * against days recorded before a field existed.
 *
 * See `docs/adr/0001-replay-harness.md`.
 */
data class ReplayTick(
    val timestampMs: Long,
    val trigger: String?,
    val bgMgdl: Double?,
    val iobU: Double?,
    val cobG: Double?,
    /** Historical export field: carries the command sensitivity, not the profile block. */
    val profileIsfMgdl: Double?,
    val profileBasalUph: Double?,
    /** Static profile ISF. Absent from fixtures captured before ADR 0002. */
    val staticIsfMgdl: Double?,
    /** Sensitivity the command used. Absent from fixtures captured before ADR 0002. */
    val commandIsfMgdl: Double?,
    /** Dynamic ISF source. Absent from fixtures captured before ADR 0003. */
    val isfSource: String?,
    val isfAgeMs: Long?,
    val decision: String?,
    val smbU: Double,
    val basalUph: Double?,
    val originOwner: String?,
    val finalOwner: String?,
    val maxSmbU: Double?,
    val iobHeadroomU: Double?,
    val correctionAggressionTier: String?,
    val safetySource: String?,
    val postHypoGuardState: String?,
    val patientMode: String?,
    val targetBgMgdl: Double?,
    val mealModeActive: Boolean?,
    val postHypoActive: Boolean?,
    val safetyGate: String?,
    val haltRemainingPipeline: Boolean?,
    val uamDominant: String?,
    val absorptionPhase: String?,
    val physiologicalPhase: String?,
    val dynamicIsfMgdl: Double?,
    val eventualMgdl: Double?,
    val minPredMgdl: Double?,
    /** Post-hypo delivery authority. Absent from fixtures captured before ADR 0006. */
    val postHypoAuthorityActive: Boolean?,
    val postHypoAuthorityReason: String?,
    val smbBeforeCapU: Double?,
    val smbAfterCapU: Double?,
) {

    companion object {

        fun fromJson(line: String): ReplayTick {
            val o = JSONObject(line)
            fun str(key: String): String? = if (o.has(key) && !o.isNull(key)) o.getString(key) else null
            fun dbl(key: String): Double? = if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
            fun lng(key: String): Long? = if (o.has(key) && !o.isNull(key)) o.getLong(key) else null
            fun bool(key: String): Boolean? = if (o.has(key) && !o.isNull(key)) o.getBoolean(key) else null
            return ReplayTick(
                timestampMs = o.getLong("t"),
                trigger = str("trig"),
                bgMgdl = dbl("bg"),
                iobU = dbl("iob"),
                cobG = dbl("cob"),
                profileIsfMgdl = dbl("pisf"),
                profileBasalUph = dbl("pbasal"),
                staticIsfMgdl = dbl("sisf"),
                commandIsfMgdl = dbl("cisf"),
                isfSource = str("isrc"),
                isfAgeMs = lng("iage"),
                decision = str("dec"),
                smbU = dbl("amt") ?: 0.0,
                basalUph = dbl("basal"),
                originOwner = str("owner"),
                finalOwner = str("fowner"),
                maxSmbU = dbl("maxsmb"),
                iobHeadroomU = dbl("iobhead"),
                correctionAggressionTier = str("tier"),
                safetySource = str("safety"),
                postHypoGuardState = str("phguard"),
                patientMode = str("pmode"),
                targetBgMgdl = dbl("tgt"),
                mealModeActive = bool("mealmode"),
                postHypoActive = bool("posthypo"),
                safetyGate = str("sgate"),
                haltRemainingPipeline = bool("halt"),
                uamDominant = str("uam"),
                absorptionPhase = str("absorb"),
                physiologicalPhase = str("phase"),
                dynamicIsfMgdl = dbl("disf"),
                eventualMgdl = dbl("ev"),
                minPredMgdl = dbl("minpred"),
                postHypoAuthorityActive = bool("phd_active"),
                postHypoAuthorityReason = str("phd_reason"),
                smbBeforeCapU = dbl("phd_before"),
                smbAfterCapU = dbl("phd_after"),
            )
        }
    }
}
