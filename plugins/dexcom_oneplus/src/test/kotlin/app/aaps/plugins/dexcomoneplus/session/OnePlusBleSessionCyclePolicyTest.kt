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
                hasSuccessfulCollection = true,
            ),
        ).isTrue()
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
                hasSuccessfulCollection = false,
            ),
        ).isFalse()
    }
}
