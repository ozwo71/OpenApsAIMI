package app.aaps.plugins.main.general.overview

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import app.aaps.plugins.main.R

class MealModeDialog(
    private val listener: MealModeListener
) : DialogFragment() {

    interface MealModeListener {
        fun onMealModeSelected(mode: MealMode, params: MealParameters)
    }

    private var params: MealParameters? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_meal_mode, null)

        val spinner = view.findViewById<Spinner>(R.id.meal_mode_spinner)
        val adapter = ArrayAdapter(
            android.R.layout.simple_spinner_item,
            MealMode.labels(requireContext())
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        view.findViewById<Button>(R.id.set_params_button).setOnClickListener {
            MealParametersDialog {
                params = it
            }.show(childFragmentManager, "MealParametersDialog")
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    positiveButton.setOnClickListener {
                        val mode = MealMode.fromPosition(spinner.selectedItemPosition)
                        val currentParams = params
                        when {
                            mode == null -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.meal_mode_unknown,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            currentParams == null -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.meal_mode_missing_parameters,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            else -> {
                                listener.onMealModeSelected(mode, currentParams)
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
    }
}
