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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R

@Composable
fun AimiControlCenterScreen(
    preferences: Preferences,
    onBack: () -> Unit,
) {
    val snapshot = buildAimiControlCenterSnapshot(preferences)

    ProvidePreferenceTheme {
        CompositionLocalProvider(LocalPreferences provides preferences) {
            Scaffold(
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
                        familyCount = snapshot.families.size,
                        expertFamilyCount = snapshot.families.count { it.status == AimiProjectionStatus.ExpertPersonalized },
                    )

                    snapshot.families.forEach { family ->
                        AimiFamilyCard(snapshot = family)
                    }

                    ControlSectionCard(section = snapshot.contextSection)
                    ControlSectionCard(section = snapshot.sourceSection)

                    Text(
                        text = stringResource(R.string.aimi_control_center_footer),
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
                text = stringResource(R.string.aimi_control_center_intro),
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
private fun AimiFamilyCard(snapshot: AimiBehaviorFamilySnapshot) {
    var expanded by rememberSaveable(snapshot.titleResId) { mutableStateOf(false) }
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
                text = stringResource(snapshot.levelLabelResId),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(
                progress = { snapshot.normalizedScore },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(snapshot.leftAnchorResId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(snapshot.rightAnchorResId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                ControlPill(text = stringResource(snapshot.status.labelResId))
                ControlPill(text = stringResource(R.string.aimi_control_center_confidence, (snapshot.confidence * 100).toInt()))
                ControlPill(text = stringResource(R.string.aimi_control_center_settings_count, snapshot.rawPreferenceCount))
            }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.aimi_control_center_hide_details
                        else R.string.aimi_control_center_show_details,
                    ),
                )
            }
            if (expanded) {
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
            text = detailValue(detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun detailValue(detail: AimiControlDetail): String =
    when {
        detail.valueResId != null -> stringResource(detail.valueResId)
        !detail.valueText.isNullOrBlank() -> detail.valueText
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
