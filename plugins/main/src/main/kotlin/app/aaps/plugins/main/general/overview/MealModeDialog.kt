package app.aaps.plugins.main.general.overview

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import app.aaps.plugins.main.R

class MealModeDialog(
    private val listener: MealModeListener
) : DialogFragment() {

    interface MealModeListener {
        fun onMealModeSelected(mode: String, params: MealParameters?)
    }

    private var params: MealParameters? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_meal_mode, null)

        val spinner = view.findViewById<Spinner>(R.id.meal_mode_spinner)
        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.meal_mode_entries,
            android.R.layout.simple_spinner_dropdown_item
        )
        spinner.adapter = adapter

        view.findViewById<Button>(R.id.set_params_button).setOnClickListener {
            MealParametersDialog {
                params = it
            }.show(childFragmentManager, "MealParametersDialog")
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val mode = spinner.selectedItem.toString()
                listener.onMealModeSelected(mode, params)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
