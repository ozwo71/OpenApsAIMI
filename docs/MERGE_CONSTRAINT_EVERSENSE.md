# Merge constraint: Eversense (CAPTCG patches / native CGM)

Purpose: preserve **Eversense E3 / E365 native CGM** integration when merging `dev` (Nightscout AndroidAPS) or rebasing this fork. Treat this as a **first-class merge constraint** alongside AIMI, smoothing, and dashboard paths.

Upstream reference repository: [CAPTCG/AndroidAPS-Eversense-](https://github.com/CAPTCG/AndroidAPS-Eversense-) (patch series + README). Related upstream discussion: [AndroidAPS PR #4474](https://github.com/nightscout/AndroidAPS/pull/4474).

## OpenApsAIMI — integration log (native plugin)

The **CAPTCG patch series 0001–0005** was applied on `dev_OAPSAIMI` (with resolutions below). After this point, merges from Nightscout `dev` must **keep** `plugins:eversense`, DI registrations, `SourceSensor` **EVERSENSE_E3** / **EVERSENSE_365**, DB converters, `EversensePlugin`, and the Eversense preference strings in `core/keys`.

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
