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
    fun `default snapshot keeps autonomy in controlled authority with rbt live defaults`() {
        val snapshot = buildAimiControlCenterSnapshot(preferences)

        val autonomy = snapshot.families.first { it.id == AimiBehaviorFamilyId.Autonomy }
        val physio = snapshot.families.first { it.id == AimiBehaviorFamilyId.Physio }

        assertThat(autonomy.levelLabelResId).isEqualTo(R.string.aimi_control_center_autonomy_controlled)
        assertThat(autonomy.status).isEqualTo(AimiProjectionStatus.CoherentProfile)
        assertThat(physio.levelLabelResId).isEqualTo(R.string.aimi_control_center_physio_level_moderate)
        assertThat(snapshot.contextSection.details).hasSize(9)
        assertThat(snapshot.sourceSection.details).hasSize(2)
    }

    @Test
    fun `all snapshot detail rows expose a valid title resource`() {
        val snapshot = buildAimiControlCenterSnapshot(preferences)
        val details = snapshot.families.flatMap { it.details } +
            snapshot.contextSection.details +
            snapshot.sourceSection.details

        assertThat(details).isNotEmpty()
        assertThat(details.map { it.titleResId }).containsNoDuplicates()
        assertThat(details.map { it.titleResId }).containsNoneIn(listOf(0))
        assertThat(
            details.first { detail ->
                detail.titleResId == R.string.autodrive_max_basal_title
            }.valueText,
        ).isNotNull()
    }

    @Test
    fun `autodrive v3 stack projects a more assertive meal family and controlled autonomy`() {
        every { preferences.get(BooleanKey.OApsAIMIautoDriveActive) } returns true
        every { preferences.get(BooleanKey.OApsAIMIautoDrive) } returns true
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease) } returns true
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive) } returns true
        every { preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative) } returns true
        every { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow) } returns true
        every { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority) } returns true
        every { preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep) } returns 0.11
        every { preferences.get(DoubleKey.autodriveMaxBasal) } returns 9.0
        every { preferences.get(DoubleKey.meal_modes_MaxBasal) } returns 10.0
        every { preferences.get(DoubleKey.OApsAIMIautodrivePrebolus) } returns 5.0
        every { preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus) } returns 1.5

        val snapshot = buildAimiControlCenterSnapshot(preferences)
        val meal = snapshot.families.first { it.id == AimiBehaviorFamilyId.MealCapture }
        val autonomy = snapshot.families.first { it.id == AimiBehaviorFamilyId.Autonomy }

        assertThat(meal.levelLabelResId).isEqualTo(R.string.aimi_control_center_meal_level_very_assertive)
        assertThat(autonomy.levelLabelResId).isEqualTo(R.string.aimi_control_center_autonomy_controlled)
        assertThat(meal.managedPreferenceCount).isEqualTo(9)
        assertThat(meal.expertPreferenceCount).isEqualTo(16)
        assertThat(meal.confidence).isGreaterThan(0.55f)
    }
}
