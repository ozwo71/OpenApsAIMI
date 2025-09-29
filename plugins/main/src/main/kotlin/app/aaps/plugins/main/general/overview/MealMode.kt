package app.aaps.plugins.main.general.overview

import android.content.Context
import androidx.annotation.StringRes
import app.aaps.plugins.main.R

enum class MealMode(@StringRes val labelResId: Int, val noteKeyword: String) {
    SNACK(R.string.meal_mode_snack, "snack"),
    HIGH_CARB(R.string.meal_mode_high_carb, "highcarb"),
    MEAL(R.string.meal_mode_meal, "meal"),
    BREAKFAST(R.string.meal_mode_breakfast, "bfast"),
    LUNCH(R.string.meal_mode_lunch, "lunch"),
    DINNER(R.string.meal_mode_dinner, "dinner");

    companion object {
        private val orderedValues = values()

        fun fromPosition(position: Int): MealMode? = orderedValues.getOrNull(position)
        fun labels(context: Context): List<String> = orderedValues.map { context.getString(it.labelResId) }
    }
}