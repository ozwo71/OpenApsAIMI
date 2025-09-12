package app.aaps.plugins.main.general.overview

data class MealParameters(
    val maxTbr: Double,
    val prebolus1: Double,
    val prebolus2: Double,
    val reactivity: Double,
    val smbInterval: Double
)