# AIMI Architecture Map — Harmonia Decision → Production

> **Scope.** Living reference for the Harmonia subsystem: how it *decides*, how (and when) it
> *acts* on the pump, and every gate in between. Built from a code audit + 24 h runtime data
> review. Line numbers are a snapshot (current as of this writing); rely on **symbol/function
> names** for durability. Extend this document as we map other AIMI subsystems.

All paths below are under
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/`.

---

## 1. TL;DR — the three Harmonia components

Harmonia is **not** one block. Separate what *observes*, what *decides*, and what *acts on the pump*:

| Component | Acts on pump? | File / entry point | Role |
|---|---|---|---|
| `PhysiologicalTree` | ❌ no | `patient/PhysiologicalTree.kt` | Read-only physiological belief tree; feeds the decision engine |
| **`HarmoniaDecisionEngine`** (ex-`HarmoniaSimulationEngine`) | ❌ not directly | `patient/HarmoniaDecision.kt` | **The brain.** Decides the action + target basal/SMB. Its output IS what production applies. |
| **Production basal-first** | ✅ yes | `DetermineBasalAIMI2.kt` → `planHarmoniaProductionBranch` | Owns the final basal when no SMB authority is active |
| **SMB modulation (RBT)** | ✅ yes | `recursive/RecursiveBeliefResolver.kt` → `resolveHarmoniaSmb` | Adjusts an *existing* RBT SMB (never originates one) |
| **`HarmoniaHarmonizer`** | ✅ yes | `patient/HarmoniaHarmonizer.kt` | 2nd-pass BLOCK/SOFTEN on the basal, descending only |

> ⚠️ **Naming caveat.** The engine was historically called *Simulation* and its decision record
> still carries legacy JSON keys `simulation_only=true` / `applies_to_pump=false` and
> `source="harmonia_simulation_branch_v1"`, plus the console string `"Harmonia sim:"`. Those flags
> mean only that *the record itself* does not command the pump — **its content does reach the pump**
> via the production/SMB channels. The Kotlin types were renamed to `HarmoniaDecision*` to remove
> this ambiguity; the JSON keys are kept as a **data contract** for the runtime-history readers /
> external analysis pipeline.

---

## 2. The decision engine (`HarmoniaDecisionEngine.evaluate`)

`patient/HarmoniaDecision.kt`. One pass per loop tick:

1. **`buildBlockers(tree, env)`** — safety gates. If non-empty, `chooseAction` is **short-circuited**
   and the action is forced to `BLOCKED` (this is why a single blocker kills the whole chain).
   Blockers, in order:
   - `sensor_warmup` — `env.sensorAgeMin in 1..120` (real minutes since insertion; `0` = unknown → not warmup)
   - `sensor_noise` — `sensorNoise >= 0.75`
   - `sensor_uncertain` — `sensorTrust.confidence < 0.40`
   - `hypo_or_recovery` — `hypoRisk.confidence >= 0.45`
   - `low_or_falling_bg` — `bg < 80 && delta <= 0`
   - `max_iob_pressure` — `iob >= maxIob * 0.92`
   - `critical_risk` — trunk risk `CRITICAL`
2. **`chooseAction`** → one of `HarmoniaAction`:
   - `BASAL_FIRST` — hormonalResistance / stress / insulinEffectiveness confidence ≥ 0.55
   - `MEAL_SUPPORT` — declared/undeclared meal-rise conditions
   - `PROTECTIVE_REDUCTION` — activity ≥ 0.55 or postActivity ≥ 0.45
   - `STABILIZE` — fragility ≥ 0.55 or exhaustion ≥ 0.65 or chaos ≥ 0.50
   - `OBSERVE` — nothing salient (→ no production action)
3. **Factor → bounded target** against a virtual pump model (caps, steps, IOB headroom):

   | Action | basal factor | smb factor |
   |---|---|---|
   | `BASAL_FIRST` | ×1.18 | 0 |
   | `MEAL_SUPPORT` | ×1.10 | ×0.30 |
   | `PROTECTIVE_REDUCTION` | ×0.70 | 0 |
   | `STABILIZE` | ×0.85 | 0 |
   | `OBSERVE` / `BLOCKED` | ×1.0 | 0 |

4. Returns `HarmoniaDecision`: `action`, `eligible`, **`targetBasalUph`**, **`targetSmbU`**,
   `basalFactor`, `smbFactor`, `blockers`, `rationale`, `compactSummary`.

### How the decision feeds production
Stored as `lastHarmoniaDecision`, then wired into `RbtExtendedSignals` (`DetermineBasalAIMI2.kt` ~§3426):

| Decision output | RBT signal | Effect |
|---|---|---|
| `action` | `harmoniaAction` | selects the channel |
| `eligible` | `harmoniaDecisionEligible` | **gates both channels** |
| `targetBasalUph` | `harmoniaBasalDemandRateUph` | **target** of applied basal |
| `targetSmbU` | `harmoniaSmbDemandU` | **target** of SMB modulation |

Production does **not** re-decide — it inherits action + target and only adds safety bounding.

---

## 3. Channel arbitration (`selectBasalFirstChannel`)

`recursive/RecursiveBeliefResolver.kt` (~§657). Mutually exclusive partition by whether RBT granted
an SMB release authority:

```
T3C eligible                              → T3C_BASAL_FIRST            (T3C wins, absolute priority)
else Harmonia eligible & authority==NONE  → HARMONIA_PRODUCTION_BASAL_FIRST
else                                      → NONE
```

- **`authority == NONE`** (no SMB this tick) → Harmonia can own the **basal** (basal-first).
- **`authority != NONE`** (SMB in flight) → basal-first blocked (`smb_authority_active`), but Harmonia
  can **modulate the SMB** (`resolveHarmoniaSmb`).

So Harmonia is *either* a basal owner *or* an SMB modulator on a given tick, never both.

---

## 4. Production basal-first (`planHarmoniaProductionBranch`)

`DetermineBasalAIMI2.kt` (~§7075). Runs only when `t3cNativePlan == null`. Gate order (first hit wins),
classified **defect / legit safety**:

| Gate | Source | Verdict |
|---|---|---|
| RBT channel must be `HARMONIA_PRODUCTION_BASAL_FIRST` + eligible | §7081–7095 | structural |
| simulation/decision `eligible` (blockers empty) | §7098–7100 (`blockers.firstOrNull()`) | depends on §2 blockers |
| action ∈ {BASAL_FIRST, MEAL_SUPPORT, PROTECTIVE_REDUCTION, STABILIZE} | §7102–7108 | `no_production_action` if OBSERVE |
| finite basal demand | §7110 | legit |
| no SMB authority active | §7113 | legit mutex |
| no SMB already requested (`rT.units/insulinReq <= 0`) | §7116 | legit (basal-first defers) |
| no exercise lockout | §7119 | legit safety |
| no post-hypo authority | §7122 (`post_hypo_guard`) | legit safety |
| no meal conflict | §7125 | legit safety |
| trunk not `CRITICAL` | §7128 | legit safety |
| `iob <= maxIob` | §7131 | legit safety |
| no stacking surveillance | §7134 | legit safety |
| no LGS hypo reason | §7138–7168 | legit safety |
| `finalRate > 0` after ramp + correction-aggression cap | §7170–7194 | legit |

Bounding: hard cap (min of env/profile max basal), **ramp** (`maxStepUp`, fragility-dependent),
correction-aggression cap. Output written to `finalProposedRate` / `finalDuration` → `setTempBasal`
(~§7484).

---

## 5. SMB modulation (`resolveHarmoniaSmb`)

`recursive/RecursiveBeliefResolver.kt` (~§669). **Modulator, never originator.** Eligible only when
`authority != NONE` **and** `basalFirstChannel == NONE` and action is `MEAL_SUPPORT`/`PROTECTIVE_REDUCTION`.

```
MEAL_SUPPORT          → smbU = max(currentDemand, bounded)   // nudge UP toward maxSMB×0.30
PROTECTIVE_REDUCTION  → smbU = min(currentDemand, bounded)   // cap DOWN
```

`bounded` is capped by `maxSMB effective` and **IOB headroom**. The result is **really applied** to the
delivered SMB at `computeSmbDemand` (~§397): `smbU = resolved.demandAfterU`.

**Why no originating SMB** (by design): SMB is irreversible (a TBR is correctable every 5 min);
avoids stacking with the many other SMB sources; Harmonia stays a co-pilot, not the bolus pilot.
JSON contract: `basal_first_only=true`, `adds_smb_authority=false`.

---

## 6. Harmonizer 2nd pass (`HarmoniaHarmonizer.evaluate`)

`patient/HarmoniaHarmonizer.kt`. Runs every tick, no toggle, **basal only, descending only**, just
before `setTempBasal` (`DetermineBasalAIMI2.kt` ~§7493). Postures:

- **`BLOCK`** — *only* path is `critical_physio_risk` (trunk `CRITICAL`, not hyper-correction-dominant)
  → `finalProposedRate = profile basal`.
- **`SOFTEN`** — `finalProposedRate *= tbrFactor` (0.82–0.90, always ≤ 1.0).
- **`CONFIRM`** — no change.

> **Known dead code:** `Outcome.smbFactor` is computed but **never consumed** (only `finalProposedRate`
> is touched). Either wire it or remove it.

---

## 7. Complete "does Harmonia act?" chain

```
PhysiologicalTree ──▶ HarmoniaDecisionEngine.evaluate ──▶ HarmoniaDecision{action, eligible, target*}
                                                              │
                          ┌───────────────────────────────────┤  (eligible gates BOTH)
                          ▼ authority==NONE                    ▼ authority!=NONE
              planHarmoniaProductionBranch            resolveHarmoniaSmb
              (owns basal, no SMB)                     (modulates existing SMB)
                          │                                    │
                          └──────────────┬─────────────────────┘
                                         ▼
                          HarmoniaHarmonizer (BLOCK/SOFTEN basal)
                                         ▼
                                   setTempBasal
