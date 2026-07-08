# Non-Regression Checklist

Purpose: enforce repeatable quality gates to prevent freezes and functional regressions after merges, especially on high-risk paths (AIMI, adaptive smoothie, dashboard skins, ML permissions, physio, hormonitor structure).

Use this file for every merge from `dev` and every release candidate.

**Latest merge log:** [MERGE_DEV_2026-07-08.md](MERGE_DEV_2026-07-08.md) (dev @ `40275ce700` → `dev_OAPSAIMI`). Previous: [MERGE_DEV_2026-07-07.md](MERGE_DEV_2026-07-07.md) (dev @ `b45fb221e8`).

---

## 1) Mandatory Pre-Merge Checks

- [ ] Working tree clean before merge (`git status`).
- [ ] Merge target and source branches explicitly documented.
- [ ] Conflict files listed and reviewed one by one (no blind conflict resolution).
- [ ] For each conflict, decision recorded: keep ours / keep theirs / combine.
- [ ] If conflict touches async code, mark with `ASYNC IMPACT`.

Notes:
- No opportunistic refactor during merge conflict resolution.
- Keep method signatures and contracts unchanged unless explicitly required.

### Fork merge constraint: Eversense (native CGM patches)

When this fork includes (or will include) the CAPTCG Eversense BLE plugin series, every merge from upstream `dev` must **preserve** module registration, DI, `SourceSensor` / DB mappings, and Config-related changes. Do not resolve conflicts “theirs only” on those paths without explicit review.

- [ ] **Eversense preservation reviewed** — follow [docs/MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md) (patch order, high-risk files, post-merge checks).

---

## 2) Critical Domain Regression Gates

### AIMI plugin
- [ ] Loop behavior unchanged (no accidental SMB disabling).
- [ ] AIMI configuration import/export keys preserved.
- [ ] No regression on JSON/CSV writes used by AIMI workflows.

### Adaptive Smoothie plugin
- [ ] Plugin activation/state transitions unchanged.
- [ ] No regression on data flow feeding smoothing decisions.
- [ ] No new blocking call on main thread.
- [ ] With **Linear calibration** active, adaptive smoothing uses `calibratedOrValue` (parity with UKF / exponential smoothers).

### Dashboard + Skin switching
- [ ] Embedded dashboard renders correctly.
- [ ] User can switch back to original overview skin.
- [ ] No freeze/jank when opening dashboard repeatedly.
- [ ] No broken FAB/top bar/bottom bar visibility transitions.

### ML permissions and storage
- [ ] JSON/CSV write paths still allowed and reachable.
- [ ] No new permission requirement missing at runtime.
- [ ] Storage fallback/error path validated (no silent failure loops).
- [ ] **DataInbox** consumers call `drain()` before early return (Dexcom, xDrip, SMS, APS result export worker).

### Physio part
- [ ] Inputs/outputs used by physio layer are unchanged or intentionally migrated.
- [ ] No data type/unit mismatch introduced.
- [ ] No async race introduced between producer/consumer.

### Hormonitor study structure
- [ ] Required structural flows/interfaces remain intact.
- [ ] No rename/removal of expected keys or records.
- [ ] Historical compatibility preserved for study data consumption.
- [ ] Schema `1.2.0` additive block `patient_story` present when patient runtime is active (no breaking rename of `1.1.0` keys).

---

## 3) Stability and Freeze Prevention Gates

### Event orchestration
- [ ] No direct "refresh all" on every event burst.
- [ ] Event streams are debounced/coalesced where needed.
- [ ] Refresh work is cancellable (`collectLatest` or equivalent).

### Coroutine safety
- [ ] No unbounded parallel job spawning from frequent events.
- [ ] Expensive jobs are single-flight (cancel previous before new launch).
- [ ] Heavy work (`DB`, `TDD`, parsing, file I/O) off main thread.

### Compose/UI performance
- [ ] No avoidable recomposition storms introduced.
- [ ] Layout measurement loops avoided on hot screens.
- [ ] UI state updates scoped to minimal changed fields.

### Blocking call checks
- [ ] No new `runBlocking` in UI/ViewModel hot paths.
- [ ] No `Thread.sleep` on any UI-related execution path.
- [ ] No synchronous I/O in frequently triggered callbacks.

### Database maintenance (May 2026 regression — `SQLITE_NOMEM` / freeze)

Upstream once ran **inline `VACUUM`** inside `cleanupDatabase()`; `KeepAliveWorker` triggered it daily while the loop was active → crashes / OOM. Re-verify after every merge touching DB or workers.

