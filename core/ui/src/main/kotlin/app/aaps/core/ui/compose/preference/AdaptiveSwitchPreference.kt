/*
 * Adaptive Switch Preference for Jetpack Compose
 */

package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.BooleanKeyWithChangeGuard
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceVisibilityContext
import app.aaps.core.ui.R
import app.aaps.core.ui.compose.dialogs.OkDialog

/**
 * Composable switch preference for use inside card sections.
 *
 * @param titleResId Optional title resource ID. If 0 or not provided, uses booleanKey.titleResId
 * @param summaryResId Optional summary resource ID. If null, uses booleanKey.summaryResId
 * @param visibilityContext Optional context for evaluating runtime visibility/enabled conditions
 */
@Composable
fun AdaptiveSwitchPreferenceItem(
    booleanKey: BooleanPreferenceKey,
    titleResId: Int = 0,
    summaryResId: Int? = null,
    summaryOnResId: Int? = null,
    summaryOffResId: Int? = null,
    visibilityContext: PreferenceVisibilityContext? = null
) {
    val effectiveTitleResId = if (titleResId != 0) titleResId else booleanKey.titleResId
    val effectiveSummaryResId = summaryResId ?: booleanKey.summaryResId
    val titleText = preferenceDisplayTitle(effectiveTitleResId, booleanKey.key)

    val visibility = calculatePreferenceVisibility(
        preferenceKey = booleanKey,
        engineeringModeOnly = booleanKey.engineeringModeOnly,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    val state = rememberPreferenceBooleanState(booleanKey)
    val changeGuard = (booleanKey as? BooleanKeyWithChangeGuard)?.guard

    var guardMessage by remember { mutableStateOf<String?>(null) }

    val summary: @Composable (() -> Unit)? = when {
        summaryOnResId != null && summaryOffResId != null -> {
            { Text(stringResource(if (state.value) summaryOnResId else summaryOffResId)) }
        }

        effectiveSummaryResId != null && effectiveSummaryResId != 0 -> {
            { Text(stringResource(effectiveSummaryResId)) }
        }

        else                                              -> null
    }

    if (changeGuard != null) {
        SwitchPreference(
            value = state.value,
            onValueChange = { newValue ->
                val message = changeGuard(newValue)
                if (message == null) {
                    state.value = newValue
                } else {
                    guardMessage = message
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(titleText)
                    SyncBadge(booleanKey, Modifier.padding(start = 6.dp))
                }
            },
            summary = summary,
            enabled = visibility.enabled
        )
    } else {
        SwitchPreference(
            state = state,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(titleText)
                    SyncBadge(booleanKey, Modifier.padding(start = 6.dp))
                }
            },
            summary = summary,
            enabled = visibility.enabled
        )
    }

    // Show guard rejection dialog
    guardMessage?.let { message ->
        OkDialog(
            title = stringResource(R.string.error),
            message = message,
            onDismiss = { guardMessage = null }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdaptiveSwitchPreferencePreview() {
    PreviewTheme {
        AdaptiveSwitchPreferenceItem(
            booleanKey = BooleanKey.OverviewKeepScreenOn
        )
    }
}
