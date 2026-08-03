package app.aaps.plugins.dexcomoneplus.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OnePlusBleSessionCyclePolicyTest {

    @Test
    fun `successful collection waits for advertisement and suppresses SessionStart`() {
        val reconnectAttempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT

        assertThat(
            OnePlusBleSessionCyclePolicy.waitForAdvertisementAfterExit(
                deliveredUsableGlucose = true,
            ),
        ).isTrue()
        assertThat(
            OnePlusSessionStartPolicy.wantSessionStartOnAttempt(
                requestNewSensorStart = true,
                attempt = reconnectAttempt,
            ),
        ).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.applyFailureBudget(
                preparedPostCollectionAdvertisement = true,
            ),
        ).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.requireFreshAdvertisementBeforeReconnect(
                persistentAdvertisementMode = true,
            ),
        ).isTrue()
        assertThat(
            OnePlusBleSessionCyclePolicy.allowConnection(
                restoredSessionMode = true,
                advertisementFresh = false,
            ),
        ).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.allowConnection(
                restoredSessionMode = true,
                advertisementFresh = true,
            ),
        ).isTrue()
    }

    @Test
    fun `a restored session can leave the persistent wait through the blind-connect escape`() {
        val silence = OnePlusBleSessionCyclePolicy.BLIND_CONNECT_AFTER_ADV_SILENCE_MS

        // Before the escape delay the restored session still refuses an ADV-less connect…
        assertThat(OnePlusBleSessionCyclePolicy.authorizeBlindConnect(silence - 1)).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.allowConnection(
                restoredSessionMode = true,
                advertisementFresh = false,
                blindFallbackAuthorized = false,
            ),
        ).isFalse()

        // …and once the silence is long enough it may connect without a sighting. Without this the
        // wait is unbounded (field log 2026-08-03: 18 min, no glucose, no error, no retry).
        assertThat(OnePlusBleSessionCyclePolicy.authorizeBlindConnect(silence)).isTrue()
        assertThat(
            OnePlusBleSessionCyclePolicy.allowConnection(
                restoredSessionMode = true,
                advertisementFresh = false,
                blindFallbackAuthorized = true,
            ),
        ).isTrue()
    }

    @Test
    fun `stale MAC is only suspected when another ONE+ is heard and ours stayed silent long enough`() {
        val threshold = OnePlusBleSessionCyclePolicy.STALE_MAC_SUSPICION_AFTER_MS

        assertThat(
            OnePlusBleSessionCyclePolicy.suspectStaleMac(continuousAdvSilenceMs = threshold, foreignSightings = 0),
        ).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.suspectStaleMac(continuousAdvSilenceMs = threshold - 1, foreignSightings = 1),
        ).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.suspectStaleMac(continuousAdvSilenceMs = threshold, foreignSightings = 1),
        ).isTrue()
    }

    @Test
    fun `the wait reports whole minutes of silence`() {
        assertThat(OnePlusBleSessionCyclePolicy.advSilenceMinutes(-5L)).isEqualTo(0L)
        assertThat(OnePlusBleSessionCyclePolicy.advSilenceMinutes(59_999L)).isEqualTo(0L)
        assertThat(OnePlusBleSessionCyclePolicy.advSilenceMinutes(60_000L)).isEqualTo(1L)
        assertThat(OnePlusBleSessionCyclePolicy.advSilenceMinutes(11L * 60_000L)).isEqualTo(11L)
    }

    @Test
    fun `blind-connect escape fires before the stale-MAC suspicion is conclusive`() {
        assertThat(OnePlusBleSessionCyclePolicy.BLIND_CONNECT_AFTER_ADV_SILENCE_MS)
            .isLessThan(OnePlusBleSessionCyclePolicy.STALE_MAC_SUSPICION_AFTER_MS)
    }

    @Test
    fun `exit before glucose and unprepared retry retain failure budget`() {
        assertThat(
            OnePlusBleSessionCyclePolicy.waitForAdvertisementAfterExit(
                deliveredUsableGlucose = false,
            ),
        ).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.applyFailureBudget(
                preparedPostCollectionAdvertisement = false,
            ),
        ).isTrue()
        assertThat(
            OnePlusBleSessionCyclePolicy.requireFreshAdvertisementBeforeReconnect(
                persistentAdvertisementMode = false,
            ),
        ).isFalse()
        assertThat(
            OnePlusBleSessionCyclePolicy.allowConnection(
                restoredSessionMode = false,
                advertisementFresh = false,
            ),
        ).isTrue()
    }
}
