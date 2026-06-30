package app.aaps.plugins.aps.openAPSAIMI.activity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EffortActivityBeliefTest {

    private val t0 = 1_719_730_800_000L // arbitrary base

    private fun inputs(
        nowMs: Long = t0,
        steps5: Int = 0,
        steps15: Int = 0,
        steps60: Int = 0,
        hrAvg15: Int = 0,
        hrResting: Int = 0,
        hrvZ: Double? = null,
        stress: Double = 0.0,
    ) = EffortActivityBelief.Inputs(nowMs, steps5, steps15, steps60, hrAvg15, hrResting, hrvZ, stress)

    @Test
    fun idle_noSignals_doesNotReduceInsulin() {
        val (a, _) = EffortActivityBelief.assess(inputs(), EffortActivityBelief.Memory())
        assertThat(a.state).isEqualTo(EffortActivityBelief.State.IDLE)
        assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.NEUTRAL)
        assertThat(a.smbFactor).isEqualTo(1.0)
        assertThat(a.basalFactor).isEqualTo(1.0)
        assertThat(a.reducesInsulin).isFalse()
    }

    @Test
    fun briskWalk_now_isActiveExertionAndReducesSmbAndBasal() {
        // ~67 steps/min over 15 min + HR elevation
        val (a, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 3000, hrAvg15 = 105, hrResting = 65),
            EffortActivityBelief.Memory(),
        )
        assertThat(a.state).isEqualTo(EffortActivityBelief.State.ACTIVE)
        assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.EXERTION)
        assertThat(a.smbFactor).isLessThan(1.0)
        assertThat(a.basalFactor).isLessThan(1.0)
        assertThat(a.smbFactor).isAtLeast(0.45)
        assertThat(mem.lastEffortMs).isEqualTo(t0)
        assertThat(mem.peakStepsPerMin).isGreaterThan(40.0)
    }

    @Test
    fun recentEffort_memoryKeepsProtectionAfterMovementStops() {
        // The reported episode: walked, then stopped >15 min (tree window cleared) but <120 min ago.
        val (walk, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 4000, hrAvg15 = 105, hrResting = 65),
            EffortActivityBelief.Memory(),
        )
        assertThat(walk.state).isEqualTo(EffortActivityBelief.State.ACTIVE)

        // 35 min later, no current steps at all → tree would be IDLE, but memory must still protect.
        val (after, _) = EffortActivityBelief.assess(
            inputs(nowMs = t0 + 35 * 60_000L, steps5 = 0, steps15 = 0, steps60 = 0, hrAvg15 = 70, hrResting = 65),
            mem,
        )
        assertThat(after.state).isEqualTo(EffortActivityBelief.State.RECENT_EFFORT)
        assertThat(after.posture).isEqualTo(EffortActivityBelief.Posture.EXERTION)
        assertThat(after.smbFactor).isLessThan(1.0) // still reducing — the core fix
        assertThat(after.reducesInsulin).isTrue()
    }

    @Test
    fun recentEffort_decaysToNeutralAfterMemoryHorizon() {
        val (_, mem) = EffortActivityBelief.assess(
            inputs(steps5 = 350, steps15 = 1000, steps60 = 4000, hrAvg15 = 105, hrResting = 65),
            EffortActivityBelief.Memory(),
        )
        // 130 min later → past the 120-min horizon → IDLE, no reduction.
        val (after, _) = EffortActivityBelief.assess(
            inputs(nowMs = t0 + 130 * 60_000L),
            mem,
        )
        assertThat(after.state).isEqualTo(EffortActivityBelief.State.IDLE)
        assertThat(after.smbFactor).isEqualTo(1.0)
        assertThat(after.basalFactor).isEqualTo(1.0)
    }

    @Test
    fun stressWithoutMovement_isStressPostureAndDoesNotReduceInsulin() {
        // HR elevated + HRV depressed + high stress prob, but no steps → must NOT lower insulin.
        val (a, _) = EffortActivityBelief.assess(
            inputs(steps5 = 0, steps15 = 0, steps60 = 0, hrAvg15 = 95, hrResting = 65, hrvZ = -1.2, stress = 0.7),
            EffortActivityBelief.Memory(),
        )
        assertThat(a.posture).isEqualTo(EffortActivityBelief.Posture.STRESS)
        assertThat(a.smbFactor).isEqualTo(1.0)
        assertThat(a.basalFactor).isEqualTo(1.0)
        assertThat(a.confidence).isGreaterThan(0.0)
    }

    @Test
    fun hrvDepression_raisesExertionConfidence() {
        // Moderate movement only (no HR/sustained terms) so the reduction is NOT already saturated
        // at the floor — then HRV depression must deepen it.
        val base = EffortActivityBelief.assess(
            inputs(steps5 = 150, steps15 = 450),
            EffortActivityBelief.Memory(),
        ).first
        val withHrv = EffortActivityBelief.assess(
            inputs(steps5 = 150, steps15 = 450, hrvZ = -1.0),
            EffortActivityBelief.Memory(),
        ).first
        assertThat(base.smbFactor).isGreaterThan(0.45) // not saturated
        assertThat(withHrv.smbFactor).isLessThan(base.smbFactor) // stronger reduction with corroboration
    }

    @Test
    fun assess_isDeterministic() {
        val i = inputs(steps5 = 300, steps15 = 900, steps60 = 3000, hrAvg15 = 100, hrResting = 65)
        val first = EffortActivityBelief.assess(i, EffortActivityBelief.Memory())
        val second = EffortActivityBelief.assess(i, EffortActivityBelief.Memory())
        assertThat(first.first).isEqualTo(second.first)
        assertThat(first.second).isEqualTo(second.second)
    }
}
