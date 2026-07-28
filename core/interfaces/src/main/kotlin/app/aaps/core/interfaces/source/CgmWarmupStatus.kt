package app.aaps.core.interfaces.source

import kotlinx.coroutines.flow.StateFlow

/**
 * Generic, source-agnostic warm-up / (re)connection status of an active CGM source.
 *
 * Exposed by a [CgmWarmupProvider] so UI (dashboard ring) and safety logic can react to a CGM
 * that is warming up or (re)connecting — i.e. temporarily producing no glucose — without hard-coding
 * any specific vendor plugin.
 *
 * @param active true while a warm-up / (re)connection is in progress (nothing to ingest yet).
 * @param phase honest phase of the current warm-up / connection.
 * @param remainingMs countdown in ms if known from the protocol, else null (indeterminate).
 * @param endsAtEpochMs wall-clock end time in epoch ms if known, else null.
 * @param message optional short human-readable status.
 */
data class CgmWarmupStatus(
    val active: Boolean,
    val phase: Phase,
    val remainingMs: Long?,
    val endsAtEpochMs: Long?,
    val message: String?,
) {

    enum class Phase { CONNECTING, RECONNECTING, WARMING, PAIRING, OTHER }
}

/**
 * Implemented by a [BgSource] plugin that can report a generic [CgmWarmupStatus].
 *
 * Consumers cast the active BG source to this interface: `(activeBgSource as? CgmWarmupProvider)?.warmupStatus`.
 */
interface CgmWarmupProvider {

    /** Current warm-up status, or null when there is nothing to show (READY / IDLE / FAILED). */
    val warmupStatus: StateFlow<CgmWarmupStatus?>
}
