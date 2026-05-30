package app.aaps.plugins.aps.openAPSAIMI.scenario

/**
 * Identifies which AIMI subsystem adjusted [ScenarioProjectionEngine] output.
 * Stable ids for JSONL export and log grep.
 */
enum class ScenarioContributorId {
    PKPD_IOB_FLOOR,
    PKPD_MEAL_UPLIFT,
    PKPD_UAM_MOMENTUM,
    TRAJECTORY_RISE,
    TRAJECTORY_SPIRAL_DAMP,
    TRAJECTORY_CONVERGENCE,
    MEAL_CONTEXT,
    MEAL_ADVISOR_COB,
    ACTIVITY_PROTECTION,
    PHYSIO_REACTIVITY,
    CONTEXT_MODULE,
    TARGET_BLEND,
}
