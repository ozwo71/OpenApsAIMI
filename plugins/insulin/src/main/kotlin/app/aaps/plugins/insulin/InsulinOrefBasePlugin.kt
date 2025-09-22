package app.aaps.plugins.insulin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlin.math.exp
import kotlin.math.pow

/**
 * Created by adrian on 13.08.2017.
 *
 * parameters are injected from child class
 *
 */
abstract class InsulinOrefBasePlugin(
    rh: ResourceHelper,
    private val preferences: Preferences,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val persistenceLayer: PersistenceLayer,
    val profileFunction: ProfileFunction,
    val rxBus: RxBus,
    aapsLogger: AAPSLogger,
    val config: Config,
    val hardLimits: HardLimits,
    val uiInteraction: UiInteraction
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.INSULIN)
        .fragmentClass(InsulinFragment::class.java.name)
        .pluginIcon(R.drawable.ic_insulin)
        .shortName(R.string.insulin_shortname)
        .visibleByDefault(false)
        .neverVisible(config.AAPSCLIENT),
    aapsLogger, rh
), Insulin, PluginConstraints {
    val MAX_INSULIN = T.days(15).msecs()
    private val millsToThePast = T.mins(15).msecs()
    private val concentrationConfirmed: Boolean
        get()= lastInsulinChange?.let { it.timestamp < preferences.get(LongNonKey.LastInsulinConfirmation) } ?: false
    private var disposable: CompositeDisposable = CompositeDisposable()
    private var lastWarned: Long = 0
    private var lastInsulinChange: TE? = null
    override val dia
        get(): Double {
            val dia = userDefinedDia
            return if (dia >= hardLimits.minDia()) {
                dia
            } else {
                sendShortDiaNotification(dia)
                hardLimits.minDia()
            }
        }

    open fun sendShortDiaNotification(dia: Double) {
        if (System.currentTimeMillis() - lastWarned > 60 * 1000) {
            lastWarned = System.currentTimeMillis()
            uiInteraction.addNotification(Notification.SHORT_DIA, String.format(notificationPattern, dia, hardLimits.minDia()), Notification.URGENT)
        }
    }

    private val notificationPattern: String
        get() = rh.gs(R.string.dia_too_short)

    open val userDefinedDia: Double
        get() {
            val profile = profileFunction.getProfile()
            return profile?.dia ?: hardLimits.minDia()
        }

    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            val td = dia * 60 //getDIA() always >= MIN_DIA
            val tp = peak.toDouble()
            // force the IOB to 0 if over DIA hours have passed
            if (t < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
            }
        }
        return result
    }

    override val iCfg: ICfg
        get() = ICfg(friendlyName, (dia * 1000.0 * 3600.0).toLong(), T.mins(peak.toLong()).msecs())

    override val comment
        get(): String {
            var comment = commentStandardText()
            val userDia = userDefinedDia
            if (userDia < hardLimits.minDia()) {
                comment += "\n" + rh.gs(R.string.dia_too_short, userDia, hardLimits.minDia())
            }
            return comment
        }

    abstract override val peak: Int
    abstract fun commentStandardText(): String

    override fun onStart() {
        super.onStart()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateConcentration() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTherapyEventChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
        swapAdapter()
        if (preferences.simpleMode || !config.isEngineeringMode()) { // Concentration not allowed without engineering mode or in simple mode
            preferences.put(IntKey.InsulinRequestedConcentration, 100)
        }
    }

    override fun onStop() {
        super.onStop()
        disposable.clear()
    }

    override val concentration: Double
        get() = preferences.get(IntNonKey.InsulinConcentration) / 100.0

    fun updateConcentration() {
        // Todo: Compare InsulinConcentration and Requested, Check Reservoir change (in the past 15min), and include update within a confirmation popup
        // Check if Insulin Concentration is different to U100, a confirmation popup should be rised once after each Reservoir change
        preferences.put(IntNonKey.InsulinConcentration, preferences.get(IntKey.InsulinRequestedConcentration))
    }

    fun concentrationPopup() {

    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "insulin_concentration_advanced") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "insulin_settings"
            title = rh.gs(R.string.insulin_settings)
            initialExpandedChildrenCount = 0
            addConcentrationPreference(preferenceManager, context, this)
        }
    }

    fun swapAdapter() { // Launch Popup to confirm Insulin concentration on Reservoir change
        val now = System.currentTimeMillis()
        disposable += persistenceLayer
            .getTherapyEventDataFromTime(now - MAX_INSULIN,false)
            .observeOn(aapsSchedulers.main)
            .subscribe { list -> lastInsulinChange = list.filter { te -> te.type == TE.Type.INSULIN_CHANGE }.lastOrNull()
                lastInsulinChange?.timestamp?.let {
                    if (it >= now - millsToThePast && !concentrationConfirmed)
                        concentrationPopup()
                }
            }
    }

    fun addConcentrationPreference(preferenceManager: PreferenceManager, context: Context, category: PreferenceCategory) {
        category.addPreference(preferenceManager.createPreferenceScreen(context).apply {
            key = "insulin_concentration_advanced"
            title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
            addPreference(
                AdaptiveIntentPreference(
                    ctx = context,
                    intentKey = IntentKey.ApsLinkToDocs,
                    intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.insulin_concentration_doc)) },
                    summary = R.string.insulin_concentration_doc_txt
                )
            )
            val summary = if (isWithinTimeRange()) R.string.insulin_requested_concentration_summary else R.string.insulin_change_concentration_summary
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.InsulinRequestedConcentration, title = R.string.insulin_requested_concentration_title, dialogMessage = summary))
        })
    }

    fun isWithinTimeRange(): Boolean {
        val now = System.currentTimeMillis()
        persistenceLayer.getTherapyEventDataFromTime(now - millsToThePast, TE.Type.INSULIN_CHANGE,false).lastOrNull()?.apply { return true }
        return false
    }

    override fun isClosedLoopAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!config.isEngineeringMode()) {
            if (value.value()) {
                //uiInteraction.addNotification(Notification.TOAST_ALARM, rh.gs(app.aaps.plugins.constraints.R.string.closed_loop_disabled_on_dev_branch), Notification.NORMAL)
            }
            //value.set(false, rh.gs(app.aaps.plugins.constraints.R.string.closed_loop_disabled_on_dev_branch), this)
        }
        return value
    }
}