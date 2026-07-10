package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec

enum class BooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.SWITCH,
    override val calculatedDefaultValue: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
    override val sync: SyncSpec? = null
) : BooleanPreferenceKey {

    GeneralSimpleMode(key = "simple_mode", defaultValue = true, titleResId = R.string.pref_title_simple_mode, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    GeneralLowEndStabilityMode("general_low_end_stability_mode", false, R.string.pref_title_low_end_stability_mode),
    GeneralInsulinConcentration(
        key = "insulin_concentration_enabled", defaultValue = false, titleResId = R.string.pref_title_insulin_concentration, summaryResId = R.string.pref_summary_insulin_concentration,
        defaultedBySM = true,
        enabledCondition = PreferenceEnabledCondition { it.isConcentrationEnabled },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    OverviewKeepScreenOn(key = "keep_screen_on", defaultValue = false, titleResId = R.string.pref_title_keep_screen_on, summaryResId = R.string.pref_summary_keep_screen_on, calculatedDefaultValue = true),
    OverviewShowTreatmentButton(key = "show_treatment_button", defaultValue = false, titleResId = R.string.pref_title_show_treatment_button, defaultedBySM = true, hideParentScreenIfHidden = true),
    OverviewShowWizardButton(key = "show_wizard_button", defaultValue = true, titleResId = R.string.pref_title_show_wizard_button, defaultedBySM = true),
    OverviewShowInsulinButton(key = "show_insulin_button", defaultValue = true, titleResId = R.string.pref_title_show_insulin_button, defaultedBySM = true),
    OverviewShowCarbsButton(key = "show_carbs_button", defaultValue = true, titleResId = R.string.pref_title_show_carbs_button, defaultedBySM = true),
    OverviewShowCgmButton(key = "show_cgm_button", defaultValue = false, titleResId = R.string.pref_title_show_cgm_button, summaryResId = R.string.pref_summary_show_cgm_button, defaultedBySM = true, showInNsClientMode = false),
    /** Must read stored value in simple mode so Home can switch AIMI dashboard vs legacy overview. */
    OverviewUseDashboardLayout(
        "overview_use_dashboard", true,
        R.string.pref_title_overview_use_dashboard_layout,
        R.string.pref_summary_overview_use_dashboard_layout
    ),
    /** "Last AIMI run" summary card on the hybrid dashboard (Compose + classic). */
    OverviewShowHybridDashboardAimiPulse(
        "overview_show_hybrid_aimi_pulse", false,
        R.string.pref_title_overview_show_hybrid_aimi_pulse,
        R.string.pref_summary_overview_show_hybrid_aimi_pulse,
        defaultedBySM = true
    ),
    /** When true, hybrid dashboard shows the full two-column metrics grid + AIMI insight strip; when false, compact metric row. Must not use [defaultedBySM]: simple mode would ignore stored value via [calculatedDefaultValue] path. */
    OverviewDashboardExtendedMetrics(
        "overview_dashboard_extended_metrics", false,
        R.string.pref_title_overview_dashboard_extended_metrics,
        R.string.pref_summary_overview_dashboard_extended_metrics
    ),
    OverviewShowCalibrationButton(
        key = "show_calibration_button",
        defaultValue = false,
        titleResId = R.string.pref_title_show_calibration_button,
        summaryResId = R.string.pref_summary_show_calibration_button,
        defaultedBySM = true,
        showInNsClientMode = false
    ),
    OverviewShowNotesInDialogs(key = "show_notes_entry_dialogs", defaultValue = false, titleResId = R.string.pref_title_show_notes_in_dialogs, defaultedBySM = true),
    OverviewUseBolusAdvisor("use_bolus_advisor", true, R.string.pref_title_use_bolus_advisor, R.string.pref_summary_use_bolus_advisor, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    OverviewUseBolusReminder("use_bolus_reminder", true, R.string.pref_title_use_bolus_reminder, R.string.pref_summary_use_bolus_reminder, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),

    @Deprecated("Remove support")
    OverviewUseSuperBolus("key_usersuperbolus", false, R.string.pref_title_use_super_bolus, R.string.pref_summary_use_super_bolus, defaultedBySM = true, hideParentScreenIfHidden = true),

    PumpBtWatchdog(
        "bt_watchdog", false, R.string.pref_title_bt_watchdog, R.string.pref_summary_bt_watchdog,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    AlertMissedBgReading("enable_missed_bg_readings", false, R.string.pref_title_alert_missed_bg_reading),
    AlertPumpUnreachable("enable_pump_unreachable_alert", true, R.string.pref_title_alert_pump_unreachable),
    AlertCarbsRequired("enable_carbs_required_alert_local", true, R.string.pref_title_alert_carbs_required),
    AlertUrgentAsAndroidNotification("raise_urgent_alarms_as_android_notification", true, R.string.pref_title_alert_urgent_as_android_notification),
    AlertIncreaseVolume("gradually_increase_notification_volume", true, R.string.pref_title_alert_increase_volume),
    AlertOverrideDoNotDisturb("alert_override_dnd", true, R.string.pref_title_alert_override_dnd, R.string.pref_summary_alert_override_dnd, defaultedBySM = true),

    BgSourceUploadToNs("dexcomg5_nsupload", true, R.string.pref_title_bg_source_upload_to_ns, defaultedBySM = true, hideParentScreenIfHidden = true),
    BgSourceCreateSensorChange("dexcom_lognssensorchange", true, R.string.pref_title_bg_source_create_sensor_change, R.string.pref_summary_bg_source_create_sensor_change, defaultedBySM = true),
    BgSourceRandomBgRandomize("randombg_randomize", true, R.string.pref_title_random_bg_randomize, R.string.pref_summary_random_bg_randomize, defaultedBySM = true),

    ApsUseDynamicSensitivity("use_dynamic_sensitivity", false, R.string.pref_title_aps_use_dynamic_sensitivity, R.string.pref_summary_aps_use_dynamic_sensitivity, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsUseAutosens("openapsama_useautosens", true, R.string.pref_title_aps_use_autosens, defaultedBySM = true, negativeDependency = ApsUseDynamicSensitivity, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsUseSmb("use_smb", true, R.string.pref_title_aps_use_smb, R.string.pref_summary_aps_use_smb, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsUseSmbWithHighTt(
        "enableSMB_with_high_temptarget",
        false,
        R.string.pref_title_aps_use_smb_with_high_tt,
        R.string.pref_summary_aps_use_smb_with_high_tt,
        defaultedBySM = true,
        dependency = ApsUseSmb,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbAlways(
        "enableSMB_always", true, R.string.pref_title_aps_use_smb_always, R.string.pref_summary_aps_use_smb_always, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility.ADVANCED_FILTERING,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbWithCob(
        "enableSMB_with_COB", true, R.string.pref_title_aps_use_smb_with_cob, R.string.pref_summary_aps_use_smb_with_cob, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility { !it.preferences.get(ApsUseSmbAlways) || !it.advancedFilteringSupported },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbWithLowTt(
        "enableSMB_with_temptarget", true, R.string.pref_title_aps_use_smb_with_low_tt, R.string.pref_summary_aps_use_smb_with_low_tt, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility { !it.preferences.get(ApsUseSmbAlways) || !it.advancedFilteringSupported },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbAfterCarbs(
        "enableSMB_after_carbs", true, R.string.pref_title_aps_use_smb_after_carbs, R.string.pref_summary_aps_use_smb_after_carbs, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility { !it.preferences.get(ApsUseSmbAlways) && it.advancedFilteringSupported },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseUam("use_uam", true, R.string.pref_title_aps_use_uam, R.string.pref_summary_aps_use_uam, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsSensitivityRaisesTarget(
        "sensitivity_raises_target", true, R.string.pref_title_aps_sensitivity_raises_target, R.string.pref_summary_aps_sensitivity_raises_target, defaultedBySM = true,
        visibility = ElementVisibility {
            if (it.preferences.get(ApsUseDynamicSensitivity)) {
                it.preferences.get(ApsDynIsfAdjustSensitivity)
            } else {
                it.preferences.get(ApsUseAutosens)
            }
        },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsResistanceLowersTarget(
        "resistance_lowers_target", true, R.string.pref_title_aps_resistance_lowers_target, R.string.pref_summary_aps_resistance_lowers_target, defaultedBySM = true,
        visibility = ElementVisibility {
            if (it.preferences.get(ApsUseDynamicSensitivity)) {
                it.preferences.get(ApsDynIsfAdjustSensitivity)
            } else {
                it.preferences.get(ApsUseAutosens)
            }
        },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAlwaysUseShortDeltas(
        "always_use_shortavg",
        false,
        R.string.pref_title_aps_always_use_short_deltas,
        R.string.pref_summary_aps_always_use_short_deltas,
        defaultedBySM = true,
        hideParentScreenIfHidden = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsDynIsfAdjustSensitivity(
        "dynisf_adjust_sensitivity",
        false,
        R.string.pref_title_aps_dynisf_adjust_sensitivity,
        R.string.pref_summary_aps_dynisf_adjust_sensitivity,
        defaultedBySM = true,
        dependency = ApsUseDynamicSensitivity,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaAutosensAdjustTargets(
        "autosens_adjust_targets",
        true,
        R.string.pref_title_aps_autosens_adjust_targets,
        R.string.pref_summary_aps_autosens_adjust_targets,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfHighTtRaisesSens(
        "high_temptarget_raises_sensitivity",
        false,
        R.string.pref_title_aps_high_tt_raises_sensitivity,
        R.string.pref_summary_aps_high_tt_raises_sensitivity,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLowTtLowersSens(
        "low_temptarget_lowers_sensitivity",
        false,
        R.string.pref_title_aps_low_tt_lowers_sensitivity,
        R.string.pref_summary_aps_low_tt_lowers_sensitivity,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseAutoIsfWeights("openapsama_enable_autoISF", false, R.string.pref_title_aps_use_autoisf_weights, R.string.pref_summary_aps_use_autoisf_weights, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsAutoIsfSmbOnEvenTarget(
        "Enable alternative activation of SMB always",
        false,
        R.string.pref_title_aps_smb_on_even_target,
        R.string.pref_summary_aps_smb_on_even_target,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    MaintenanceEnableFabric("enable_fabric2", true, R.string.pref_title_maintenance_enable_fabric, defaultedBySM = true, hideParentScreenIfHidden = true),

    // Master-only (not a follower client): unattended settings export backs up the local config, which on a
    // client is derived from the master. showInNsClientMode=false hides it in apsMode + pumpControlMode only;
    // hideParentScreenIfHidden collapses the now-empty "Unattended Settings Export" subscreen on a client.
    MaintenanceEnableExportSettingsAutomation("enable_unattended_export", false, R.string.pref_title_maintenance_enable_export_automation, defaultedBySM = false, showInNsClientMode = false, hideParentScreenIfHidden = true),

    AutotuneAutoSwitchProfile("autotune_auto", false, R.string.pref_title_autotune_auto_switch_profile, R.string.pref_summary_autotune_auto_switch_profile),
    AutotuneCategorizeUamAsBasal("categorize_uam_as_basal", false, R.string.pref_title_autotune_categorize_uam_as_basal, R.string.pref_summary_autotune_categorize_uam_as_basal),
    AutotuneTuneInsulinCurve("autotune_tune_insulin_curve", false, R.string.pref_title_autotune_tune_insulin_curve),
    AutotuneCircadianIcIsf("autotune_circadian_ic_isf", false, R.string.pref_title_autotune_circadian_ic_isf, R.string.pref_summary_autotune_circadian_ic_isf),
    AutotuneAdditionalLog("autotune_additional_log", false, R.string.pref_title_autotune_additional_log),

    SmsAllowRemoteCommands("smscommunicator_remotecommandsallowed", false, R.string.pref_title_sms_allow_remote_commands),
    SmsReportPumpUnreachable("smscommunicator_report_pump_unreachable", true, R.string.pref_title_sms_report_pump_unreachable, R.string.pref_summary_sms_report_pump_unreachable),

    VirtualPumpStatusUpload("virtualpump_uploadstatus", false, R.string.pref_title_virtual_pump_status_upload, showInNsClientMode = false),
    NsClientUploadData("ns_upload", true, R.string.pref_title_ns_upload_data, R.string.pref_summary_ns_upload_data, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptCgmData("ns_receive_cgm", false, R.string.pref_title_ns_receive_cgm, R.string.pref_summary_ns_receive_cgm, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptProfileStore("ns_receive_profile_store", false, R.string.pref_title_ns_receive_profile_store, R.string.pref_summary_ns_receive_profile_store, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTempTarget("ns_receive_temp_target", false, R.string.pref_title_ns_receive_temp_target, R.string.pref_summary_ns_receive_temp_target, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptProfileSwitch("ns_receive_profile_switch", false, R.string.pref_title_ns_receive_profile_switch, R.string.pref_summary_ns_receive_profile_switch, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptInsulin("ns_receive_insulin", false, R.string.pref_title_ns_receive_insulin, R.string.pref_summary_ns_receive_insulin, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptCarbs("ns_receive_carbs", false, R.string.pref_title_ns_receive_carbs, R.string.pref_summary_ns_receive_carbs, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTherapyEvent("ns_receive_therapy_events", false, R.string.pref_title_ns_receive_therapy_event, R.string.pref_summary_ns_receive_therapy_event, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptRunningMode("ns_receive_running_mode", false, R.string.pref_title_ns_receive_running_mode, R.string.pref_summary_ns_receive_running_mode, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTbrEb("ns_receive_tbr_eb", false, R.string.pref_title_ns_receive_tbr_eb, R.string.pref_summary_ns_receive_tbr_eb, showInNsClientMode = false, engineeringModeOnly = true),
    NsClientNotificationsFromAlarms("ns_alarms", false, R.string.pref_title_ns_notifications_from_alarms, calculatedDefaultValue = true),
    NsClientNotificationsFromAnnouncements("ns_announcements", false, R.string.pref_title_ns_notifications_from_announcements, calculatedDefaultValue = true),
    NsClientUseCellular("ns_cellular", true, R.string.pref_title_ns_use_cellular),
    NsClientUseRoaming("ns_allow_roaming", true, R.string.pref_title_ns_use_roaming, dependency = NsClientUseCellular),
    NsClientUseWifi("ns_wifi", true, R.string.pref_title_ns_use_wifi),
    NsClientUseOnBattery("ns_battery", true, R.string.pref_title_ns_use_on_battery),
    NsClientUseOnCharging("ns_charging", true, R.string.pref_title_ns_use_on_charging),
    NsClientLogAppStart("ns_log_app_started_event", false, R.string.pref_title_ns_log_app_start, calculatedDefaultValue = true),
    NsClientCreateAnnouncementsFromErrors("ns_create_announcements_from_errors", false, R.string.pref_title_ns_create_announcements_from_errors, calculatedDefaultValue = true, showInNsClientMode = false),
    NsClientCreateAnnouncementsFromCarbsReq("ns_create_announcements_from_carbs_req", false, R.string.pref_title_ns_create_announcements_from_carbs_req, calculatedDefaultValue = true, showInNsClientMode = false),
    NsClientSlowSync("ns_sync_slow", false, R.string.pref_title_ns_slow_sync),
    NsClient3UseWs("ns_use_ws", true, R.string.pref_title_ns_use_ws, R.string.pref_summary_ns_use_ws),
    NsClientAllowClientControl(
        "ns_allow_client_control", false,
        R.string.pref_title_ns_allow_client_control, R.string.pref_summary_ns_allow_client_control,
        // The rich stop/allow-communication switch lives on the Authorized clients screen; it is ALSO exposed in a
        // "Remote control" category on the NSCv3 settings screen (NSClientV3Plugin.getPreferenceScreenContent) so it
        // is reachable from search. Default OFF, but ON in simple mode (resolved in PreferencesImpl.calculatedDefaultValue). Hidden on a client.
        calculatedDefaultValue = true, showInNsClientMode = false,
        // Remote control rides the WebSocket — hide the toggle (and its single-item "Remote control" parent category)
        // when WS is off, and on a client where the key is already hidden (so the category never shows empty).
        dependency = NsClient3UseWs, hideParentScreenIfHidden = true,
        // Synced master→client (MasterOnly — the client mirrors, never pushes back) so a paired client knows
        // whether the master is accepting commands and can gate its UI. buildSyncedPrefs publishes the EFFECTIVE
        // value for this key (see RunningConfigurationImpl), not the raw default.
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.MasterOnly)
    ),
    OpenHumansWifiOnly("oh_wifi_only", true, R.string.pref_title_openhumans_wifi_only),
    OpenHumansChargingOnly("oh_charging_only", false, R.string.pref_title_openhumans_charging_only),
    XdripSendStatus("xdrip_send_status", false, R.string.pref_title_xdrip_send_status),
    XdripSendDetailedIob("xdripstatus_detailediob", true, R.string.pref_title_xdrip_send_detailed_iob, R.string.pref_summary_xdrip_send_detailed_iob, defaultedBySM = true, hideParentScreenIfHidden = true),
    XdripSendBgi("xdripstatus_showbgi", true, R.string.pref_title_xdrip_send_bgi, R.string.pref_summary_xdrip_send_bgi, defaultedBySM = true, hideParentScreenIfHidden = true),
    WearControl(key = "wearcontrol", defaultValue = false, titleResId = R.string.pref_title_wear_control, summaryResId = R.string.pref_summary_wear_control),
    WearWizardBg(key = "wearwizard_bg", defaultValue = true, titleResId = R.string.pref_title_wear_wizard_bg, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardTt(key = "wearwizard_tt", defaultValue = false, titleResId = R.string.pref_title_wear_wizard_tt, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardTrend(key = "wearwizard_trend", defaultValue = false, titleResId = R.string.pref_title_wear_wizard_trend, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardCob(key = "wearwizard_cob", defaultValue = true, titleResId = R.string.pref_title_wear_wizard_cob, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardIob(key = "wearwizard_iob", defaultValue = true, titleResId = R.string.pref_title_wear_wizard_iob, dependency = WearControl, hideParentScreenIfHidden = true),
    WearCustomWatchfaceAuthorization(key = "wear_custom_watchface_autorization", defaultValue = false, titleResId = R.string.pref_title_wear_custom_watchface_authorization),
    WearNotifyOnSmb(key = "wear_notifySMB", defaultValue = true, titleResId = R.string.pref_title_wear_notify_on_smb, summaryResId = R.string.pref_summary_wear_notify_on_smb),
    WearBroadcastData(key = "wear_broadcast_data", defaultValue = false, titleResId = R.string.pref_title_wear_broadcast_data, summaryResId = R.string.pref_summary_wear_broadcast_data, showInApsMode = false, showInPumpControlMode = false),

    EversenseCloudUploadEnabled("eversense_cloud_upload_enabled", true, R.string.eversense_cloud_upload_enabled),
    EversenseCloudUploadToast("eversense_notif_cloud_upload_toast", true, R.string.eversense_cloud_upload_toast),
    SiteRotationManagePump("site_rotation_manage_pump", defaultValue = false, titleResId = R.string.pref_title_site_rotation_manage_pump, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    SiteRotationManageCgm("site_rotation_manage_cgm", defaultValue = false, titleResId = R.string.pref_title_site_rotation_manage_cgm, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),

    OApsAIMIMLtraining("key_enable_ML_training", false),
    OApsAIMIEnableBasal("key_enable_basal", false),
    OApsAIMIEnableStepsFromWatch("count_steps_watch", false),
    OApsAIMIpregnancy("key_use_AimiPregnancy",false),
    OApsAIMIforcelimits("key_use_AimiForceLimits",false),
    OApsAIMInight("OApsAIMI_Enable_night",false),
    OApsAIMIhoneymoon("key_use_Aimi_honeymoon",false),
    OApsxdriponeminute(key = "key_use_Aimi_xdripOM",defaultValue = false),
    OApsAIMIT3cAdaptiveBasalEnabled("key_use_aimi_t3c_adaptive_basal", false),
    OApsAIMIAutodriveV3EnhancedGater("key_use_aimi_autodrive_v3_enhanced_gater", false),
    OApsAIMIautoDriveActive(key = "key_use_aimi_autodrive_active", defaultValue = true),
    /**
     * Opt-in: on an aggressive rise, let Autodrive V3 deliver at least the user-defined prebolus
     * amount ([DoubleKey.OApsAIMIautodrivePrebolus] / [DoubleKey.OApsAIMIautodrivesmallPrebolus]),
     * as a floor on top of the model SMB. Always re-bounded by V3 safety (post-hypo, maxSMB, IOB,
     * correction aggression). Absorbs the former classic-autodrive aggressive-rise SMB.
     */
    OApsAIMIautodriveAggressiveSmbFloor(
        key = "key_aimi_autodrive_aggressive_smb_floor",
        defaultValue = false,
        titleResId = R.string.pref_title_aimi_autodrive_aggressive_smb_floor,
        summaryResId = R.string.pref_summary_aimi_autodrive_aggressive_smb_floor,
        dependency = OApsAIMIautoDriveActive,
    ),
    /**
     * Opt-in: sensor-driven effort protection. Caps SMB when steps/HR indicate current or recent
     * physical effort, independent of any declared AIMI Context activity intent. Reduction-only
     * (fail-safe); never reduces under a stress posture. See docs/AIMI_ARCHITECTURE_MAP.md §11.
     */
    OApsAIMIEffortActivityProtection(
        key = "key_aimi_effort_activity_protection",
        defaultValue = true,
        titleResId = R.string.pref_title_aimi_effort_activity_protection,
        summaryResId = R.string.pref_summary_aimi_effort_activity_protection,
    ),
    /**
     * When Autodrive V3 applies a safe command, skip the legacy MPC/PI blender so V3 safety
     * (night cap, post-hypo, weight-aware limits) is not overwritten.
     */
    OApsAIMIautoDriveAuthoritative(
        key = "key_aimi_autodrive_v3_authoritative",
        defaultValue = true,
        titleResId = R.string.pref_title_aimi_autodrive_v3_authoritative,
        summaryResId = R.string.pref_summary_aimi_autodrive_v3_authoritative,
        dependency = OApsAIMIautoDriveActive,
    ),
    OApsAIMIwcycle(key = "key_use_Aimi_wcycle",defaultValue = false),
    OApsAIMIWCycleShadow("key_use_Aimi_wcycle_shadow", false),
    OApsAIMIWCycleRequireConfirm("key_use_Aimi_wcycle_require_confirm", false),
    OApsAIMINightGrowthEnabled("key_oaps_aimi_ngr_enabled", true),
    OApsAIMIPkpdEnabled("key_aimi_pkpd_enabled", false),
    /** Set after the guided PK/PD setup wizard completes (or is skipped). */
    OApsAIMIPkpdSetupWizardCompleted("key_aimi_pkpd_setup_wizard_completed", false),
    OApsAIMIPeakGovernorEnabled(
        key = "key_aimi_peak_governor_enabled",
        defaultValue = true,
        titleResId = R.string.pref_title_aimi_peak_governor_enabled,
        summaryResId = R.string.pref_summary_aimi_peak_governor_enabled,
    ),
    OApsAIMIPkpdPragmaticReliefEnabled("key_aimi_pkpd_pragmatic_relief_enabled", true),
    /** When false, AIMI stops writing loop_blackbox_v1.jsonl only; hormonitor event/daily streams unchanged. */
    OApsAIMILoopBlackboxFileEnabled("key_aimi_loop_blackbox_file_enabled", true),
    /**
     * When true, AIMI determine_basal runs under a ReentrantLock so overlapping invocations cannot corrupt state.
     * Disable only for isolated tests/benchmarks that intentionally nest calls.
     */
    OApsAIMILoopExclusiveInvocationEnabled("key_aimi_loop_exclusive_invocation", true),
    /**
     * When true, runs the AIMI vs OpenAPS SMB counterfactual comparator (extra SMB pass + CSV).
     * Default off to avoid cost on hot paths; enable for R&D or divergence analysis.
     */
    OApsAIMIAimiSmbComparatorEnabled("key_aimi_smb_comparator_enabled", false),
    /** Plateau + meaningful IOB + falling prediction → throttle SMB, bias TBR, no Red Carpet restore. */
    OApsAIMIIobSurveillanceGuard("key_aimi_iob_surveillance_guard", true),
    /**
     * When true, scenario projection + trajectory can lift Autodrive V3 SMB on credible hyper rise
     * (see docs/AIMI_HYPER_TRAJECTORY_RELEASE.md).
     */
    OApsAIMIHyperTrajectoryRelease(
        key = "key_aimi_hyper_trajectory_release",
        defaultValue = true,
        titleResId = R.string.pref_title_aimi_hyper_trajectory_release,
        summaryResId = R.string.pref_summary_aimi_hyper_trajectory_release,
        dependency = OApsAIMIautoDriveActive,
    ),
    OApsAIMIHyperTrajectoryReleaseAggressive(
        key = "key_aimi_hyper_trajectory_release_aggressive",
        defaultValue = false,
        titleResId = R.string.pref_title_aimi_hyper_trajectory_release_aggressive,
        summaryResId = R.string.pref_summary_aimi_hyper_trajectory_release_aggressive,
        dependency = OApsAIMIHyperTrajectoryRelease,
    ),
    /**
     * Recursive Belief Tree — export unfold to AIMI_Decisions.jsonl (shadow). See docs/AIMI_RECURSIVE_BELIEF.md.
     */
    OApsAIMIRecursiveBeliefShadow(
        key = "key_aimi_recursive_belief_shadow",
        defaultValue = true,
        titleResId = R.string.pref_title_aimi_recursive_belief_shadow,
        summaryResId = R.string.pref_summary_aimi_recursive_belief_shadow,
        dependency = OApsAIMIautoDriveActive,
    ),
    /**
     * When on (requires autodrive active), RBT [DoseChannelResolution] drives SMB floor / Traj-Bridge suppression live.
     * Independent of [OApsAIMIRecursiveBeliefShadow] (JSONL export + SHADOW_* leaves only).
     */
    OApsAIMIRecursiveBeliefAuthority(
        key = "key_aimi_recursive_belief_authority",
        defaultValue = true,
        titleResId = R.string.pref_title_aimi_recursive_belief_authority,
        summaryResId = R.string.pref_summary_aimi_recursive_belief_authority,
        dependency = OApsAIMIautoDriveActive,
    ),
    OApsAIMIRecursiveBeliefWavelet(
        key = "key_aimi_recursive_belief_wavelet",
        defaultValue = false,
        titleResId = R.string.pref_title_aimi_recursive_belief_wavelet,
        summaryResId = R.string.pref_summary_aimi_recursive_belief_wavelet,
        dependency = OApsAIMIautoDriveActive,
    ),
    /**
     * Trajectory-informed bounded tweak to DynISF (AutoISF-style CGM geometry). Requires dynamic sensitivity.
     * Default off: enable after monitoring shadow logs.
     */
    OApsAIMIDynIsfTrajectoryTuningEnabled(
        key = "aimi_dyn_isf_trajectory_tuning_enabled",
        defaultValue = false,
        titleResId = R.string.pref_title_aimi_dyn_isf_trajectory_tuning,
        summaryResId = R.string.pref_summary_aimi_dyn_isf_trajectory_tuning,
        dependency = ApsUseDynamicSensitivity,
    ),
    /**
     * When trajectory tuning is enabled: log the would-be ISF multiplier but do not apply it.
     * Default on for safe rollout; set false to apply the bounded adjustment.
     */
    OApsAIMIDynIsfTrajectoryShadowOnly(
        key = "aimi_dyn_isf_trajectory_shadow_only",
        defaultValue = true,
        titleResId = R.string.pref_title_aimi_dyn_isf_trajectory_shadow,
        summaryResId = R.string.pref_summary_aimi_dyn_isf_trajectory_shadow,
        dependency = OApsAIMIDynIsfTrajectoryTuningEnabled,
    ),
    OApsAIMIUnifiedReactivityEnabled("key_use_unified_reactivity", true),  // 🎯 NEW: Enable UnifiedReactivityLearner
    AimiAuditorEnabled("aimi_auditor_enabled", false),  // 🧠 AI Decision Auditor
    OApsAIMITrajectoryGuardEnabled("key_aimi_trajectory_guard_enabled", false),  // 🌀 Phase-Space Trajectory Control
    /** Discrete tube + straight-command regularizer on max SMB (uses PKPD min-pred curve). Off by default. */
    OApsAIMIStraightLineTubeAdvisorEnabled(
        key = "key_aimi_straight_line_tube_enabled",
        defaultValue = false,
        titleResId = R.string.pref_title_aimi_straight_line_tube,
        summaryResId = R.string.pref_summary_aimi_straight_line_tube,
    ),
    OApsAIMIContextEnabled("key_aimi_context_enabled", false),  // 🎯 Context Module
    // 🩸 Anti-whiplash: limit upward basal rate-of-change per tick
    OApsAIMIBasalSlewLimitEnabled(
        "key_aimi_basal_slew_limit", true,
        titleResId = R.string.pref_title_aimi_basal_slew_limit,
        summaryResId = R.string.pref_summary_aimi_basal_slew_limit,
    ),
    // 🩸 pkpd hybrid/eventual: EGP reversion off the absorbing 39 floor
    OApsAIMIPkpdEndogenousReversion(
        "key_aimi_pkpd_endo_reversion", true,
        titleResId = R.string.pref_title_aimi_pkpd_endo_reversion,
        summaryResId = R.string.pref_summary_aimi_pkpd_endo_reversion,
    ),
    // 🩸 pkpd predictions: shape the insulin-activity curves on the LEARNED DIA/peak, not the static profile
    OApsAIMIPkpdPredictionKinetics(
        "key_aimi_pkpd_prediction_kinetics", true,
        titleResId = R.string.pref_title_aimi_pkpd_prediction_kinetics,
        summaryResId = R.string.pref_summary_aimi_pkpd_prediction_kinetics,
    ),
    OApsAIMIContextLLMEnabled("key_aimi_context_llm_enabled", false),  // 🤖 LLM-powered context parsing
    OApsAIMIT3cBrittleMode("key_aimi_t3c_brittle_mode", false),
    /** Cystic fibrosis-related diabetes (CFRD) adaptations in T3C mode:
     *  higher LGS safety floor, COB absorption delay, exacerbation support. */
    OApsAIMIT3cCfrdMode("key_aimi_t3c_cfrd_mode", false),
    /** CFRD manual exacerbation flag: raises the T3C aggressiveness ceiling during
     *  active pulmonary exacerbations or corticosteroid (steroid) treatment. */
    OApsAIMIT3cCfrdExacerbationMode("key_aimi_t3c_cfrd_exacerbation", false),

    /** Undeclared-meal COB estimation: derives a bounded virtual COB (grams) from the glucose
     *  appearance rate, BG dynamics, weight/TDD ceiling and rest/activity context, then injects it
     *  into the prediction path so basal (TBR) anticipates a meal that was not declared. TBR-only:
     *  it never adds autonomous SMB, and is muted by exercise / hypo / exacerbation / false-meal
     *  suppression. Off by default. */
    OApsAIMIUndeclaredCobEnabled(
        "key_aimi_undeclared_cob_enabled", false,
        titleResId = R.string.pref_title_aimi_undeclared_cob,
        summaryResId = R.string.pref_summary_aimi_undeclared_cob,
    ),


    // 🦋 Thyroid / Basedow Module (MTR)
    OApsAIMIThyroidEnabled("key_aimi_thyroid_enabled", false),
    OApsAIMIThyroidLogVerbosity("key_aimi_thyroid_debug", false),

    // 🏥 AIMI Physiological Assistant (MTR)
    AimiPhysioAssistantEnable("aimi_physio_assistant_enable", false),
    AimiPhysioSleepDataEnable("aimi_physio_sleep_enable", true),
    AimiPhysioHRVDataEnable("aimi_physio_hrv_enable", true),
    AimiPhysioLLMAnalysisEnable("aimi_physio_llm_enable", false),
    AimiPhysioDebugLogs("aimi_physio_debug_logs", false),

    // 🌸 Endometriosis & Cycle Management (MTR)
    AimiEndometriosisEnable("aimi_endo_enable", false),
    AimiEndometriosisHormonalSuppression("aimi_endo_suppression", false),
    AimiEndometriosisPainFlare("aimi_endo_flare", false),
    OApsAIMIMealAdvisorTrigger("aimi_meal_advisor_trigger", false), // Trigger for one-shot MAX-SMB bypass

    // 🌀 Adaptive Kernel Bank (Cosine Gate)
    AimiCosineGateEnabled("aimi_cosine_gate_enabled", true),

    // 🚨 Emergency SOS (Hypo)
    AimiEmergencySosEnable("aimi_emergency_sos_enable", false),

    /** On-device MLP risk models for AIMI Advisor (OREF features); trains when Advisor runs if enough rows. */
    OApsAIMIAdvisorPersonalOrefMl(
        "key_aimi_advisor_personal_oref_ml",
        false,
        R.string.pref_title_aimi_advisor_personal_oref_ml,
        R.string.pref_summary_aimi_advisor_personal_oref_ml,
    ),
    /** When true, AI Coach prompt includes structured user-insight block from OREF (easier plain-language coaching). */
    OApsAIMIAdvisorLlmRichOref(
        "key_aimi_advisor_llm_rich_oref",
        true,
        R.string.pref_title_aimi_advisor_llm_rich_oref,
        R.string.pref_summary_aimi_advisor_llm_rich_oref,
    ),
    /** Transient Preference Overlay — temporary protection prefs (2 h). */
    OApsAIMITpoEnabled(
        "key_aimi_tpo_enabled",
        true,
        R.string.pref_title_aimi_tpo_enabled,
        R.string.pref_summary_aimi_tpo_enabled,
    ),
    /** Require LLM confirmation before TPO apply (when API key available). */
    OApsAIMITpoLlmConfirmEnabled(
        "key_aimi_tpo_llm_confirm_enabled",
        true,
        R.string.pref_title_aimi_tpo_llm_confirm_enabled,
        R.string.pref_summary_aimi_tpo_llm_confirm_enabled,
        dependency = OApsAIMITpoEnabled,
    ),
    /** Show notification when a TPO session starts (reserved for notification UX). */
    OApsAIMITpoNotifyOnApply(
        "key_aimi_tpo_notify_on_apply",
        true,
        R.string.pref_title_aimi_tpo_notify_on_apply,
        R.string.pref_summary_aimi_tpo_notify_on_apply,
        dependency = OApsAIMITpoEnabled,
    ),
}


