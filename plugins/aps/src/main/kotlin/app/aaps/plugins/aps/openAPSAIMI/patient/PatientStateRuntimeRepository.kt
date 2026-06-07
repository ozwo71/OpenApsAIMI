package app.aaps.plugins.aps.openAPSAIMI.patient

import java.util.concurrent.atomic.AtomicReference

internal data class PatientRuntimeSnapshot(
    val patientState: PatientStateSnapshot,
    val patientModeDecision: PatientModeOrchestrator.Decision,
    val updatedAtMs: Long = patientState.timestampMs,
)

internal object PatientStateRuntimeRepository {

    private val latestRef = AtomicReference<PatientRuntimeSnapshot?>(null)

    fun publish(
        patientState: PatientStateSnapshot,
        patientModeDecision: PatientModeOrchestrator.Decision,
        updatedAtMs: Long = patientState.timestampMs,
    ) {
        latestRef.set(
            PatientRuntimeSnapshot(
                patientState = patientState,
                patientModeDecision = patientModeDecision,
                updatedAtMs = updatedAtMs,
            ),
        )
    }

    fun getLatest(): PatientRuntimeSnapshot? = latestRef.get()

    fun clear() {
        latestRef.set(null)
    }
}
