package app.aaps.plugins.main.general.overview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import app.aaps.plugins.main.R

class MealParametersDialog(
    private val onParamsSet: (MealParameters) -> Unit
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_meal_parameters, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val maxTbr = view.findViewById<EditText>(R.id.max_tbr)
        val pre1 = view.findViewById<EditText>(R.id.prebolus_1)
        val pre2 = view.findViewById<EditText>(R.id.prebolus_2)
        val reactivity = view.findViewById<EditText>(R.id.reactivity)
        val smbInterval = view.findViewById<EditText>(R.id.smb_interval)
        val duration = view.findViewById<EditText>(R.id.duration)

        view.findViewById<View>(R.id.ok_button).setOnClickListener {
            val maxTbrValue = maxTbr.text.toString().toDoubleOrNull()
            val pre1Value = pre1.text.toString().toDoubleOrNull()
            val pre2Value = pre2.text.toString().toDoubleOrNull()
            val reactivityValue = reactivity.text.toString().toDoubleOrNull()
            val smbIntervalValue = smbInterval.text.toString().toDoubleOrNull()
            val durationValue = duration.text.toString().toIntOrNull()

            var isValid = true

            if (maxTbrValue == null) {
                maxTbr.error = getString(R.string.meal_parameters_error_required)
                isValid = false
            } else {
                maxTbr.error = null
            }

            if (pre1Value == null || pre1Value < 0.0) {
                pre1.error = getString(R.string.meal_parameters_error_prebolus)
                isValid = false
            } else {
                pre1.error = null
            }

            if (pre2Value == null || pre2Value < 0.0) {
                pre2.error = getString(R.string.meal_parameters_error_prebolus)
                isValid = false
            } else {
                pre2.error = null
            }

            if (reactivityValue == null || reactivityValue < 0.0 || reactivityValue > 2.0) {
                reactivity.error = getString(R.string.meal_parameters_error_reactivity)
                isValid = false
            } else {
                reactivity.error = null
            }

            if (smbIntervalValue == null || smbIntervalValue < 5.0) {
                smbInterval.error = getString(R.string.meal_parameters_error_smb_interval)
                isValid = false
            } else {
                smbInterval.error = null
            }

            if (durationValue == null || durationValue < 0 || durationValue > 480) {
                duration.error = getString(R.string.meal_parameters_error_duration)
                isValid = false
            } else {
                duration.error = null
            }

            if (!isValid) {
                return@setOnClickListener
            }
            val params = MealParameters(maxTbrValue,
                                        pre1Value,
                                        pre2Value,
                                        reactivityValue,
                                        smbIntervalValue,
                                        durationValue
            )
            onParamsSet(params)
            dismiss()
        }

        view.findViewById<View>(R.id.cancel_button).setOnClickListener { dismiss() }
    }
}
