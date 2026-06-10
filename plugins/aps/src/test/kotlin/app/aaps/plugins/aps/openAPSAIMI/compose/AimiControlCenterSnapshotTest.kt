package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.R
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AimiControlCenterSnapshotTest {

    private val preferences = mockk<Preferences>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { preferences.get(any<BooleanPreferenceKey>()) } answers {
            firstArg<BooleanPreferenceKey>().defaultValue
        }
        every { preferences.get(any<DoublePreferenceKey>()) } answers {
            firstArg<DoublePreferenceKey>().defaultValue
        }
        every { preferences.get(any<StringPreferenceKey>()) } answers {
            firstArg<StringPreferenceKey>().defaultValue
        }
    }

    @Test
    fun `default snapshot keeps autonomy in observation and physio in moderate mode`() {
        val snapshot = buildAimiControlCenterSnapshot(preferences)

        val autonomy = snapshot.families.first { it.titleResId == R.string.aimi_control_center_autonomy_title }
        val physio = snapshot.families.first { it.titleResId == R.string.aimi_control_center_physio_title }

        assertThat(autonomy.levelLabelResId).isEqualTo(R.string.aimi_control_center_autonomy_observation)
        assertThat(autonomy.status).isEqualTo(AimiProjectionStatus.EquivalentCurrent)
        assertThat(physio.levelLabelResId).isEqualTo(R.string.aimi_control_center_physio_level_moderate)
        assertThat(snapshot.contextSection.details).hasSize(9)
    }

    @Test
    fun `autodrive v3 stack projects a more assertive meal family and controlled autonomy`() {
        every { preferences.get(BooleanKey.OApsAIMIautoDrive) } returns true
        every { preferences.get(BooleanKey.OApsAIMIautoDriveActive) } returns true
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease) } returns true
        every { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow) } returns true
        every { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority) } returns true
        every { preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative) } returns true
        every { preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep) } returns 0.11
        every { preferences.get(DoubleKey.OApsAIMIautodrivePrebolus) } returns 5.0
        every { preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus) } returns 1.5

        val snapshot = buildAimiControlCenterSnapshot(preferences)
        val meal = snapshot.families.first { it.titleResId == R.string.aimi_control_center_meal_title }
        val autonomy = snapshot.families.first { it.titleResId == R.string.aimi_control_center_autonomy_title }

        assertThat(meal.levelLabelResId).isEqualTo(R.string.aimi_control_center_meal_level_very_assertive)
        assertThat(autonomy.levelLabelResId).isEqualTo(R.string.aimi_control_center_autonomy_controlled)
        assertThat(meal.confidence).isGreaterThan(0.55f)
    }
}
