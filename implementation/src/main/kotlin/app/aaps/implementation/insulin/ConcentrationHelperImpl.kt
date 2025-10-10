package app.aaps.implementation.insulin

import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConcentrationHelperImpl @Inject constructor(
    val aapsLogger: AAPSLogger,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val decimalFormatter: DecimalFormatter
) : ConcentrationHelper {

    override fun isU100(): Boolean = concentration == 1.0

    override fun toPump(amount: Double): Double = amount / concentration

    override fun fromPump(amount: Double): Double = amount * concentration

    override fun getProfile(): Profile? = profileFunction.getProfile()?.toPump(concentration)

    override fun basalRateString(rate: Double): String {
        if (isU100())
            return rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, rate)
        else {
            val amountString = rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, rate)
            val convertedString = rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, toPump(rate))
            return rh.gs(R.string.concentration_format, amountString, convertedString)
        }
    }

    override fun insulinAmountString(amount: Double): String {
        if (isU100())
            return decimalFormatter.toPumpSupportedBolus(amount, activePlugin.activePump.pumpDescription.bolusStep)
        else {
            val amountString = decimalFormatter.toPumpSupportedBolus(amount, activePlugin.activePump.pumpDescription.bolusStep)
            val convertedValue = decimalFormatter.toPumpSupportedBolus(toPump(amount), activePlugin.activePump.pumpDescription.bolusStep)
            return rh.gs(R.string.concentration_format, amountString, convertedValue)
        }
    }


    override fun insulinConcentrationString(): String = rh.gs(R.string.insulin_concentration, preferences.get(IntNonKey.InsulinConcentration))

    override fun bolusWithVolume(amount: Double): String = rh.gs(
        R.string.bolus_with_volume,
        decimalFormatter.toPumpSupportedBolus(amount, activePlugin.activePump.pumpDescription.bolusStep),
        amount * 10
    )

    override fun bolusWithConvertedVolume(amount: Double): String = rh.gs(
        R.string.bolus_with_volume,
        decimalFormatter.toPumpSupportedBolus(amount, activePlugin.activePump.pumpDescription.bolusStep),
        toPump(amount * 10)
    )

    override fun bolusProgress(totalAmount: Double, delivered: Double): String {
        if (isU100())
            return rh.gs(R.string.bolus_delivered_so_far, delivered, totalAmount)
        else {
            val amountString = rh.gs(R.string.bolus_delivered_so_far, delivered, totalAmount)
            val convertedString = rh.gs(R.string.bolus_delivered_so_far, fromPump(delivered), fromPump(totalAmount))
            return rh.gs(R.string.bolus_converted_delivered, convertedString, amountString)
        }
    }

    override val concentration: Double
        get() = preferences.get(IntNonKey.InsulinConcentration) / 100.0

}