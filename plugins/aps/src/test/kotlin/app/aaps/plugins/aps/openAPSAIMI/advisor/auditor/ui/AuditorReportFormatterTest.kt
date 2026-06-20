package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.content.Context
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdict
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.BoundedAdjustments
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AuditorReportFormatterTest {

  private val context: Context = mock()

  init {
    whenever(context.getString(any<Int>())).thenAnswer { "s${it.arguments[0]}" }
    whenever(context.getString(any<Int>(), anyVararg())).thenAnswer { invocation ->
      invocation.arguments.drop(1).joinToString("|")
    }
  }

  @Test
  fun formatVerdict_includesEvidenceAndAdjustments() {
    val verdict = AuditorVerdict(
      verdict = VerdictType.Soften,
      confidence = 0.82,
      degradedMode = true,
      riskFlags = listOf("hypo_risk"),
      evidence = listOf("rising delta", "IOB elevated"),
      boundedAdjustments = BoundedAdjustments(
        smbFactorClamp = 0.75,
        intervalAddMin = 3,
        preferTbr = true,
        tbrFactorClamp = 1.05,
      ),
      debugChecks = emptyList(),
    )

    val text = AuditorReportFormatter.formatVerdict(context, verdict, "OK_SOFTEN")

    assertTrue(text.contains("SOFTEN"))
    assertTrue(text.contains("82"))
    assertTrue(text.contains("OK_SOFTEN"))
    assertTrue(text.contains("rising delta"))
    assertTrue(text.contains("hypo_risk"))
    assertTrue(text.contains("0.75"))
    assertTrue(text.contains("3"))
  }

  @Test
  fun insightCount_isZeroWhenCacheEmpty() {
    assertEquals(0, AuditorReportFormatter.insightCount())
  }
}
