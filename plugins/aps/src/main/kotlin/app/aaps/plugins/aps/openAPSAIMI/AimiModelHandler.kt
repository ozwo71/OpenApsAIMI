package app.aaps.plugins.aps.openAPSAIMI

import android.content.Context
import android.os.Environment
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class AimiModelHandler {
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val modelFile = File(externalDir, "ml/model.tflite")
    private val modelFileUAM = File(externalDir, "ml/modelUAM.tflite")
    private var interpreterUAM: Interpreter? = null
    private var interpreterMeal: Interpreter? = null

    fun initModelInterpreters(context: Context) {
        try {
            if (modelFile.exists() && interpreterMeal == null) {
                interpreterMeal = Interpreter(modelFile)
            }

            if (modelFileUAM.exists() && interpreterUAM == null) {
                interpreterUAM = Interpreter(modelFileUAM)
            }

        } catch (e: Exception) {
            Log.e("AIMI", "Erreur d'initialisation des modèles: ${e.message}")
        }
    }

    fun calculateSMBFromModel(
        hourOfDay: Int, weekend: Int,
        bg: Float, targetBg: Float, iob: Float, cob: Float, lastCarbAgeMin: Int, futureCarbs: Float,
        delta: Float, shortAvgDelta: Float, longAvgDelta: Float,
        tdd7DaysPerHour: Float, tdd2DaysPerHour: Float, tddPerHour: Float, tdd24HrsPerHour: Float,
        recentSteps5Minutes: Int, recentSteps10Minutes: Int, recentSteps15Minutes: Int,
        recentSteps30Minutes: Int, recentSteps60Minutes: Int, recentSteps180Minutes: Int
    ): Float {
        val modelInputs: FloatArray
        val interpreter: Interpreter

        return try {
            when {
                cob > 0 && lastCarbAgeMin < 240 && interpreterMeal != null -> {
                    modelInputs = floatArrayOf(
                        hourOfDay.toFloat(), weekend.toFloat(),
                        bg, targetBg, iob, cob, lastCarbAgeMin.toFloat(), futureCarbs,
                        delta, shortAvgDelta, longAvgDelta
                    )
                    interpreter = interpreterMeal!!
                }

                interpreterUAM != null -> {
                    modelInputs = floatArrayOf(
                        hourOfDay.toFloat(), weekend.toFloat(),
                        bg, targetBg, iob, delta, shortAvgDelta, longAvgDelta,
                        tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                        recentSteps5Minutes.toFloat(), recentSteps10Minutes.toFloat(), recentSteps15Minutes.toFloat(),
                        recentSteps30Minutes.toFloat(), recentSteps60Minutes.toFloat(), recentSteps180Minutes.toFloat()
                    )
                    interpreter = interpreterUAM!!
                }

                else -> return 0.0f
            }

            val output = arrayOf(floatArrayOf(0.0f))
            interpreter.run(modelInputs, output)

            val smbToGive = output[0][0]
            val formatter = DecimalFormat("#.####", DecimalFormatSymbols(Locale.US))
            formatter.format(smbToGive).toFloat()

        } catch (e: Exception) {
            Log.e("AIMI", "Erreur d'inférence TFLite: ${e.message}")
            0.0f
        }
    }

    fun releaseModelInterpreters() {
        interpreterMeal?.close()
        interpreterUAM?.close()
        interpreterMeal = null
        interpreterUAM = null
    }
}