package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class AutodrivePatternCapReplayTest {

    private data class AutoDriveSnapshot(
        val label: String,
        val timestampMs: Long,
    )

    private val autoDriveSnapshots = listOf(
        AutoDriveSnapshot("14:29:12", 52_152_000L),
        AutoDriveSnapshot("21:20:08", 76_808_000L),
    )

    @Test
    fun observed_variant_a_at_14_29_replays_pattern_safety_pkpd_throttle_and_terminal_values() {
        val observed = SmbBindingTrace.Draft(
            timestampMs = 52_152_000L,
            originOwner = "AutodriveV3",
            finalOwner = "AutodriveV3",
            mpcOutputU = 2.2,
            patternActive = "MEAL_UNDECLARED_FAST",
            patternCapU = 1.2,
            safetyNetBaseLimitU = 1.02,
            pkpdBeforeU = 1.2,
            pkpdAfterU = 1.02,
            throttleBeforeU = 1.02,
            throttleAfterU = 0.816,
        )
            .appendStage("PATTERN_CAP", 2.2, 1.2, 1.2, "AUTODRIVE_PRE_TERMINAL", "CAP")
            .appendStage("SAFETY_PRECAUTIONS_PKPD", 1.2, 1.02, phase = "FINALIZE", kind = "GUARD")
            .appendStage("SAFETY_NET", 1.02, 1.02, 1.02, "FINALIZE", "CAP")
            .appendStage("PKPD_THROTTLE", 1.02, 0.816, phase = "FINALIZE", kind = "DAMPEN")
            .appendStage("FINAL", 0.816, 0.816, phase = "EXPORT", kind = "OBSERVATION")
            .build(finalU = 0.816)

        assertThat(observed.bindingStage).isEqualTo("PATTERN_CAP")
        assertThat(observed.finalU).isWithin(1e-9).of(0.816)
        assertThat(observed.stages.map { it.afterU })
            .containsExactly(1.2, 1.02, 1.02, 0.816, 0.816)
            .inOrder()
    }

    @Test
    fun observed_variant_a_at_21_20_replays_refractory_throttle_and_red_carpet_restore() {
        val observed = SmbBindingTrace.Draft(
            timestampMs = 76_808_000L,
            originOwner = "AutodriveV3",
            finalOwner = "AutodriveV3",
            mpcOutputU = 2.2,
            patternActive = "MEAL_FIRST_WAVE",
            patternCapU = 1.2,
            safetyNetBaseLimitU = 0.72,
            throttleBeforeU = 0.29,
            throttleAfterU = 0.23,
            redCarpetBeforeU = 0.23,
            redCarpetAfterU = 1.2,
        )
            .appendStage("PATTERN_CAP", 2.2, 1.2, 1.2, "AUTODRIVE_PRE_TERMINAL", "CAP")
            .appendStage("SAFETY_NET", 1.2, 0.72, 0.72, "FINALIZE", "CAP")
            .appendStage("REFRACTORY", 0.72, 0.29, phase = "FINALIZE", kind = "GUARD")
            .appendStage("PKPD_THROTTLE", 0.29, 0.23, phase = "FINALIZE", kind = "DAMPEN")
            .appendStage("RED_CARPET", 0.23, 1.2, phase = "FINALIZE", kind = "RESTORE")
            .appendStage("FINAL", 1.2, 1.2, phase = "EXPORT", kind = "OBSERVATION")
            .build(finalU = 1.2)

        assertThat(observed.bindingStage).isEqualTo("PATTERN_CAP")
        assertThat(observed.finalU).isWithin(1e-9).of(1.2)
        assertThat(observed.stages.map { it.afterU })
            .containsExactly(1.2, 0.72, 0.29, 0.23, 1.2, 1.2)
            .inOrder()
    }

    @Test
    fun observed_22_59_is_global_aimi_negative_control_without_autodrive_pattern_binding() {
        val observed = SmbBindingTrace.Draft(
            timestampMs = 82_743_000L,
            originOwner = "GlobalAIMI",
            finalOwner = "GlobalAIMI",
            modelOutputU = 0.72,
            mpcOutputU = 0.0,
            tier = "OFF",
            patternCapU = null,
        )
            .appendStage("GLOBAL_AIMI", 0.72, 0.72)
            .appendStage("FINAL", 0.72, 0.72)
            .build(finalU = 0.72)

        assertThat(observed.originOwner).isEqualTo("GlobalAIMI")
        assertThat(observed.finalOwner).isEqualTo("GlobalAIMI")
        assertThat(observed.mpcOutputU).isEqualTo(0.0)
        assertThat(observed.finalU).isWithin(1e-9).of(0.72)
        assertThat(observed.stages.map { it.name }).doesNotContain("PATTERN_CAP")
        assertThat(observed.bindingStage).isNull()
    }

    @Test
    fun isolated_pattern_cap_counterfactual_stops_before_terminal_protections_and_is_not_a_terminal_dose_prediction() {
        autoDriveSnapshots.forEach { snapshot ->
            val cappedBeforeTerminalProtections = SmbBindingTrace.replayCapsBeforeTerminalProtections(
                timestampMs = snapshot.timestampMs,
                autodriveRequestU = 2.2,
                patternCapU = 1.2,
            )
            val neutralizedBeforeTerminalProtections = SmbBindingTrace.replayCapsBeforeTerminalProtections(
                timestampMs = snapshot.timestampMs,
                autodriveRequestU = 2.2,
                patternCapU = null,
            )

            assertWithMessage(snapshot.label)
                .that(cappedBeforeTerminalProtections.afterPatternCapU)
                .isWithin(1e-9)
                .of(1.2)
            assertWithMessage(snapshot.label)
                .that(cappedBeforeTerminalProtections.bindingStage)
                .isEqualTo("PATTERN_CAP")
            assertWithMessage(snapshot.label)
                .that(neutralizedBeforeTerminalProtections.afterPatternCapU)
                .isWithin(1e-9)
                .of(2.2)
            assertWithMessage(snapshot.label)
                .that(neutralizedBeforeTerminalProtections.bindingStage)
                .isNull()
            assertThat(cappedBeforeTerminalProtections.stages.map { it.name })
                .containsExactly("PATTERN_CAP")
            assertThat(neutralizedBeforeTerminalProtections.stages.map { it.name })
                .containsExactly("PATTERN_CAP")
        }
    }
}
