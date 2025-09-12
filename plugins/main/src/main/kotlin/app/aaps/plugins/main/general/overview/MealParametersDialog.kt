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

        view.findViewById<View>(R.id.ok_button).setOnClickListener {
            val params = MealParameters(
                maxTbr.text.toString().toDoubleOrNull() ?: 0.0,
                pre1.text.toString().toDoubleOrNull() ?: 0.0,
                pre2.text.toString().toDoubleOrNull() ?: 0.0,
                reactivity.text.toString().toDoubleOrNull() ?: 0.0,
                smbInterval.text.toString().toDoubleOrNull() ?: 0.0
            )
            onParamsSet(params)
            dismiss()
        }

        view.findViewById<View>(R.id.cancel_button).setOnClickListener { dismiss() }
    }
}
