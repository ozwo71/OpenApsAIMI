package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.SliderWithButtons
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun AimiControlCenterScreen(
    preferences: Preferences,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var preferenceRevision by remember { mutableIntStateOf(0) }
    val currentSnapshot = remember(preferenceRevision) { buildAimiControlCenterSnapshot(preferences) }
    val currentDraft = remember(preferenceRevision) { readAimiControlCenterDraft(preferences) }
    var protectionLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.protectionLevel) }
    var mealCaptureLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.mealCaptureLevel) }
    var stabilityLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.stabilityLevel) }
    var physioLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.physioLevel) }
    var autonomyMode by remember(preferenceRevision) { mutableStateOf(currentDraft.autonomyMode) }
    var showApplyConfirm by remember { mutableStateOf(false) }

    val targetDraft = AimiControlCenterDraft(
        protectionLevel = protectionLevel,
        mealCaptureLevel = mealCaptureLevel,
        stabilityLevel = stabilityLevel,
        physioLevel = physioLevel,
        autonomyMode = autonomyMode,
    )
    val pendingChanges = remember(
        preferenceRevision,
        protectionLevel,
        mealCaptureLevel,
        stabilityLevel,
        physioLevel,
        autonomyMode,
    ) {
        buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = targetDraft,
        )
    }
    val appliedMessage = stringResource(R.string.aimi_control_center_apply_done)

    fun resetDraft() {
        protectionLevel = currentDraft.protectionLevel
        mealCaptureLevel = currentDraft.mealCaptureLevel
        stabilityLevel = currentDraft.stabilityLevel
        physioLevel = currentDraft.physioLevel
        autonomyMode = currentDraft.autonomyMode
    }

    fun applyDraft() {
        applyAimiControlCenterPendingChanges(preferences, pendingChanges)
        preferenceRevision++
        scope.launch { snackbarHostState.showSnackbar(appliedMessage) }
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text(stringResource(R.string.aimi_control_center_confirm_apply_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.aimi_control_center_confirm_apply_body,
                        pendingChanges.changedFamilyCount,
                        pendingChanges.changedSettingsCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showApplyConfirm = false
                        applyDraft()
                    },
                ) {
                    Text(stringResource(R.string.aimi_control_center_apply_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    ProvidePreferenceTheme {
        CompositionLocalProvider(LocalPreferences provides preferences) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AapsTopAppBar(
                        title = { Text(stringResource(R.string.aimi_control_center_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    ControlCenterIntroCard(
                        familyCount = currentSnapshot.families.size,
                        expertFamilyCount = currentSnapshot.families.count { it.status == AimiProjectionStatus.ExpertPersonalized },
                    )

                    currentSnapshot.families.forEach { family ->
                        val familyPlan = pendingChanges.familyPlan(family.id)
                        when (family.id) {
                            AimiBehaviorFamilyId.Protection -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = protectionLevelLabelForIndex(protectionLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { protectionLevel = currentDraft.protectionLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = protectionLevel,
                                        maxLevel = 4,
                                        onValueChange = { protectionLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.MealCapture -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = mealLevelLabelForIndex(mealCaptureLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { mealCaptureLevel = currentDraft.mealCaptureLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = mealCaptureLevel,
                                        maxLevel = 4,
                                        onValueChange = { mealCaptureLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.Stability -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = stabilityLevelLabelForIndex(stabilityLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { stabilityLevel = currentDraft.stabilityLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = stabilityLevel,
                                        maxLevel = 4,
                                        onValueChange = { stabilityLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.Physio -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = physioLevelLabelForIndex(physioLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { physioLevel = currentDraft.physioLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = physioLevel,
                                        maxLevel = 2,
                                        onValueChange = { physioLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.Autonomy -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = autonomyMode.labelResId,
                                pendingPlan = familyPlan,
                                onResetFamily = { autonomyMode = currentDraft.autonomyMode },
                                control = {
                                    AutonomyModeSelector(
                                        selectedMode = autonomyMode,
                                        onModeSelected = { autonomyMode = it },
                                    )
                                },
                            )
                        }
                    }

                    PendingChangesCard(
                        pendingChanges = pendingChanges,
                        onResetDraft = ::resetDraft,
                        onApplyChanges = { showApplyConfirm = true },
                    )

                    ControlSectionCard(section = currentSnapshot.contextSection)
                    ControlSectionCard(section = currentSnapshot.sourceSection)

                    Text(
                        text = stringResource(R.string.aimi_control_center_footer_v2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlCenterIntroCard(
    familyCount: Int,
    expertFamilyCount: Int,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_control_center_intro_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.aimi_control_center_intro_v2),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                ControlPill(text = stringResource(R.string.aimi_control_center_preserve_note))
                ControlPill(text = stringResource(R.string.aimi_control_center_family_count, familyCount))
                ControlPill(text = stringResource(R.string.aimi_control_center_expert_count, expertFamilyCount))
            }
        }
    }
}

@Composable
private fun AimiFamilyCard(
    snapshot: AimiBehaviorFamilySnapshot,
    targetLabelResId: Int,
    pendingPlan: AimiFamilyWritebackPlan?,
    onResetFamily: () -> Unit,
    control: @Composable () -> Unit,
) {
    var expandedCurrent by rememberSaveable(snapshot.id.name) { mutableStateOf(false) }
    Card {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(snapshot.titleResId),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(snapshot.questionResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.aimi_control_center_current_profile),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(snapshot.levelLabelResId),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                ControlPill(text = stringResource(snapshot.status.labelResId))
                ControlPill(text = stringResource(R.string.aimi_control_center_confidence, (snapshot.confidence * 100).roundToInt()))
                ControlPill(text = stringResource(R.string.aimi_control_center_settings_count, snapshot.rawPreferenceCount))
            }
            Text(
                text = stringResource(projectionStatusSummaryResId(snapshot.status)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (pendingPlan != null) {
                Text(
                    text = stringResource(R.string.aimi_control_center_target_after_apply),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(targetLabelResId),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            control()

            if (pendingPlan != null) {
                PreviewImpactCard(
                    plan = pendingPlan,
                    onResetFamily = onResetFamily,
                )
            }

            TextButton(
                onClick = { expandedCurrent = !expandedCurrent },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    text = stringResource(
                        if (expandedCurrent) R.string.aimi_control_center_hide_details
                        else R.string.aimi_control_center_show_current_details,
                    ),
                )
            }
            if (expandedCurrent) {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    snapshot.details.forEach { detail ->
                        DetailRow(detail = detail)
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyLevelSlider(
    value: Int,
    maxLevel: Int,
    onValueChange: (Int) -> Unit,
    leftAnchorResId: Int,
    rightAnchorResId: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        SliderWithButtons(
            value = value.toDouble(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, maxLevel)) },
            valueRange = 0.0..maxLevel.toDouble(),
            step = 1.0,
            showValue = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(leftAnchorResId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(rightAnchorResId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutonomyModeSelector(
    selectedMode: AimiAutonomyMode,
    onModeSelected: (AimiAutonomyMode) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
    ) {
        AimiAutonomyMode.entries.forEach { mode ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                label = { Text(stringResource(mode.labelResId)) },
            )
        }
    }
}

@Composable
private fun PreviewImpactCard(
    plan: AimiFamilyWritebackPlan,
    onResetFamily: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.aimi_control_center_preview_impacts),
                    style = MaterialTheme.typography.titleSmall,
                )
                ControlPill(
                    text = stringResource(
                        R.string.aimi_control_center_preview_changes_count,
                        plan.changes.size,
                    ),
                )
            }
            Text(
                text = stringResource(plan.currentLabelResId) + " -> " + stringResource(plan.targetLabelResId),
                style = MaterialTheme.typography.bodyMedium,
            )
            plan.noteResId?.let { noteResId ->
                Text(
                    text = stringResource(noteResId),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            plan.changes.forEach { change ->
                PreferenceChangeRow(change = change)
            }
            TextButton(
                onClick = onResetFamily,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.aimi_control_center_reset_family))
            }
        }
    }
}

@Composable
private fun PendingChangesCard(
    pendingChanges: AimiControlCenterPendingChanges,
    onResetDraft: () -> Unit,
    onApplyChanges: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_control_center_pending_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (pendingChanges.hasChanges) {
                Text(
                    text = stringResource(
                        R.string.aimi_control_center_pending_summary,
                        pendingChanges.changedFamilyCount,
                        pendingChanges.changedSettingsCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
                ) {
                    Button(
                        onClick = onApplyChanges,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.aimi_control_center_apply_changes))
                    }
                    TextButton(
                        onClick = onResetDraft,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.aimi_control_center_reset_draft))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.aimi_control_center_no_pending_changes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreferenceChangeRow(change: AimiPreferenceChange) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
        Text(
            text = stringResource(change.titleResId),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = descriptorText(change.before) + " -> " + descriptorText(change.after),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun ControlSectionCard(section: AimiControlSectionSnapshot) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(section.titleResId),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(section.summaryResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            section.details.forEach { detail ->
                DetailRow(detail = detail)
            }
        }
    }
}

@Composable
private fun DetailRow(detail: AimiControlDetail) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(detail.titleResId),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detailText(detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun detailText(detail: AimiControlDetail): String =
    when {
        detail.valueResId != null -> stringResource(detail.valueResId)
        !detail.valueText.isNullOrBlank() -> detail.valueText
        else -> stringResource(R.string.aimi_control_center_not_configured)
    }

@Composable
private fun descriptorText(value: AimiValueDescriptor): String =
    when {
        value.valueResId != null -> stringResource(value.valueResId)
        !value.valueText.isNullOrBlank() -> value.valueText
        else -> stringResource(R.string.aimi_control_center_not_configured)
    }

@Composable
private fun ControlPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.small),
        )
    }
}
