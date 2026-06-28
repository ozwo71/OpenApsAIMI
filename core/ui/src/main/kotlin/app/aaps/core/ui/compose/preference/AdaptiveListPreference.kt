/*
 * Adaptive List Preferences for Jetpack Compose
 */

package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.compose.ExcludeFromJacocoGeneratedReport

/**
 * Composable list int preference for use inside card sections.
 *
 * @param titleResId Optional title resource ID. If 0 or not provided, uses intKey.titleResId
 * @param visibilityContext Optional context for evaluating runtime visibility/enabled conditions
 */
@Composable
fun AdaptiveListIntPreferenceItem(
    intKey: IntPreferenceKey,
    titleResId: Int = 0,
    entries: List<String>,
    entryValues: List<Int>,
    visibilityContext: VisibilityContext? = null
) {
    val effectiveTitleResId = if (titleResId != 0) titleResId else intKey.titleResId
    val titleText = preferenceDisplayTitle(effectiveTitleResId, intKey.key)

    val visibility = calculatePreferenceVisibility(
        preferenceKey = intKey,
        engineeringModeOnly = intKey.engineeringModeOnly,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    val state = rememberPreferenceIntState(intKey)
    val currentValue = state.value
    val currentIndex = entryValues.indexOf(currentValue).coerceAtLeast(0)
    val currentEntry = entries.getOrElse(currentIndex) { currentValue.toString() }

    // Get dialog summary from key
    val summaryResId = intKey.summaryResId
    val dialogSummary = if (summaryResId != null && summaryResId != 0) stringResource(summaryResId) else null

    ListPreference(
        state = state,
        values = entryValues,
        title = { Text(titleText) },
        enabled = visibility.enabled,
        summary = { Text(currentEntry) },
        dialogSummary = dialogSummary,
        valueToText = { value ->
            val index = entryValues.indexOf(value)
            AnnotatedString(entries.getOrElse(index) { value.toString() })
        }
    )
}

/**
 * Composable string list preference for use inside card sections.
 *
 * @param titleResId Optional title resource ID. If 0 or not provided, uses stringKey.titleResId
 * @param visibilityContext Optional context for evaluating runtime visibility/enabled conditions
 */
@Composable
fun AdaptiveStringListPreferenceItem(
    stringKey: StringPreferenceKey,
    titleResId: Int = 0,
    entries: Map<String, String>,
    visibilityContext: VisibilityContext? = null
) {
    val effectiveTitleResId = if (titleResId != 0) titleResId else stringKey.titleResId
    val titleText = preferenceDisplayTitle(effectiveTitleResId, stringKey.key)

    val visibility = calculatePreferenceVisibility(
        preferenceKey = stringKey,
        visibilityContext = visibilityContext
    )

    if (!visibility.visible) return

    val state = rememberPreferenceStringState(stringKey)
    val currentValue = state.value
    val currentEntry = entries[currentValue] ?: currentValue
    val values = entries.keys.toList()

    // Get dialog summary from key
    val summaryResId = stringKey.summaryResId
    val dialogSummary = if (summaryResId != null && summaryResId != 0) stringResource(summaryResId) else null

    ListPreference(
        state = state,
        values = values,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titleText)
                SyncBadge(stringKey, Modifier.padding(start = 6.dp))
            }
        },
        enabled = visibility.enabled,
        summary = { Text(currentEntry) },
        dialogSummary = dialogSummary,
        valueToText = { value ->
            AnnotatedString(entries[value] ?: value)
        }
    )
}

@ExcludeFromJacocoGeneratedReport
@Preview(showBackground = true)
@Composable
private fun AdaptiveListIntPreferencePreview() {
    PreviewTheme {
        AdaptiveListIntPreferenceItem(
            intKey = IntKey.OverviewCarbsButtonIncrement1,
            entries = listOf("5g", "10g", "15g", "20g"),
            entryValues = listOf(5, 10, 15, 20)
        )
    }
}
