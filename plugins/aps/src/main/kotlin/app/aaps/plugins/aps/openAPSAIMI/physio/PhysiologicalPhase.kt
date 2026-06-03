package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Dominant physiological context for dosing risk (one tick).
 * See docs/AIMI_PHYSIOLOGICAL_PHASE.md.
 */
enum class PhysiologicalPhase {
    OFF,
    DAWN_CORTISOL,
    MALE_CIRCADIAN_HORMONAL,
    FEMALE_CYCLE_HORMONAL,
    STRESS_CORTISOL,
    MEAL_DECLARED,
    MEAL_UNDECLARED,
    HYPER_INSTALLED,
    ;

    val isHormonalRisk: Boolean
        get() = this == DAWN_CORTISOL ||
            this == MALE_CIRCADIAN_HORMONAL ||
            this == FEMALE_CYCLE_HORMONAL

    val isMealRisk: Boolean
        get() = this == MEAL_DECLARED || this == MEAL_UNDECLARED
}
