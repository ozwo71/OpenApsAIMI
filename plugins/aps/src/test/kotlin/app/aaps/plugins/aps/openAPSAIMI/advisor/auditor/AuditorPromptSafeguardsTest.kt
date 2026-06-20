package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble

class AuditorPromptSafeguardsTest {

    @Test
    fun `prompt contains critical safety assertions`() {
        // Given a standard input
        val input = createDummyInput()
        
        // When building the prompt
        val prompt = AuditorPromptBuilder.buildPrompt(input)
        
        // Then it must contain critical safety sections (Audit compliance)
        
        // 1. Section Header
        assertTrue(prompt.contains("SAFETY ASSERTIONS (REQUIRED)"), "Prompt must contain Safety Assertions section")
            
        // 2. Critical Rules
        assertTrue(prompt.contains("DATA_INTEGRITY"), "Prompt must contain DATA_INTEGRITY rule for missing data")
            
        assertTrue(prompt.contains("HYPO_RULE"), "Prompt must contain HYPO_RULE for low BG safety")
        
        assertTrue(prompt.contains("STACKING_RULE"), "Prompt must contain STACKING_RULE for IOB peaks")
            
        assertTrue(prompt.contains("ANTI-HALLUCINATION"), "Prompt must contain ANTI-HALLUCINATION safeguards")
            
        // 3. Specific Bound Checks
        assertTrue(prompt.contains("< 75 mg/dL"), "Prompt must enforce strict hypo threshold (75mg/dL)")
            
        assertTrue(prompt.contains("uncertain_data"), "Prompt must allow flagging uncertain data")
            
        // 4. Instructions
        assertTrue(prompt.contains("degradedMode"), "Prompt must contain instructions on degraded mode")
    }

    @Test
    fun `prompt contains LLM World conservative preamble`() {
        val prompt = AuditorPromptBuilder.buildPrompt(createDummyInput())
        assertTrue(
            prompt.contains(LlmWorldConservativePreamble.FOR_JSON_CONTRACT.substring(0, 40)),
            "Prompt must contain LLM World JSON preamble",
        )
        assertTrue(prompt.contains("do not change output schema"), "Preamble must preserve output contract")
        assertTrue(prompt.contains("Do not recommend free insulin doses"), "Preamble must forbid dose recommendations")
    }

    private fun createDummyInput(): AuditorInput {
        return AuditorInput(
            snapshot = Snapshot(
                bg = 120.0,
                delta = 0.0,
                shortAvgDelta = 0.0,
                longAvgDelta = 0.0,
                unit = "mg/dl",
                timestamp = System.currentTimeMillis(),
                cgmAgeMin = 2,
                noise = "Clean",
                iob = 1.0,
                iobActivity = 0.1,
                cob = 0.0,
                isfProfile = 40.0,
                isfUsed = 40.0,
                ic = 10.0,
                target = 100.0,
                pkpd = PKPDSnapshot(300, 75, 0.2, true, 0.0),
                activity = ActivitySnapshot(0, 0, null, null),
                physio = null,
                states = StatesSnapshot("Normal", 10, "Idle", null, 1.0),
                limits = LimitsSnapshot(2.0, 3.0, 5.0, 2.0, null, null),
                decisionAimi = DecisionSnapshot(0.0, null, null, 5.0, emptyList()),
                lastDelivery = LastDeliverySnapshot(null, null, null, null, null, null)
            ),
            history = History(
                emptyList(), emptyList(), emptyList(), emptyList(), 
                emptyList(), emptyList(), emptyList()
            ),
            stats = Stats7d(
                80.0, 1.0, 19.0, 130.0, 30.0, 40.0, 50.0, 50.0
            ),
            trajectory = null
        )
    }
}
