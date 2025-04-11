package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.Preferences
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlin.math.abs
import kotlin.math.ln

/**
 * Classe de filtre de Kalman simple permettant de lisser les mesures d'ISF.
 */
class KalmanFilter(
    var stateEstimate: Double,
    var estimationError: Double,
    var processVariance: Double,
    var measurementVariance: Double
) {
    /**
     * Mise à jour du filtre avec une nouvelle mesure.
     */
    fun update(measurement: Double): Double {
        // Étape de prédiction
        val prediction = stateEstimate
        val predictionError = estimationError + processVariance

        // Calcul du gain de Kalman
        val kalmanGain = predictionError / (predictionError + measurementVariance)

        // Mise à jour de l'estimation de l'état
        stateEstimate = prediction + kalmanGain * (measurement - prediction)
        estimationError = (1 - kalmanGain) * predictionError

        return stateEstimate
    }
}

/**
 * Calculateur de l’ISF utilisant une approche par filtre de Kalman.
 *
 * L’estimation de l’ISF se base sur une mesure brute obtenue avec une formule classique,
 * puis le filtre de Kalman lisse cette valeur pour obtenir une estimation plus stable et réactive.
 */
class KalmanISFCalculator(
    private val tddCalculator: TddCalculator,
    private val preferences: Preferences,
    private val logger: AAPSLogger
) {
    companion object {
        private const val MIN_ISF = 5.0
        private const val MAX_ISF = 300.0
        private const val BASE_CONSTANT = 75.0
        private const val SCALING_FACTOR = 1800.0 // Même facteur que dans la méthode classique
    }

    // Initialisation du filtre de Kalman
    // Les valeurs initiales (stateEstimate, estimationError, processVariance, measurementVariance)
    // seront ensuite affinées pour s'adapter au profil de chaque patient.
    private val kalmanFilter = KalmanFilter(
        stateEstimate = 20.0,     // Valeur initiale approximative (peut être issue du profil)
        estimationError = 5.0,      // Incertitude initiale sur l'estimation
        processVariance = 0.5,      // Variance du processus (évolution de l'état)
        measurementVariance = 2.0   // Variance de la mesure (peut être ajustée dynamiquement)
    )

    /**
     * Calcule un TDD effectif en combinant plusieurs moyennes sur différentes périodes.
     * La méthode simplifiée combine des moyennes sur 7 jours, 2 jours et 1 jour.
     */
    private fun computeEffectiveTDD(): Double {
        val tdd7P = preferences.get(DoubleKey.OApsAIMITDD7)
        val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount ?: tdd7P
        val tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: tdd7P

        // Combinaison pondérée des différentes périodes
        return (0.3 * tdd7D) + (0.5 * tdd2Days) + (0.2 * tddDaily)
    }

    /**
     * Calcule la mesure brute de l’ISF en fonction de la glycémie et du TDD effectif.
     * Cette méthode utilise la formule classique.
     */
    private fun computeRawISF(glucose: Double): Double {
        val effectiveTDD = computeEffectiveTDD()
        val safeTDD = if (effectiveTDD < 1.0) 1.0 else effectiveTDD // éviter la division par zéro
        val rawISF = SCALING_FACTOR / (safeTDD * ln(glucose / BASE_CONSTANT + 1))
        return rawISF.coerceIn(MIN_ISF, MAX_ISF)
    }

    /**
     * Méthode principale de calcul de l’ISF qui utilise le filtre de Kalman.
     *
     * @param glucose      Glycémie actuelle en mg/dL.
     * @param currentDelta Variation actuelle de la glycémie (optionnel).
     * @param predictedDelta Variation prédite (optionnel).
     * @return L’estimation filtrée de l’ISF.
     */
    fun calculateISF(glucose: Double, currentDelta: Double?, predictedDelta: Double?): Double {
        // 1. Calcul de la mesure brute selon la méthode classique
        val rawISF = computeRawISF(glucose)
        logger.debug(LTag.APS, "Raw ISF calculé : $rawISF pour BG = $glucose")

        // 2. Ajustement dynamique de la variance de mesure en fonction du delta
        // Par exemple, en cas de forte variation, on augmente l'incertitude sur la mesure.
        kalmanFilter.measurementVariance = when {
            currentDelta != null && abs(currentDelta) > 10 -> 4.0
            currentDelta != null && abs(currentDelta) > 5 -> 3.0
            else -> 2.0
        }

        // 3. Mise à jour du filtre de Kalman avec la mesure brute
        val filteredISF = kalmanFilter.update(rawISF).coerceIn(MIN_ISF, MAX_ISF)
        logger.debug(LTag.APS, "ISF filtré par Kalman : $filteredISF (variance de mesure = ${kalmanFilter.measurementVariance})")

        return filteredISF
    }
}
