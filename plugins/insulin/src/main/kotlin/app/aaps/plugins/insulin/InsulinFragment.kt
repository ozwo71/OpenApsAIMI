package app.aaps.plugins.insulin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.plugins.insulin.databinding.InsulinFragmentBinding
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class InsulinFragment : DaggerFragment() {

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var config: Config
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var aapsLogger: AAPSLogger

    private var _binding: InsulinFragmentBinding? = null
    private var disposable: CompositeDisposable = CompositeDisposable()
    val MAX_INSULIN = T.days(15).msecs()
    private val concentrationConfirmed: Boolean
        get()= preferences.get(LongNonKey.LastInsulinChange) < preferences.get(LongNonKey.LastInsulinConfirmation) || currentInsulin == 100
    private val currentConcentration
        get() = preferences.get(IntNonKey.InsulinConcentration)
    private val targetConcentration
        get() = preferences.get(IntKey.InsulinRequestedConcentration)
    private val currentInsulin: Int
        get()= preferences.get(IntNonKey.InsulinConcentration)
    private val targetInsulin: Int
        get()= preferences.get(IntKey.InsulinRequestedConcentration)

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = InsulinFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

    }

    override fun onResume() {
        super.onResume()
        binding.name.text = activePlugin.activeInsulin.friendlyName
        binding.concentration.visibility = (currentConcentration != 100 || targetConcentration != 100 || config.isEngineeringMode()).toVisibility()
        binding.concentration.text = rh.gs(R.string.insulin_current_concentration, currentConcentration)
        binding.comment.text = activePlugin.activeInsulin.comment
        binding.dia.text = rh.gs(app.aaps.core.ui.R.string.dia) + ":  " + rh.gs(app.aaps.core.ui.R.string.format_hours, activePlugin.activeInsulin.dia)
        binding.graph.show(activePlugin.activeInsulin)
        binding.concentration.setOnClickListener {
            confirmationDialog()
            binding.concentration.text = rh.gs(R.string.insulin_current_concentration, currentConcentration)
        }
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTherapyEventChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
        swapAdapter()
        if (preferences.simpleMode || !config.isEngineeringMode()) { // Concentration not allowed without engineering mode or in simple mode
            preferences.put(IntKey.InsulinRequestedConcentration, 100)
        }
        aapsLogger.debug("XXXXX onResume Last Insulin Change Updated in pref ${preferences.get(LongNonKey.LastInsulinChange)}")
        aapsLogger.debug("XXXXX onResume Last Confirmation ${preferences.get(LongNonKey.LastInsulinConfirmation)}")
        aapsLogger.debug("XXXXX onResume Concentration Confirmed: $concentrationConfirmed")
    }

    fun swapAdapter() { // Launch Popup to confirm Insulin concentration on Reservoir change
        val now = System.currentTimeMillis()
        disposable += persistenceLayer
            .getTherapyEventDataFromTime(now - MAX_INSULIN,false)
            .observeOn(aapsSchedulers.main)
            .subscribe { list ->
                list.filter { te -> te.type == TE.Type.INSULIN_CHANGE }.firstOrNull()?.let {
                    preferences.put(LongNonKey.LastInsulinChange, it.timestamp)
                    if (it.timestamp >= preferences.get(LongNonKey.LastInsulinConfirmation) && !concentrationConfirmed) {
                        confirmationDialog()
                        binding.concentration.text = rh.gs(R.string.insulin_current_concentration, currentConcentration)
                    }
                }
            }
    }

    fun confirmationDialog() {
        if (currentInsulin != 100 || targetInsulin != 100)
            uiInteraction.runConcentrationDialog(childFragmentManager)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
        _binding = null
    }
}