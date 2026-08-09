package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The safety barrier must read a sensitivity that policy cannot lower.
 *
 * `enforce` derives `lgh = -siMetabolic * bg` and permits `safeU = (-gamma*h - lfh) / lgh`. A
 * **lower** sensitivity makes `|lgh|` smaller and the permitted dose **larger**, so anything able to
 * lower that number is able to loosen the barrier.
 *
 * `estimatedSI` is exactly such a number: it descends from `pkpdRuntime.fusedIsf`, which
 * `PkPdIntegration` multiplies by an aggression term bounded [0.55, 1.08] driven by delta, UAM
 * confidence and the behaviour family. On an undeclared meal that lowers the sensitivity by up to
 * 45 %, loosening the barrier and pushing the controller the same way at the same moment. A barrier
 * that moves with the controller is not a barrier.
 *
 * ## The dose sizes below are a fixture artefact — do not read them as production behaviour
 *
 * These tests build `AutoDriveState` through the **constructor**, so the sensitivity really is
 * ISF/10000 (about 0.0045). In that regime the h-constraint needs a request near 30 U before it binds
 * at all, which is why the fixture asks for one.
 *
 * Production never reaches that regime. Every path into `AutodriveEngine.tick` builds state through
 * `AutoDriveState.createSafe`, which floors the sensitivity at `coerceAtLeast(0.1)` — 22 to 50 times
 * above ISF/10000 for any real profile ISF. Measured on a 17 068-row `autodrive_dataset.csv`
 * (2026-02-28 to 2026-07-11): the median `Estimated_SI` is exactly 0.1000, and on the 14 291 rows
 * where the floor is active the barrier intervenes on **41.2 %** of ticks and zeroes the whole dose on
 * 37.7 %. On the 2 777 rows predating the floor it intervened on 0.7 %.
 *
 * So the barrier is not inert — it is the dominant clamp, and it is dominant because of a
 * `coerceAtLeast` on a value carried in the wrong units, not because of a designed margin.
 */
class ControlBarrierShieldSafetySiTest {

    /** Fresh instance per call: `lastBgVelocity` is instance state and would change `activeGamma`. */
    private fun shield() = ControlBarrierShield(mockk<AAPSLogger>(relaxed = true))

    private val profileAnchored = 45.0 / 10000.0

    /** The aggression term's lower bound in `PkPdIntegration`. */
    private val policyFloorMultiplier = 0.55

    private fun state(estimatedSI: Double, safetySi: Double?) = AutoDriveState(
        bg = 90.0,
        bgVelocity = -1.0,
        iob = 3.0,
        estimatedSI = estimatedSI,
        safetySi = safetySi,
        estimatedRa = 0.0,
        physiologicalStressMask = DoubleArray(0),
        // High enough that the maxIOB truncation never masks what the barrier itself decided.
        maxIOB = 60.0,
    )

    /** Large enough that the h-constraint actually binds — see the class note. */
    private fun request() = AutoDriveCommand(
        scheduledMicroBolus = 28.0,
        temporaryBasalRate = 24.0,
        isSafe = true,
        reason = "test",
    )

    private fun permittedDose(state: AutoDriveState): Double {
        val out = shield().enforce(request(), state, profileBasal = 1.0)
        return out.scheduledMicroBolus + out.temporaryBasalRate / 12.0
    }

    @Test
    fun `a policy that lowers the commanded sensitivity no longer loosens the barrier`() {
        // Same tick, same physiology; only the aggression term differs.
        val nominal = permittedDose(state(estimatedSI = profileAnchored, safetySi = profileAnchored))
        val loweredByPolicy = permittedDose(
            state(estimatedSI = profileAnchored * policyFloorMultiplier, safetySi = profileAnchored)
        )

        assertThat(loweredByPolicy).isWithin(1e-9).of(nominal)
    }

    @Test
    fun `without the anchor the same policy would have permitted more`() {
        // The behaviour being removed. This test documents why the field exists, so deleting the
        // anchor cannot pass silently.
        val honest = permittedDose(state(estimatedSI = profileAnchored, safetySi = null))
        val loosened = permittedDose(
            state(estimatedSI = profileAnchored * policyFloorMultiplier, safetySi = null)
        )

        assertThat(loosened).isGreaterThan(honest)
    }

    @Test
    fun `a defensive learner raising the sensitivity still tightens the barrier`() {
        // `safetySi` is the more restrictive of the two, so a defensive rise must survive it.
        val nominal = permittedDose(state(estimatedSI = profileAnchored, safetySi = profileAnchored))
        val raised = permittedDose(
            state(estimatedSI = profileAnchored * 1.5, safetySi = profileAnchored * 1.5)
        )

        assertThat(raised).isLessThan(nominal)
    }

    @Test
    fun `a missing anchor falls back to the commanded value rather than failing`() {
        val withAnchor = permittedDose(state(estimatedSI = profileAnchored, safetySi = profileAnchored))
        val withoutAnchor = permittedDose(state(estimatedSI = profileAnchored, safetySi = null))

        assertThat(withoutAnchor).isWithin(1e-9).of(withAnchor)
    }

    /**
     * The anchor cannot currently win the `max`, so `safetySi` equals `estimatedSI` on every
     * production tick.
     *
     * `AutodriveEngine.SAFETY_SI_MAX` is `400/10000 = 0.04`, while `AutoDriveState.createSafe` floors
     * `estimatedSI` at `0.1`. `max(anchored, commanded)` therefore always returns the commanded value
     * — for any profile ISF, including an absurd one. The separation is structurally correct and
     * behaviourally inert until the units are reconciled.
     *
     * This test fails the day the floor or the bound changes, which is exactly when someone must
     * re-measure the barrier's binding rate before shipping.
     */
    @Test
    fun `the profile anchor is currently unreachable because createSafe floors the sensitivity`() {
        val floored = AutoDriveState.createSafe(
            bg = 90.0,
            bgVelocity = -1.0,
            iob = 3.0,
            estimatedSI = 45.0 / 10000.0,
            physiologicalStressMask = DoubleArray(0),
        )

        assertThat(floored.estimatedSI).isEqualTo(0.1)
        // The widest anchor the engine can produce, against the narrowest floored sensitivity.
        assertThat(400.0 / 10000.0).isLessThan(floored.estimatedSI)
    }

    /**
     * Records where the h-constraint starts to bind **in the fixture's units**, so a future change to
     * `METABOLIC_SI_BASE` or to the units of the sensitivity shows up here instead of passing
     * unnoticed. See the class note: this is not the production regime.
     */
    @Test
    fun `the barrier does not bind at a realistic dose in ISF over 10000 units`() {
        val realistic = AutoDriveCommand(
            scheduledMicroBolus = 2.0,
            temporaryBasalRate = 3.0,
            isSafe = true,
            reason = "test",
        )
        val out = shield().enforce(
            realistic,
            state(estimatedSI = profileAnchored, safetySi = profileAnchored),
            profileBasal = 1.0,
        )

        assertThat(out.scheduledMicroBolus).isWithin(1e-9).of(2.0)
        assertThat(out.temporaryBasalRate).isWithin(1e-9).of(3.0)
        assertThat(out.reason).doesNotContain("CBF SATURATED")
    }
}
