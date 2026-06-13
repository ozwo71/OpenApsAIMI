package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.DoubleKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AimiControlCenterSupportTest {

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
    fun `unchanged draft produces no pending changes`() {
        val currentDraft = readAimiControlCenterDraft(preferences)

        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft,
        )

        assertThat(pending.hasChanges).isFalse()
        assertThat(pending.changedFamilyCount).isEqualTo(0)
        assertThat(pending.changedSettingsCount).isEqualTo(0)
    }

    @Test
    fun `moving one family only previews that family legacy keys`() {
        val currentDraft = readAimiControlCenterDraft(preferences)
        val targetDraft = currentDraft.copy(
            protectionLevel = (currentDraft.protectionLevel + 1).coerceAtMost(4),
        )

        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = targetDraft,
        )

        assertThat(pending.changedFamilyCount).isEqualTo(1)
        assertThat(pending.familyPlans.single().familyId).isEqualTo(AimiBehaviorFamilyId.Protection)
        assertThat(pending.changedSettingsCount).isAtLeast(1)
    }

    @Test
    fun `controlled autonomy apply writes expected legacy booleans`() {
        every { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow) } returns false
        every { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority) } returns false
        every { preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative) } returns false

        val currentDraft = readAimiControlCenterDraft(preferences)
        val targetDraft = currentDraft.copy(autonomyMode = AimiAutonomyMode.ControlledAuthority)
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = targetDraft,
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(BooleanKey.OApsAIMIautoDrive, true) }
        verify { preferences.put(BooleanKey.OApsAIMIautoDriveActive, true) }
        verify { preferences.put(BooleanKey.OApsAIMIRecursiveBeliefShadow, true) }
        verify { preferences.put(BooleanKey.OApsAIMIRecursiveBeliefAuthority, true) }
        verify { preferences.put(BooleanKey.OApsAIMIautoDriveAuthoritative, true) }
    }

    @Test
    fun `protective writeback keeps pkpd guard factors on the protective side`() {
        every { preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor) } returns 0.90
        every { preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold) } returns 0.90

        val currentDraft = readAimiControlCenterDraft(preferences)
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(protectionLevel = 0),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.60) }
        verify { preferences.put(DoubleKey.OApsAIMIRedCarpetRestoreThreshold, 0.60) }
    }
}
