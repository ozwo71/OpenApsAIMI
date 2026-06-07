package app.aaps.plugins.aps.openAPSAIMI.context.ui

import android.view.View
import app.aaps.plugins.aps.databinding.ItemPatientSignalGaugeBinding
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientSignalGauge

internal object PatientSignalGaugeBinder {

    fun bind(container: View, gauge: PatientSignalGauge) {
        val binding = ItemPatientSignalGaugeBinding.bind(container)
        binding.textGaugeLabel.text = "${gauge.label} ${gauge.percent}%"
        binding.progressGauge.setProgressCompat(gauge.percent, true)
    }

    fun bindAll(
        mealContainer: View,
        endogenousContainer: View,
        resistanceContainer: View,
        sensorContainer: View,
        gauges: List<PatientSignalGauge>,
    ) {
        if (gauges.size < 4) {
            return
        }
        bind(mealContainer, gauges[0])
        bind(endogenousContainer, gauges[1])
        bind(resistanceContainer, gauges[2])
        bind(sensorContainer, gauges[3])
    }
}
