package app.aaps.plugins.main.general.overview

import java.util.Locale

enum class MealMode(val keyword: String) {
    BFAST("bfast"),
    LUNCH("lunch"),
    DINNER("dinner"),
    SNACK("snack"),
    HIGHCARB("highcarb"),
    LOWCARB("lowcarb");

    companion object {
        fun fromSpinnerIndex(index: Int): MealMode? = entries.getOrNull(index)

        fun fromKeyword(keyword: String?): MealMode? = keyword
            ?.lowercase(Locale.ROOT)
            ?.let { key -> entries.firstOrNull { it.keyword == key } }
    }
}