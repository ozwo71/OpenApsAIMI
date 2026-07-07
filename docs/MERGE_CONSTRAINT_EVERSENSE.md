# Merge constraint: Eversense (CAPTCG patches / native CGM)

Purpose: preserve **Eversense E3 / E365 native CGM** integration when merging `dev` (Nightscout AndroidAPS) or rebasing this fork. Treat this as a **first-class merge constraint** alongside AIMI, smoothing, and dashboard paths.

Upstream reference repository: [CAPTCG/AndroidAPS-Eversense-](https://github.com/CAPTCG/AndroidAPS-Eversense-) (patch series + README). Related upstream discussion: [AndroidAPS PR #4474](https://github.com/nightscout/AndroidAPS/pull/4474).

## OpenApsAIMI — integration log (native plugin)

The **CAPTCG patch series 0001–0005** was applied on `dev_OAPSAIMI` (with resolutions below). After this point, merges from Nightscout `dev` must **keep** `plugins:eversense`, DI registrations, `SourceSensor` **EVERSENSE_E3** / **EVERSENSE_365**, DB converters, `EversensePlugin`, and the Eversense preference strings in `core/keys`.

### Merge `dev` → `dev_OAPSAIMI_mergeDEV` (2026-05-20)

- Upstream Nightscout `dev` at `d1e22496f4` (AutoISF settings PR #4882, HiltWorker migration, DB `useWriterConnection`, scene UI).
- **Conflicts resolved (combine, not theirs-only):**
  - **AIMI / physio:** `AndroidManifest.xml` — Health Connect activities + WorkManager Hilt init + FGS `dataSync`.
  - **Dashboard:** `MainScreen.kt` — kept `dashboardOverview` skin switch + upstream `activeSceneState` / `onIobChipClick`.
  - **Hilt workers:** all `plugins/*` `build.gradle.kts` — `ksp(androidx.hilt.compiler)` + fork ONNX/Truth on `:plugins:aps`.
  - **NS AIMI context:** `NSClientAddUpdateWorker` — `@HiltWorker` + `ContextManager` inject from NS.
  - **Wear:** `WearPlugin.kt` — `collectResilient` + kept `throttleFirst` on loop/autosens resend.
  - **DB:** `AppRepository.cleanupDatabase` — dev PRAGMA API + fork `runVacuum` → `vacuumDatabase()`.
  - **Eversense:** `SourceModule.kt` — Eversense DI activities kept; BG workers = Hilt only (no duplicate `ContributesAndroidInjector`).
- **Fork preserved:** `Versions.appVersion` AIMI suffix, ML model copy paths in `MainApp`, hormonitor export in `DetermineBasalAIMI2`, adaptive smoothing `calibratedOrValue`, `:plugins:eversense` in `settings.gradle`.
- **Post-merge verify (user):** smoke per [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) §4.

### CAPTCG sync 0006–0013 (2026-06-05)

Full port of [CAPTCG/AndroidAPS-Eversense-](https://github.com/CAPTCG/AndroidAPS-Eversense-) combined patch (post-0005 quality + pattern alignment):

| Change | Notes |
|--------|--------|
| Package rename | `com.nightscout.eversense` → `app.aaps.plugins.eversense` (152 files) |
| BLE reliability | `submitToExecutorAndSync`, 365 status-19 `gatt.connect()`, persistent 60s reconnect, E3 clock sync on KeepAlive |
| 365 never-disconnect | `SetBleDisconnect365Packet(0)` |
| Calibration | Post-cal `WAITING_POST_CALIBRATION` + readiness re-read on E3 |
| Credentials | `username`/`password` on plugin, `syncCredentialsIfNeeded()`, token cache only on change |
| Build / Sonar | `:core:interfaces` dep, manifest `usesCleartextTraffic=false` |
| Battery % | `GetBatteryPercentagePacket` via `BatteryLevel` enum (kept % mapping, not raw 0–11) |

**Fork kept:** Calibration activity readiness UI (CAPTCG strips it; we keep user-facing readiness text).

### Merge `dev` → `dev_OAPSAIMI` (2026-07-07)

- Upstream Nightscout `dev` at `b45fb221e8` (12 commits: Wear scene tiles settings + color themes,
  TT rounding fix `getRoundedTargetMgdl`/`apsAdjustedTargetMgdl`, BgQuality doubled-BG → LGS mode,
  Tidepool upload ID, Equil fixes, UKF/Diaconn/Profile tests). **Zero conflicts.**
- **No Eversense / AIMI / SMB / autoISF file in upstream diff** — `settings.gradle`, `SourceSensor`,
  DI, `AppRepository`, `KeepAliveWorker`, `AndroidManifest` untouched; constraint satisfied without
  re-application. Nothing to port into AIMI plugin logic.
- **Ported to fork-only file:** `OverviewFragment.kt` (legacy overview, empty upstream) — TT rounding
  fix via `apsAdjustedTargetMgdl` (compose overview/widget/wear were migrated upstream).
- **Test adaptations:** new upstream `UnscentedKalmanFilterPluginTest` bridged to fork suspend
  `smooth()`; pre-existing constraints test drift fixed (`AutosensDataStore` import, suspend
  `isAdvancedFilteringEnabled`, `ObjectivesViewModel` `config` param).
- **Fork preserved:** AIMI/hormonitor export, Eversense module + `SourceSensor` E3/365, dashboard
  skin switch, ML `storageHelper`, adaptive smoothing `calibratedOrValue`, `KeepAliveWorker
  runVacuum=false`, `AppRepository` guard, physio HC manifest. `:app` + `:wear` compile **PASS**;
  smoothing/objects/constraints + AIMI unit tests **PASS**.
- Log: [MERGE_DEV_2026-07-07.md](MERGE_DEV_2026-07-07.md).

### Merge `dev` → `dev_OAPSAIMI` (2026-07-05)

- Upstream Nightscout `dev` at `2e09122620` (41 commits: Wear bolus-wizard correction, Plugin
  enable/disable from search, Wizard alarm fix, dependabot compose-bom/sqlite/hilt/wear, Crowdin, tests).
- **No Eversense / AIMI / SMB / autoISF file in upstream diff** — `settings.gradle`, `SourceSensor`,
  `PluginsListModule`, DI, `AppRepository`, `KeepAliveWorker`, `AndroidManifest` untouched; constraint
  satisfied without re-application. Nothing to port into AIMI.
- **Conflicts (1, not Eversense):** `MainScreen.kt` — combined fork `dashboardOverview` skin switch +
  embedded-dashboard UI with upstream "plugin enable/disable from search" (5 params, `SearchResults`
  `revision`/`onPluginToggle`, plugin/hardware confirmation dialogs).
- **Fork preserved:** AIMI contexts/hormonitor export + recent fork work (prebolus A+B, activity basal
  cap), Eversense `SourceSensor` E3/365 + `settings.gradle`, dashboard skin, ML `storageHelper`,
  adaptive smoothing `calibratedOrValue`, `KeepAliveWorker runVacuum=false`, `AppRepository` guard,
  physio HC manifest, AIMI docs. `:app:compileFullDebugKotlin` **PASS**; AIMI unit tests **PASS**.
- Log: [MERGE_DEV_2026-07-05.md](MERGE_DEV_2026-07-05.md).

### Merge `dev` → `dev_OAPSAIMI` (2026-07-01)

- Upstream Nightscout `dev` at `79c3d7bdce` (28 commits: Tidepool mode/TBR fixes, SetupWizard
  Master-Client, QuickWizard, client-master UI + SiteRotation client control + remote-control search,
  insulin concentration fixes, profile-edit save, statistics, Dana bolus-block, gradle/junit).
- **No Eversense / AIMI / SMB / autoISF file in upstream diff** — `settings.gradle`, `SourceSensor`,
  `PluginsListModule`, DI, `AppRepository`, `KeepAliveWorker`, `AndroidManifest` untouched; constraint
  satisfied without re-application. Nothing to port into AIMI.
- **Conflicts (4, none Eversense):** `BooleanKey.kt` (fork Eversense keys kept + SiteRotation `sync`
  from upstream), `IntKey.kt` (upstream `SiteRotationUserProfile` LIST/entries/sync + fork AIMI keys),
  `MainApp.kt` (both `storageHelper` + `profileSwitchExpiryScheduler`), `RunningModeManagementViewModel.kt`
  (upstream `ControlDisabled` + `failTextResId`, no fork logic).
- **Fork preserved:** AIMI contexts/effort/hormonitor export, Eversense `SourceSensor` E3/365 +
  `settings.gradle`, dashboard skin (`MainScreen`), ML `storageHelper`, adaptive smoothing
  `calibratedOrValue`, `KeepAliveWorker runVacuum=false`, `AppRepository` guard, physio HC manifest.
  `:app:compileFullDebugKotlin` **PASS**; AIMI unit tests **PASS**.
- Log: [MERGE_DEV_2026-07-01.md](MERGE_DEV_2026-07-01.md).

### Merge `dev` → `dev_OAPSAIMI` (2026-06-28)

- Upstream Nightscout `dev` at `989065e9b4` (30 commits: Crowdin, QuickWizard/QuickLaunch TT presets, AAPSClient HTTP 410 / remote-error dialogs / unpaired visibility, Medtrum bolus tracking, dagger 2.60, mmol constant move to `Constants`).
- **No Eversense file in upstream diff** — `settings.gradle`, `SourceSensor`, `PluginsListModule`, DI untouched; constraint satisfied without re-application.
- **Conflicts (10, none Eversense):** `MainScreen.kt` (kept `dashboardOverview` skin + ported `masterOrPairedClient`), `ElementType.kt` (theirs visibility migration + fork `AFREZZA`), 4× core/ui preference (ours + `PreferenceVisibilityContext`→`VisibilityContext` API migration), 3× strings.xml (ours), `AboutDialog.kt` (both imports).
- **Transitive fix:** `GraphViewModel.kt` `GlucoseUnit.MGDL_TO_MMOLL` → `Constants.MGDL_TO_MMOLL` (upstream removed the `GlucoseUnit` constants).
- **Fork preserved:** AIMI, hormonitor, adaptive smoothing `calibratedOrValue`, dashboard skin, physio HC manifest, ML storage, Eversense module, `KeepAliveWorker runVacuum=false`. Build `:app:assembleFullDebug` **PASS**.
- Log: [MERGE_DEV_2026-06-28.md](MERGE_DEV_2026-06-28.md).

### Merge `dev` → `dev_OAPSAIMI` (2026-06-24)

- Upstream Nightscout `dev` at `42ea25d792` (3 commits since `6436a9556f`: rounding fix, Equil command-drop fix #4910, dev3 merge).
- **Clean merge** — no conflicts. `EquilBLE.kt` auto-merged (fork `cancelPendingConnect` + upstream `writeCmd` ready path).
- **Fork preserved:** AIMI, hormonitor, adaptive smoothing, dashboard skin, physio HC, ML storage, Eversense module, `KeepAliveWorker runVacuum=false`.
- Log: [MERGE_DEV_2026-06-24.md](MERGE_DEV_2026-06-24.md).

### Merge `dev` → `dev_OAPSAIMI_mergeDEV` (2026-06-05)

- Upstream Nightscout `dev` at `496d3275f4` (alarm unification, Vico 3.2.2, empty-graph fix, Google Drive export).
- **Conflicts resolved (combine, not theirs-only):**
  - **Notifications:** `NotificationId.kt` — upstream enum + fork Eversense / AIMI / permission ids.
  - **Dashboard graphs:** `BgGraphCompose.kt`, `GraphUtils.kt`, `GraphViewModel.kt` — fork dashboard features + dev empty-data fix; removed obsolete `CobGraphCompose` / `IobGraphCompose`.
  - **Alarms:** `OverviewFragment.kt` + `UiInteractionImpl.kt` — `IMPORTANT` tier + `AlarmSoundPlayer` path for legacy overview sound notifications.
- **Fork preserved:** AIMI/hormonitor/pattern export, adaptive smoothing, dashboard skin switch, physio HC manifest, ML storage permissions, Eversense module, `KeepAliveWorker runVacuum=false`.
- **Post-merge verify (user):** smoke per [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) §4.

### Merge `dev` → `dev_OAPSAIMI_mergeDEV` (2026-05-31)

- Upstream commits through `d6e06f0087` (Data receive fix, calibration UI, jacoco/Compose tests).
- **Clean merge** (no conflicts). Eversense / AIMI / dashboard paths untouched in merge diff.
- Post-merge fork fixes: `AdaptiveSmoothingPlugin` → `calibratedOrValue`; `ApsResultExportWorker` drain-before-gate (parity Dexcom/xDrip).
- Smoke: xDrip/Dexcom inbox, calibration screen buttons, dashboard ↔ classic overview skin.

### Merge resolutions performed

1. **0003 — `core/keys/src/main/res/values/strings.xml`**  
   Conflict with fork-only AIMI preference title strings. Resolution: **keep all AIMI strings** and **append** the three Eversense keys (`eversense_use_smoothing`, `eversense_cloud_upload_enabled`, `eversense_cloud_upload_toast`).

2. **0004 — UI patch failed `git am` (corrupt index)**  
   The published `0004.patch` contains an invalid `index` line for `ui/src/main/res/values-vi-rVN/strings.xml` (`45eeff98998e`), so Git cannot build a fake ancestor. Resolution: **manually** applied the same intent as the patch:
   - `StatusViewModel`: sensor status light `compactLevel = true` (show transmitter/sensor battery when the source exposes it).
   - `dexcom_tir` string resources → Eversense-oriented wording in the locales touched by the patch (plus matching updates where the fork had divergent copy).  
   If you re-download patches from CAPTCG, consider fixing the `index` line in `0004` or keep this doc as the procedure when `git am` fails on 0004.

3. **0005**  
   Applied cleanly against this fork (`ExternalOptions.ENABLE_OMNIPOD_DRIFT_COMPENSATION` remains in `Config.kt` here).

### Local patch storage

Upstream `.patch` files are stored under `eversense_upstream_patches/` (gitignored) for re-application or diff review; re-fetch from GitHub if missing.

### CAPTCG sync (2026-05-17, commit `3da9a02`)

Targeted port from [CAPTCG/AndroidAPS-Eversense-](https://github.com/CAPTCG/AndroidAPS-Eversense-) (not a full `git am` — fork has AIMI/dashboard deltas):

| Change | Files |
|--------|--------|
| E3 calibration packet 0x3C (dual FAT timestamps) | `SendCalibrationPacket.kt`, `EversenseE3Communicator.sendCalibration` |
| E3 push packets 0x40–0x42 trigger glucose read | `EversenseE3Packets.isPushPacket`, `EversenseGattCallback` |
| Calibration on `bleExecutor` + local `lastCalibrationDate` | `EversenseGattCallback.submitToExecutor`, `EversenseCGMPlugin.sendCalibration` |
| E3 DMS EU (`ousiamapialpha` / `ousalphaapiservices`) | `EversenseHttpE3Util.kt`, `EversensePlugin` cloud upload branch |

**Not ported in this pass:** in-app README guide (link only in CAPTCG README); full patch re-apply of 0002–0005 filenames refresh.

### Notification reader vs native plugin (important)

Notifications from official Senseonics apps are **not** the BLE plugin: they are routed through **Notification reader** using `plugins/source/src/main/assets/notification_reader_packages.json`.

- **`com.senseonics.gen12androidapp`** and **`com.senseonics.androidapp`** → sensor text **`Eversense E3`** → `SourceSensor.EVERSENSE_E3` (matches `SourceSensor` enum `text`).
- **`com.senseonics.eversense365.us`** → **`Eversense 365`** → `SourceSensor.EVERSENSE_365`.

The bundled JSON **`version`** must be bumped when this mapping changes (currently **3**). `NotificationReaderPlugin` reloads from the **asset** when `bundled.version >` the parsed version of the JSON stored in preferences, so existing installs pick up E3/365 without a manual reset. Remote definitions (Nightscout URL) still win when their `version` is higher than the local effective config after load.

When merging upstream `dev`, preserve fork-specific Senseonics rows and the version-bump / reload logic unless upstream provides an equivalent.

---

## Current fork state (baseline)

- **OpenApsAIMI** ships Eversense via:
  - **Notification reader** path (`SourceSensor.EVERSENSE`, official Senseonics app) — `plugins/source`.
  - **Native BLE plugin** — `plugins:eversense` (E3/E365), `plugins/source` `EversensePlugin`, calibration / DMS / status activities (CAPTCG series as integrated on this branch).

All merge sections below apply on every upstream merge.

## Patch order (do not reorder)

Apply with `git am -3` in strict order (from CAPTCG repo root / extracted ZIP):

1. `0001-Add-Eversense-E3-365-CGM-plugin.patch` — BLE driver module `plugins/eversense`
2. `0002-Add-EversenseStatusActivity-fix-plugin-registration-.patch` — BgSource plugin, DMS, activities
3. `0003-Register-Eversense-in-core-enums-DB-models-and-plugi.patch` — `SourceSensor` variants, DB, `PluginsListModule`, `settings.gradle`, `MainApp`
4. `0004-UI-Eversense-customizations-TIR-rename-transmitter-b.patch` — locales, overview status lights, icon
5. `0005-Maintenance-and-Config-cleanup.patch` — export settings, **Config / drift compensation cleanup**

If a patch fails: resolve conflicts **without dropping** Eversense-specific registrations (enums, DI, Gradle `include`). Prefer **combine** over “theirs only” when upstream touched the same file for unrelated reasons.

---

## High-risk conflict zones (merge `dev` → fork)

| Area | Why it conflicts | Preserve |
|------|------------------|----------|
| `settings.gradle` / `plugins/settings.gradle` | upstream adds/removes modules | Gradle must keep including the Eversense module (path name as on branch) |
| `PluginsListModule` / plugin DI graph | upstream plugin list churn | Eversense plugin binding and any `@Binds` / factory entries |
| `core/data/.../SourceSensor.kt` (and DB converters) | upstream CGM enum changes | **EVERSENSE**, **EVERSENSE_E3**, **EVERSENSE_365** (or exact names your branch uses) + DB round-trip |
| `database/impl/.../GlucoseValue.kt`, converters | new sensors | Eversense source values must persist in DB layer |
| `plugins/main/.../Overview*` / dashboard skins | fork-specific overview | Patch 0004-style UI (TIR label, transmitter battery) must be **re-applied** or merged manually if fork diverged |
| `core/interfaces/.../Config.kt` + `ConfigImpl` | fork has `ExternalOptions`, AIMI flags | Patch **0005** removes `enableOmnipodDriftCompensation` — **do not blindly accept** if this fork still relies on that symbol; **combine** with fork `Config` |
| Strings (10+ locales in CAPTCG 0004) | fork may differ | English + merge strategy per project rules (translations optional) |

---

## Product / safety notes (unchanged by merge)

- **E3**: official Eversense app typically required **alongside** AAPS (see CAPTCG README).
- **365**: can run **standalone** with native plugin.
- **DMS / cloud**: credentials and endpoints (EU vs US); privacy and support burden — not reverted by routine merges but **must not be stripped** accidentally when refactoring sync.

---

## Verification after merge

- [ ] `./gradlew` (or CI) compiles **full** flavor; when the native Eversense module is present, it stays on the classpath (no orphaned references).
- [ ] Eversense appears in Config Builder / BG source list and can be enabled.
- [ ] New glucose rows retain correct `SourceSensor` / NS upload source id.
- [ ] **AIMI / smoothing**: smoke loop with Eversense as active source (or notification path) — no crash, no missing enum in exporters.

---

## When upstream merges Eversense officially

If Nightscout `dev` eventually contains Eversense:

1. Prefer **upstream implementation** as source of truth for the next large merge.
2. Diff CAPTCG vs upstream and **drop duplicate** fork-only hacks.
3. Keep this file updated with a one-line status line (date and upstream commit hash).

---

## Escalation

If conflicts are unsolvable without architectural change: stop merge, document file list + `git log --merge`, and decide explicitly whether to **defer** Eversense to a follow-up branch rather than shipping a broken half-merge.