```

---

## 8. Change log (decisions already taken)

1. **`sensor_age` → `sensor_warmup` fix** (`HarmoniaDecision.kt`). The old `sensorAgeMin > 10` leaked a
   simulation threshold onto real telemetry (age in days) → blocked ~259/291 ticks, neutralizing the
   whole engine. Now `in 1..SENSOR_WARMUP_MAX_MIN (120)`; `0` (unknown insertion) and established
   sensors are not blocked. Reliability still covered by `sensor_noise` + `sensor_uncertain`.
2. **STABILIZE alignment** (`RecursiveBeliefResolver.kt` `productionAction` set). Added `STABILIZE`,
   which the production branch already accepted (`DetermineBasalAIMI2` §7106) — prevents silent
   `NO_PRODUCTION_ACTION` mismatches.
3. **Simulation → Decision rename.** All Kotlin symbols (`HarmoniaSimulation*` → `HarmoniaDecision*`,
   `simulated*Uph/U` → `target*`, `harmoniaSimulation*` → `harmoniaDecision*`) + 2 files renamed.
   **Legacy JSON keys kept** (`simulated_basal_uph`, `simulated_smb_u`, `simulation_only`,
   `applies_to_pump`, `harmonia_simulation_branch_v1`) and console `"Harmonia sim:"` for the analysis
   pipeline / history readers.

## 9. Autodrive — unified V3 product (classic V1/V2 removed)

**Context.** Two engines used to coexist: *classic* (`tryAutodrive`, key `OApsAIMIautoDrive`) and *V3*
(`AutodriveEngine`, key `OApsAIMIautoDriveActive`). Structurally V3 ran first and, whenever it delivered
SMB, locked out the classic SMB (`classicSmbLockout`); a threshold proof showed that whenever classic
qualified (BG≥120, rise≥2) V3 also engaged (BG≥120, Δ>1.2), so classic was preempted in the nominal
case and its only unique capability — a **user-defined prebolus on aggressive rise** — almost never
fired. When V3 was TBR-only, classic could still overwrite V3's TBR (conflict).

**Decision (implemented).** Single product = V3.
1. **Absorption.** `aggressiveRiseSmbFloorU` + opt-in key `OApsAIMIautodriveAggressiveSmbFloor`
   (default off). On a confirmed aggressive rise within a *safe* V3 tick, the delivered SMB is
   `max(modelSmb, prefFloor)` where the floor is the user prebolus (`OApsAIMIautodrivePrebolus` Large
   tier rise≥5 & avg≥3, `OApsAIMIautodrivesmallPrebolus` Small tier rise≥2), pre-bounded by maxSMB +
   IOB headroom and re-bounded by post-hypo cap + downstream V3 safety. Never reduces the model SMB.
2. **Removal.** Deleted `tryAutodrive`, `runAutodriveV2FallbackBranch`, `isAutodriveModeCondition`,
   `adjustAutodriveCondition`, the cooldown `lastAutodriveActionTime`, the `classicSmbLockout`
   plumbing, and the `OApsAIMIautoDrive` key everywhere. `LoopPlugin` periodic loop now gates on V3.
   - **User migration** (`OpenAPSAIMIPlugin.migrateClassicAutodriveToV3`, runs once at `onStart`): if the
     legacy key `key_use_Aimi_autoDrive` was stored and ON while V3 was OFF, V3 is enabled to preserve the
     "autodrive enabled" intent (a classic-only user would otherwise silently lose all autodrive), then
     the legacy key is dropped so it never re-runs and a later manual V3-off is respected. Reads the
     legacy value via `SP` (raw string key) since the enum entry no longer exists.
3. **Autonomy ladder (no shadow, production).** Re-expressed via V3 + sub-flags:

   | Level | V3 | HTR | RBT authority | authoritative |
   |---|---|---|---|---|
   | Observation | OFF | — | — | — |
   | Recommendations | ON | OFF | OFF | OFF |
   | AssistedApplication | ON | ON | OFF | OFF |
   | ControlledAuthority | ON | ON | ON | ON |

   Read side (`AimiControlCenterSnapshot.buildAutonomyFamily`, `AimiControlCenterSupport.readAutonomyMode`)
   and write side (`buildAutonomyPlan`) are kept inverse-consistent. No tier "computes without acting."

## 10. Open items / next steps

- **Phase 3 (validation):** after the `sensor_warmup` fix, replay the 24 h runtime export and confirm
  `sensor_warmup` rate ≈ 0, identify the new dominant blocker, the real `chooseAction` distribution,
  the APPLIED rate, and how often T3C owns the channel.
- **Phase 4 (conditional):** if still too conservative (mostly OBSERVE, or T3C owns ~always), revisit
  `chooseAction` confidence thresholds or the T3C↔Harmonia arbitration.
- **Harmonizer `smbFactor`:** wire or delete.
- **JSON/console vocabulary migration** (optional): move `simulated_*` keys + `"Harmonia sim:"` to a
  `decision`-based vocabulary, versioned (bump record `version`, dual-write legacy + new key) so old
  exports stay readable.