- [ ] **`KeepAliveWorker.databaseCleanup`** calls `cleanupDatabase(..., runVacuum = false)` only (daily retention trim, no VACUUM). File: `app/.../receivers/KeepAliveWorker.kt`.
- [ ] **`AppRepository.cleanupDatabase`** does **not** run `VACUUM` unless `runVacuum == true` (comment documents SQLITE_NOMEM risk). Default path: `PRAGMA optimize` + deletes + `wal_checkpoint(TRUNCATE)` only. File: `database/impl/.../AppRepository.kt`.
- [ ] **Startup DB maintenance** uses `MainApp.maintainDatabaseIfDue()` → `maintainDatabaseAtStartup()` (**no automatic VACUUM** at launch; full VACUUM only via manual `runVacuum=true`). Key `LongNonKey.LastVacuumRun`, timeout 2 min, `catch (Throwable)`.
- [ ] **Manual maintenance only:** `runVacuum = true` only from explicit UI (e.g. `MaintenanceViewModel`, NS client cleanup dialog), with `DatabaseMaintenanceCoordinator` around compaction in `AppRepository`.
- [ ] **Merge conflicts:** resolving `AppRepository.kt` / `KeepAliveWorker.kt` / `PersistenceLayer.kt` did not re-inline `VACUUM` into the automatic cleanup path. See [MERGE_DEV_2026-05-20.md](MERGE_DEV_2026-05-20.md) (AppRepository combine note).

Reference commits: upstream `16598541af` (regression), fork `11f409c58c` (optional `runVacuum` + KeepAlive fix), upstream `18b2dee6ef` (VACUUM at startup).

---

## 4) Smoke Test Matrix (Required)

Run after merge and before release build:

### Core runtime
- [ ] App starts cleanly after cold launch.
- [ ] No ANR/freeze in first 5 minutes idle.

### Dashboard / Overview
- [ ] Open dashboard, interact for 3 minutes, no freeze.
- [ ] Switch skin dashboard <-> original overview, no regressions.
- [ ] Graph interactions remain responsive.

### AIMI / Smoothie
- [ ] AIMI plugin enabled path validated.
- [ ] Adaptive smoothie enabled path validated.
- [ ] SMB-related indicators/settings remain coherent with behavior.

### AAPSClient / NS flow (if applicable)
- [ ] With incoming status bursts, UI remains responsive for 5-10 minutes.
- [ ] Status lights/chips update without stutter or lockups.
- [ ] Validate with `Low-end device stability mode` ON and OFF (no functional regression in both modes).

### Permissions / storage
- [ ] JSON and CSV writes succeed on device.
- [ ] Missing-permission scenario handled without infinite retries/freezes.
- [ ] xDrip / Dexcom high-frequency receive: no WorkManager inbox stall (post-merge `Inbox.kt` gate).

### Database (optional on device, recommended after DB-related merge)
- [ ] Cold start: no crash during “optimizing database” (monthly checkpoint path, not full VACUUM).
- [ ] After 24h+ uptime: no `SQLITE_NOMEM` / DB freeze in logcat tied to `cleanupDatabase` or `KeepAliveWorker`.

Pass criteria:
- No freeze, no ANR, no blocking UI behavior, no critical feature regression.

---

## 5) PR Gate (Copy/Paste in PR description)

```
## Non-Regression Gate
- [ ] AIMI verified
- [ ] Adaptive Smoothie verified
- [ ] Dashboard + skin switching verified
- [ ] ML JSON/CSV permissions and writes verified
- [ ] Physio path verified
- [ ] Hormonitor structure verified
- [ ] Eversense merge constraint reviewed (if native plugin present on branch)
- [ ] Database maintenance regression gate reviewed (KeepAlive `runVacuum=false`, no auto VACUUM in `cleanupDatabase`)
- [ ] Async/freeze checklist reviewed
- [ ] Smoke tests passed
```

---

## 6) Freeze Incident Log (Append-only)

When a freeze is reported, append an entry:

```
Date:
Branch/Build:
Area:
Symptom:
Repro steps:
Suspected commit(s):
Root cause:
Fix commit:
Validation done:
Status: OPEN / MONITORING / CLOSED
```

Rule:
- Never close an incident without reproducible validation notes.

---

## 7) Release Go/No-Go

Release is `NO-GO` if any of the following is true:

- Any checklist item above is unchecked.
- Any OPEN freeze incident on same area/build.
- Any known regression in AIMI, adaptive smoothie, dashboard skin switching, ML permissions, physio, hormonitor structure, or Eversense native CGM integration when that integration is part of the release branch.

Release is `GO` only when all gates are green and documented.
