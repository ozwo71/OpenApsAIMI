# AIMI — session handover, 2026-08-09

Started at the end of the 2026-08-09 morning session, extended 2026-08-10. Three parts:

- **Part A — the record of the morning.** Decisions and measurements. Several of its conclusions are
  superseded; read Part A-bis before acting on any of them.
- **Part A-bis — the production day of 2026-08-09.** What happened on the pump, what caused it, and
  which of Part A's conclusions were wrong.
- **Part A-ter — the lunch of 2026-08-10.** The Part A-bis build ran, and one of its brakes turned a
  near-hypo into a sustained hyper. **This is the current state of knowledge, and it reverses one
  change from Part A-bis.**
- **Part A-quater — the meal intent, traced (2026-08-11).** Answers Part A-ter's closing question and
  **corrects its diagnosis of the 2026-08-10 lunch**. This is now the current state of knowledge.
- **Part B — the prompt for the next session.** Paste it as-is, but read Part A-quater first — it
  outranks everything in Part B.

Part A is committed: `eda530a545` then `6d24095023`. **Everything in Part A-bis, A-ter and A-quater is
uncommitted.** Full module suite: **1345 tests, 0 failures** (was 1332; A-quater adds 13).

---

# Part A — the record

## A1. Where the code stands

Two commits landed this morning, on top of `1135038d55`.

| commit | what it contains |
|---|---|
| `eda530a545` | estimator advances once per CGM sample; one training row per tick, staged then flushed in a `finally`; `engaged` supplied by the caller; first version of the profile-anchored safety sensitivity |
| `6d24095023` | `InsulinActionModel` as the single source of truth; the `0.1` sensitivity floor removed; HTR floor and Ra exported from where they exist; MPC Ra floor wired on every path; barrier acceleration memory keyed per observation; shadow exports for the two open calibration questions |

The commit message of `eda530a545` says it *"Updated `AutoDriveState` to include a new `safetySi`
field"*. That field was removed again in `6d24095023` and replaced by a parameter on
`ControlBarrierShield.enforce`. **The message describes the opposite of the code.** Amend it or leave
a note; do not trust it when reading history.

## A2. Decisions taken, with the reasoning

### D1 — The once-per-tick invariant lives in the estimator, not in the callers

`AutodriveEngine.tick()` is reached from three places: the engaged Autodrive branch, the T3C shadow
tick, and `proposeBasalOnlyTbr`, which itself delegates to `tick()`. Each call ran
`ContinuousStateEstimator.updateAndPredict` unconditionally. Between two calls in the same tick
`dtMin ≈ 0`, so `raDecay ≈ 1` and the prediction step separates nothing: the same observation is
applied again, `pRa` takes another `+ qRa` then another `(1 - kRa)`, and the effective Kalman gain
doubles or triples.

The guard was put **inside** `updateAndPredict`, keyed on an observation id. Moving one call site
would not protect against the next call site somebody adds.

### D2 — The key is the CGM sample, not the loop invocation

First attempt keyed on `ctx.currentTime`. Measured on the production corpus
(`autodrive_dataset.csv`, 17 068 rows): **2 610 consecutive rows carry an identical `BG_Current`
*and* `BG_Velocity` less than 60 s apart, and `Estimated_Ra` moved between them on 1 974 of those
(76 %)** — by more than 0.2 mg/dL/min on 405, up to 2.83. So the duplication that actually happens is
*between* invocations, on the same CGM sample. The key is now `ctx.glucoseStatus.date`, with a
fallback to `ctx.currentTime` when the sample carries no date.

Two identities are deliberately separate and must stay separate:

- `tickId` — the invocation. Identifies the **training row**; must match what `flushTickRow` is
  called with.
- `observationId` — the CGM sample. Identifies the **observation**; governs the estimator advance and
  the barrier's acceleration memory.

### D3 — One training row per tick, labelled by the path that owned the dose

`tick()` no longer writes to the data lake. It stages a `PendingTrainingRow`; an engaged row is never
replaced by a shadow one for the same tick. `flushTickRow(tickId)` writes at most one row and is
called from `runDetermineBasalTick` in a `finally`.

`engaged` is a caller-supplied parameter. `tick()` cannot tell a real decision from a T3C proposal
whose SMB the caller strips and which never reaches the pump — and it used to label both as engaged.
That contaminated the very feature added the day before.

### D4 — The safety barrier reads a sensitivity nothing downstream can lower

`enforce` derives `lgh = -siMetabolic * bg` and permits `safeU = (-γh - lfh) / lgh`. A **lower**
sensitivity makes `|lgh|` smaller and the permitted dose **larger**. Anything able to lower that
number is able to loosen the barrier.

The sensitivity is now passed as a parameter, computed as
`max(clamp(profileIsf / 10000), commandedSi)` — the more restrictive of the two, so the change can
only ever tighten. It is a parameter and not a field on `AutoDriveState` because a `copy()` would
re-run the state's `init` checks on the dosing path, and because a second sensitivity in the domain
model is exactly what ADR 0008 step 3 will have to remove.

### D5 — One insulin-action model, calibrated to change nothing

There were **four** constants, not three, and the fourth did not look like one:

| reader | old expression | effective coefficient |
|---|---|---|
| `ContinuousStateEstimator` | `estimatedSI * 0.0012 * iob * bg` | 1.2e-4 |
| `MpcController` | `estimatedSI * 0.05 * iob * bg` | 5.0e-3 |
| `ControlBarrierShield` | `estimatedSI * 0.05 * iob * bg` | 5.0e-3 |
| `AutoDriveState.createSafe` | `estimatedSI.coerceAtLeast(0.1)` | governed all three |

Callers pass an ISF in mg/dL/U divided by 10000 — about 0.004. The floor clamped **every patient to
the same sensitivity**, 22 to 50 times above the value passed, so the profile ISF stopped reaching
the controller and the barrier entirely.

`InsulinActionModel` states the calibration as a time constant:
`effect = (isf / tau) * iob * (bg / 120)`.

- `MPC_TAU_MIN = 75` reproduces the old 5.0e-3 **exactly** at ISF 45.
- `ESTIMATOR_TAU_MIN = 3125` reproduces the old 1.2e-4 **exactly** at ISF 45.

Both are pinned by tests to 1e-12 / 1e-15. The physiological range for the exponential tail of a
rapid analogue is 150–200 minutes, so the controller and the barrier are roughly twice as aggressive
as physiology, and the estimator has effectively had **no insulin term at all**.

### D6 — The floor removal is real, so a floor on the coefficient replaces it

Removing the `0.1` floor makes the barrier proportional to the profile ISF, which is correct: a
resistant patient (low ISF) should be allowed more insulin than a sensitive one. But proportionality
necessarily **loosens** the barrier for every patient below the reference ISF.

Measured on 1 135 exported ticks from this deployment: **median profile ISF is 30, not 45.** The
unfloored coefficient would be 0.67×, i.e. a barrier about **a third more permissive on the median
tick**. That is a hypoglycaemia constraint, and a third is not something to ship on arithmetic.

So `InsulinActionModel.controlCoefficient` floors at `LEGACY_CONTROL_COEFFICIENT = 0.005`. Above the
reference ISF the barrier tightens; at or below it, it stays exactly where it was. **The refactor can
only tighten.**

Side effect worth knowing: that floor also blocks *any* downward multiplier, so the
`aggressionMultiplier ∈ [0.55, 1.08]` of `PkPdIntegration` can no longer loosen the barrier at all.
The `safetySi` anchor now only bites above the reference ISF. Both regimes have a test.

## A3. What was measured, and where

All figures below were computed directly, not estimated. Reproduce before trusting.

Sources — **outside the repo, and they contain personal health data. Do not copy them into the repo,
do not paste their contents into an issue, a commit or a shared document.**

- `/Users/mtr/Downloads/AIMI files/autodrive_dataset.csv` — 17 068 rows, 2026-02-28 → 2026-07-11
- `/Users/mtr/Downloads/AIMI_Support_Package_*/AIMI_Decisions*.jsonl` — 1 135 ticks with a profile ISF

| measurement | value |
|---|---|
| `Estimated_SI` median | exactly 0.1000; 83.7 % of rows ≥ 0.09 |
| barrier intervention, floor active (14 291 rows) | **41.2 %** of ticks; whole dose zeroed on 37.7 % |
| barrier intervention, floor inactive (2 777 rows) | **0.7 %** |
| profile ISF | p10 = 30, median = 30, p90 = 70 |
| insulin term, barrier/MPC | median 1.668 mg/dL/min (p90 10.0, max 32.3) |
| insulin term, estimator | median 0.040 mg/dL/min |
| same term at τ = 150 / τ = 200 | median 0.833 / 0.625 |
| duplicate CGM samples with Ra moving | 1 974 of 2 610 pairs (76 %), max Δ 2.83 |

The barrier is **not** a rarely-binding component. It is the dominant clamp, and it is dominant
because of a `coerceAtLeast` on a value carried in the wrong units, not because of a designed margin.

## A4. Errors corrected

### Mine, made and corrected during this session

1. **Wrote a factual claim into a test class doc that production contradicts.** The doc said the
   barrier "rarely binds" and that "the only clamp that fires is the maxIOB truncation". True of a
   fixture that builds `AutoDriveState` through the constructor and so bypasses `createSafe`; false
   of the pump, where it binds on 41.2 % of ticks. Corrected with the measured numbers. This is the
   most damaging kind of error: written with enough authority to stop the next reader re-measuring.
2. **Keyed the estimator guard on the invocation instead of the CGM sample** — see D2. The guard
   would have caught nothing that actually happens.
3. **Confused the training-row identity with the observation identity** while fixing (2). Would have
   made `flushTickRow` reject every row. Caught before the build; fixed by splitting `tickId` and
   `observationId`.
4. **Put the safety sensitivity on `AutoDriveState`**, which added a `copy()` on the dosing path.
   Kotlin `copy` re-runs the `init` block, so a non-finite estimate would have thrown where it used
   to be tolerated. Replaced by a parameter.
5. **Called `flushTickRow` as a plain statement**, so a throw in the inner tick lost the staged row —
   biasing the training set away from the anomalous ticks, which are the ones worth learning from.
   Moved into a `finally`.
6. **Presented the "policy can loosen the barrier by 45 %" threat model as live.** It was dead at the
   `0.1` floor: `aggressionMultiplier × ISF/10000` coerces to `0.1` in every arm. Confirmed
   empirically — post-floor `Estimated_SI` spans 0.0999 to 0.1006, a ±0.3 % spread, not ±45 %.
7. **Reported "the exit bound would bite 3 % of ticks" as if it measured the safety path.** It was
   measured on `oapsProfile.sens`; the barrier and the MPC read `pkpdRuntime.fusedIsf`. The figure is
   withdrawn and has not been re-measured. See T7.

### Pre-existing, found and fixed

- Estimator advancing 1–3 times per tick, at a rate that depended on which preferences were on.
- Training rows duplicated at the same timestamp, all labelled `Engaged = 1`, including T3C proposals
  that never reach the pump.
- `htr_ra_floor_mgdl_per_min` guaranteed `null` in every export: written inside `if (gate.engage)`,
  read at tick bootstrap from a frozen `val`. The one instrument added to make the HTR floor
  measurable could not measure it.
- `estimated_ra_mgdl_per_min` read at bootstrap, so it carried the **previous** tick's estimate — the
  comparison with the floor, which is the whole point of exporting the two together, was meaningless.
  A correctly-timed `estimated_ra_used_mgdl_per_min` was added.
- `mpcRaFloorMgdlPerMin` wired at one of three call sites; the other two took the `0.0` default in
  silence. Now relayed, and the two T3C paths pass `0.0` explicitly with the reason written down.
- `ControlBarrierShield.lastBgVelocity` is `@Singleton` state: a second `enforce` in the same tick
  read `accel = 0`, disarming the guard that halves γ on a fast fall, and destroyed the tick-to-tick
  derivative. Now keyed per observation — which is also what makes the shadow second call safe.
- The four insulin-action constants and the `0.1` floor (D5, D6).

## A5. What tonight's data must answer

The build now computes two shadow quantities. Neither reaches the pump; both are export-only.

**Verification of the four fixes:**

| check | field |
|---|---|
| estimator advances once per CGM sample | `ra_estimator_advances` +1 per tick; `ra_estimator_replayed_calls` rises when T3C runs |
| one row per tick, correctly labelled | no duplicate timestamps in the CSV; `Engaged = 0` on T3C proposals |
| HTR floor measurable | `htr_ra_floor_mgdl_per_min` non-null, against `estimated_ra_used_mgdl_per_min` |
| dose unchanged | `cbf_permitted_u` reproduces the previous build |

**The two open calibration questions:**

- `ra_aligned_tau_shadow_mgdl_per_min` — the same Kalman filter, same process noise, same clipping,
  only the insulin term aligned on the controller's. If it stays below 0.6 most of the time, the
  projection from medians was too pessimistic and `ESTIMATOR_TAU_MIN` can be aligned. If it sits at
  the ceiling whenever there is IOB, the Ra gates (0.6 / 0.7 / 0.8) and `UndeclaredCobEstimator` must
  move in the same change. **The two meals are the discriminating test**: that is where Ra must rise
  for real, so that is where we see whether the shadow separates a meal from the mere presence of
  insulin.
- `cbf_permitted_u` vs `cbf_permitted_unfloored_u`, with `cbf_profile_isf_mgdl` alongside — the same
  barrier, same state, floor removed. This is the number that decides the ±33 %, measured on the real
  ISF distribution rather than on a median.

---

# Part A-bis — the production day of 2026-08-09, and what it changed

Written 2026-08-10. This supersedes several conclusions in Part A. Read it before Part B.

## The event

An undeclared lunch. No meal mode, COB 0 g throughout. Support package
`AIMI_Support_Package_1786304612047`, 292 ticks.

| | |
|---|---|
| SMB delivered 14:07 → 15:26 | **14.09 U in 79 minutes** |
| peak IOB | **16.75 U**, against a computed physiological budget of **8.11 U** |
| BG | 115 → 225 → 110, still 11.1 U on board at 16:06 |
| outcome | no hypoglycaemia — rescue carbs (ice cream) at 16:10 |

The patient's words: *"ça a corrigé un peu trop"*.

## The cause, and the one it is not

**Not the ISF collapse.** `command_isf_mgdl` did collapse — 46.8 at euglycaemia down to a terminal
9.7 at BG 225, driven by `dynamicDeltaCorrectionFactor` (`OpenAPSAIMIPlugin.kt:707`, exact against the
export to 10 decimals). But `InsulinActionModel.controlCoefficient` floors at 0.005 and the floor was
active on 39 of 45 ticks, so **the MPC and the barrier both reasoned as if the ISF were 45 on every
tick of this event**. The collapse changed their arithmetic by nothing.

**The cause is `aggressiveRiseSmbFloorU`, `max()`-ed after the safety barrier.**
`DetermineBasalAIMI2.kt`, `val v3SmbRaw = maxOf(v3SmbModel, v3SmbFloor)`, downstream of
`safetyShield.enforce`:

```
h      BG     IOB    MPC asked   barrier permitted   delivered
14:41  188.5   9.04     0.000          0.000           1.088
14:46  202.2  10.36     0.000          0.000           1.088
14:51  212.6  11.65     0.000          0.000           1.088
14:56  217.2  12.75     0.000          0.000           1.088
15:01  220.3  13.66     0.000          0.000           1.600
15:06  224.9  15.28     0.000          0.000           1.550
```

Over the whole day: **25 ticks where the delivered dose exceeded what the barrier permitted, +12.87 U
in total.** A per-tick floor with no memory, at a 1.6 U ceiling on 5-minute ticks, is an effective
19 U/h floor for as long as the rise holds. Its KDoc claims it is *"always re-bounded by V3 safety"*.

**Nothing braked the stack.** `InsulinLoadGovernor` computes the right number (`phys_budget_u` 8.11)
and stayed in tier `FULL` up to IOB 16.75 — 2.07x the budget — because `ESCAPE_RISE` pinned the
multiplier on any rise with **no IOB guard**, while `ESCAPE_PROJECTION` three lines below already had
one. `maxIOB` is 20 U, which against a budget of 8.11 is not a brake. The predictive hypo guard fired,
announced 67 mg/dL, and was overridden by `PREDICTIVE_HYPO_MEAL_BYPASS`.

**Harmonia was not absent — it was overruled.** Through the entire rise the pattern catalogue proposed
a SOFT cap of **1.2 U** and Harmonia's SMB arbiter returned `REDUCE` with intent `PROTECTIVE`. Three
components said no or less; a `maxOf()` downstream of all three served anyway.

## Corrections to Part A and to earlier notes

1. **The build boundary was 14:07, not 16:22.** Part A's A5 assumed the new export fields would date
   it. They did not: `ra_estimator_advances` was written **after** the JSONL was serialised, so it
   landed on 7 ticks out of 93, all `Basal_Modulation`. The lunch ran on `6d24095023`. Fixed —
   the counters are now written in `markEstimatorDiagnosticsForExport`, immediately before
   `decisionCtx.toMedicalJson()`, the one point every export path goes through.
2. **The two calibration questions of A5 are still unanswered.** The shadow reached only 7 ticks for
   the reason above. What those 7 do show: on flat glucose at BG 91 with 5.5 U on board and a real Ra
   of 0.00, the aligned-tau shadow reported **0.60 / 0.70 / 0.79** — it invents appearance to explain
   why glucose is not falling faster, and crosses all three gates. Suggestive, not conclusive: n = 7,
   biased to quiet ticks. **A clean day is still needed.**
3. **`sensor_confidence` is no longer the Harmonia blocker.** It measured 0.88 (min 0.77) against
   thresholds of 0.45 / 0.65. The `cgm_first` option is on and works. The memory note claiming it is
   pinned at 0.32 is obsolete.
4. **Harmonia has an SMB path, and it works.** `basal_first_only = true` is the *label of the basal
   production record*, tautological — not a restriction. `HarmoniaSmbArbiter` exists with a
   `LIFT_WITHIN_ENVELOPE` mode. The note claiming the LIFT is "structurally dead downstream" is wrong:
   `RecursiveBeliefResolver` applies it and explicitly protects it from the legacy meal-support
   target. LIFT never fired on this day because the intent was `PROTECTIVE`, which takes an early
   return that can only reduce or accept — correctly, on this meal.
5. **Do not clamp the dose to the barrier.** It was proposed as a "pure reduction" and it is not: it
   would remove **12.87 U, 38 % of the day's SMB**, and it would have delivered nothing during the
   evening rise (BG 130 → 151, barrier at zero on every tick). The barrier's `lfh` was wrong by
   ~12 mg/dL/min during the meal. **The barrier cannot be made binding until its insulin model is
   fixed** — which is the `MPC_TAU_MIN` question deliberately deferred in D5.

## Structural finding: five writers of the final SMB

| site | owner | legitimate |
|---|---|---|
| `finalizeAndCapSMB` | the intended terminal | yes |
| `runMealAdvisorDecisionOrReturn` | meal advisor | yes — user action |
| `applyLegacyMealModes` (x2) | legacy meal modes / prebolus | early-exit paths |
| `runPostBasalEngineLearners…AuditorStage` | **AI auditor** | **no** |

The auditor wrote `finalResult.units = result.bolusU` with no comparison against the terminal, while
its own prompt says *"CONFIRM or SOFTEN only — never invent a lift"*. Third instance in two days of
the same shape: **a constraint stated in documentation and absent from the code.**

## What was implemented on 2026-08-09/10 (uncommitted, 1332 tests green)

- **Export instrumentation fixed** — counters and the Ra shadow now reach every tick.
- **`ESCAPE_RISE` IOB guard** — the escape stops at the physiological budget, matching its neighbour.
  On this event it stops at 14:41, IOB 9.04 vs budget 8.11: the tick the overdose began.
- **Episode budget on the rise floor** — one prebolus per rise, re-arming after 90 idle minutes; only
  what the floor adds *above the model* spends the budget.
- **Pattern catalogue in fractions of the user's ceiling** — the table was absolute units implicitly
  calibrated for `maxSMBHB = 1.6`. Converted against that reference, rounded **down**, max difference
  0.001 U. Read as fractions it is a coherent severity ladder from 19 % to 94 %; read as units it was
  a single-patient table. A fraction can never exceed 1.0, so a pattern may reduce the user's ceiling,
  never raise it.
- **Sealed SMB terminal** — `applySmbUnits(rT, value, owner)` is the only write path; after
  `finalizeAndCapSMB` seals the tick, a write may only lower. `MealAdvisor` keeps its exception, now
  counted. Refusals exported as `smb_seal_refused_count` / `_total_u`.
- **Learning pipeline hardened** — schema-versioned dataset (the `Engaged` feature was perfectly
  collinear with "labelled by the broken pass"; 73.2 % of rows had an uncovered outcome window; the
  positive class is **0.53 %**, not 3 %), non-blocking lock on the dosing path, class-balanced trainer
  objective with a chronological holdout gate and prior correction on save, `SensitivityRatioEstimator`
  given COB/bolus rejection, real delivered basal, time-keyed EMA and persistence.

**The sealed terminal would not have prevented this event.** The floor is an *input* to
`finalizeAndCapSMB`, upstream of the seal. The two brakes are what address it.

---

# Part A-ter — the lunch of 2026-08-10, and the brake that was reverted

Written 2026-08-10, after the build of Part A-bis ran in production. **This is the current state of
knowledge. It reverses one of Part A-bis's changes.**

## The event

The Part A-bis build was installed. Same patient, undeclared lunch again, no meal mode, COB 0.
Support package `AIMI_Support_Package_1786365787997`.

| | 2026-08-09 (no episode budget) | 2026-08-10 (episode budget active) |
|---|---|---|
| peak BG | 225 | **268.7** |
| SMB at peak | 14.06 U | **6.53 U** |
| peak IOB | 16.75 | 8.66 |
| two hours later | 110 (rescue carbs) | still ~205 |

One failure was replaced by its opposite.

## What caused it, and what did not

**Not the pattern catalogue.** The conversion to fractions reproduces the previous behaviour exactly:
`max_smb_hb_u` = 1.6, `smb_cap_u` = 1.20, identical to the previous absolute value. It is exonerated.

> **⚠️ CORRECTED 2026-08-11 — this attribution is wrong. See Part A-quater §AQ5.** The 2026-08-10
> undershoot was the **effort veto after a walk to lunch** (steps15 390–453 at 12:02–12:12, HR 74–84),
> which multiplied the SMB by 0.45–0.56 on 14 ticks. Both lunches ran the *identical* cap chain
> (proposed 1.60 → safety 1.36 → throttle 1.09) and differed only in that multiplier. The paragraph
> below is left as the record of what was believed on 2026-08-10; its own caveat — that the budget
> state was never exported and its absence was inferred — is exactly why it was wrong.

**The episode budget on `aggressiveRiseSmbFloorU`.** The floor appears nowhere in the 2026-08-10 lunch
trace, while on 2026-08-09 it delivered 1.36 then 1.60 U per tick. Doses were small from the very
first tick — 0.29 U at BG 125 with IOB 0.94 — well before any governor action.

**Caveat, and it is mine:** the budget state (`riseFloorSpentU`, time since last contribution) was
**never exported**. The floor's absence is inferred from the trace, not measured. Second time in this
work that a mechanism was shipped without its instrument.

> **⚠️ CORRECTED 2026-08-11.** `PROTECTIVE` is real, but it is *not* the root cause, and
> `LIFT_WITHIN_ENVELOPE` is not the fix: it can only restore RBT's own demand toward the MPC command,
> never exceed it, so it cannot carry a meal at any threshold. See Part A-quater §AQ5.

**The root cause is upstream of both brakes.** On **every tick** of the rise,
`harmonia_smb_authority.insulin_intent` was `PROTECTIVE` — at BG 144, 195, 237, 269, with Ra up to
5.33. Never `MEAL_SUPPORT`, never `NEED_MORE_INSULIN`. `PROTECTIVE` takes an early return in
`HarmoniaSmbArbiter.decide` that can only ACCEPT or REDUCE, so `LIFT_WITHIN_ENVELOPE` is structurally
unreachable. The governed demand caps at the catalogue's 1.20 U and Harmonia reduces it further,
leaving ~0.5 U per tick against a meal driving BG to 268.

**So the floor was not merely bypassing the barrier — it was carrying the entire meal.** The governed
path never has. Removing the bypass without first checking that the legitimate path could carry the
load turned a near-hypo into a sustained hyper.

## What was changed on 2026-08-10

- **The episode budget was removed from the dose path.** `remainingRiseFloorBudgetU` and its eight
  tests are kept, out of the dose path, with the two measurement tables in its KDoc and an explicit
  instruction not to reinstate it until Harmonia's intent switches on a confirmed meal.
- **The `ESCAPE_RISE` IOB guard was kept.** The two brakes bound the same thing — accumulation — but
  not at the same place. The budget cuts at the *start* of a meal, when dosing is still needed; the
  guard cuts at the *physiological budget*, when there genuinely is too much insulin on board. On
  2026-08-09 it would have stopped at IOB 9.04 instead of 16.75. Keeping the guard and dropping the
  budget should land between the two failures.

Suite: 1332 tests, 0 failures. Uncommitted.

## The lesson, stated plainly

The 2026-08-09 brake was designed from a single episode — the one where the floor delivered 14 U too
many — without measuring what the governed path delivered **without** it. It delivered almost nothing.
Removing a bypass is only safe once the legitimate path is shown to carry the load.

## The dinner of 2026-08-10 — a regression I introduced, and a useful signal

Package `AIMI_Support_Package_1786432933407`. The patient observed that the highest SMB of the dinner
was 1.2 U and asked why it could not reach the configured 1.6.

**Answer:** `max_smb_hb_u` did ramp correctly with glucose (0.88 → 1.12 → 1.36 → 1.60), but the meal
patterns propose **0.75 of the ceiling**, so the effective cap is 1.20 and never 1.60. Confirmed:
`smb_cap_u` = 1.200 on every tick of the rise, and the delivered doses sat on it (1.16, 1.19, 1.24).

**The regression — mine, fixed.** The fraction conversion was calibrated against `maxSMBHB = 1.6`
assuming that ceiling constant. It is not: `this.maxSMBHB` is a field **reassigned during the tick**
that ramps with glucose. Multiplying a fraction by it applied the reduction twice:

| BG | cap before the conversion | cap with the bug | cap now |
|---|---|---|---|
| 111 | 1.20 | **0.66** | 1.20 |
| 125 | 1.20 | 0.84 | 1.20 |
| 140 | 1.20 | 1.02 | 1.20 |

Up to **45 % tighter at the start of a meal**, exactly when the prebolus matters. Fixed by reading
`preferences.get(OApsAIMIHighBGMaxSMB)` — the configured setting — instead of the ramped field. The
catalogue reduces a *setting*; the tick's own maxSMB/maxSMBHB selection still bounds the dose
afterwards, so the two bounds act in their own place.

Same failure shape as the episode budget: a claim of neutrality verified at one operating point only.

**Two signals worth carrying forward:**

1. At 21:12 and 21:17 the intent was **`NEED_MORE_INSULIN`**, not `PROTECTIVE`. First observed switch.
   The mode stayed `ACCEPT` because the MPC was not asking above the 1.20 cap, so there was nothing to
   lift. This points Agent 0 at a **mis-calibrated threshold — too late, too rare — rather than a dead
   path.** That is a materially different (and easier) problem than the one Part A-ter states.
2. The day's highest SMB was **1.60 U at 07:42**, with no meal pattern active. The full ramp is
   reachable outside meals and capped at 75 % during them — the inverse of what is wanted.

**Still open:** a `SOFT` cap that is never lifted behaves exactly like a `HARD` one. Either the lift
becomes reachable (Agent 0), or an unlifted `SOFT` must defer to `maxSMBHB` instead of replacing it.

## The Ra shadow, finally measurable — and the answer to A5's first calibration question

The export-ordering fix landed, so `ra_aligned_tau_shadow_mgdl_per_min` now reaches **226 ticks out of
231** instead of 7 out of 93. Measured on `AIMI_Support_Package_1786432933407`, and pooled with the
earlier corpora where the field exists (**413 paired ticks**).

### The aligned insulin term produces a usable signal

| | Ra today | shadow, aligned tau |
|---|---|---|
| median | 0.21 | 0.87 |
| p90 | 1.51 | 5.49 |
| max | 5.33 | 8.13 |
| crosses 0.6 | 23 % | **59 %** |

**The earlier fear was overstated.** On the 65 ticks where the real Ra is ≤ 0.05 — nothing digesting —
the shadow's median is **0.14**, and it crosses 0.6 on only 13 of them. It does *not* invent
appearance from the mere presence of insulin. It separates rest (0.14) from meal (p90 5.49).

The conclusion of A-bis correction 2 — "suggestive, not conclusive, n = 7, biased to quiet ticks" —
resolves in favour of alignment being **viable**, provided the gates move with it.

Also confirmed in production: **`ra_estimator_replayed_calls` = 0**. The once-per-CGM-sample guard
holds; no duplicate advances observed.

### Iso-rate gate thresholds — and why the result is itself a finding

Mapping each current gate to the shadow threshold that reproduces its crossing rate, over 413 ticks:

| gate | crossing rate today | equivalent shadow threshold |
|---|---|---|
| 0.6 | 18.4 % | **4.25** |
| 0.7 | 16.7 % | **4.55** |
| 0.8 | 16.2 % | **4.57** |

Shadow distribution: p50 0.63, p75 2.85, p90 5.22, p95 6.14, p99 7.38.

**The three thresholds compress to almost one value, because they already do today.** 0.6 / 0.7 / 0.8
select 18.4 % / 16.7 % / 16.2 % — 2.2 percentage points apart across the whole range. Three gates that
differ by two points of population are not three gates; they are one gate written three times.

So iso-rate mapping is the wrong method here, and it fails informatively: it should not be used to
pick 4.25 / 4.55 / 4.57. The prior question is **what each of the three gates is supposed to
discriminate**, since today they do not discriminate anything from each other. Answer that first, then
place thresholds on the shadow's distribution by intent rather than by rate-matching.

**Nothing was changed in code for this.** `ESTIMATOR_TAU_MIN` stays at 3125 (behaviour-preserving),
the gates stay at 0.6 / 0.7 / 0.8. The shadow is exported on every tick, so the next packages extend
the sample at no cost and with no dosing risk.

**Deliberately not sequenced with the dosing work.** `OApsAIMIHighBGMaxSMB` is being raised by the
patient, which moves the dose. Changing the Ra gates at the same time would make neither effect
attributable — the exact error made twice this week.

## The open question, and it is now the only one that matters

**Where is `insulin_intent` decided, and why does it never select `MEAL_SUPPORT` on a confirmed
undeclared meal?** Until it does, the loop depends on a floor that ignores the MPC, the barrier and
Harmonia — and every attempt to bound that floor will starve meals. This is the next session's first
task, ahead of everything in Part B.

---

# Part A-quater — the meal intent, traced (2026-08-11)

Written 2026-08-11 by the Agent 0 (`meal-intent`) run. **This answers Part A-ter's closing question
and corrects its diagnosis of the 2026-08-10 lunch.** Everything below was computed from the three
support packages (`1786304612047`, `1786365787997`, `1786432933407`); reproduce before trusting.

## AQ1. Where `insulin_intent` is decided, and what makes it `PROTECTIVE`

Producer: `PhysiologicalTreeBuilder.resolveInsulinIntent`,
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PhysiologicalTree.kt`. Two
`PROTECTIVE` gates, in order:

| gate | line (before this change) | condition |
|---|---|---|
| 1 — hypo / sleep | `PhysiologicalTree.kt:301` | trunk `HYPO_RISK` or `SLEEP_RECOVERY`, or `branches.hypoRisk.confidence >= 0.55` |
| 2 — activity | `PhysiologicalTree.kt:307` | `branches.activity.confidence >= 0.60` **or** `branches.postActivity.confidence >= 0.55` |

**Gate 2 causes essentially all of it, and gate 1 causes none of it on a meal.** Classified over every
`PROTECTIVE` tick in each package:

| package | gate 2 (activity) | gate 1 (hypo/sleep) |
|---|---|---|
| lunch 2026-08-10 | **150 / 150** | 0 |
| dinner 2026-08-10 | **156 / 156** | 0 |
| lunch 2026-08-09 | 74 / 98 | 24 |

At the tick the prompt named — **BG 237, Ra 4.59, COB 0, 12:36 on 2026-08-10** — the tree reported
`hypo_risk 0.06`, `activity 0.00` (steps15 = 0, HR 82), and `post_activity 0.79` made **entirely of
`effort_recent = 0.79`**; the biometric `exercise_afterburn` was only 0.20. The `effort_recent` memory
came from steps15 = 390–453 at 12:02–12:12 with HR 74–84 — walking to lunch, scored `effort = 1.00`.
So a meal branch at confidence 1.00 and a digestion branch at 1.00, on a trunk already
`DIGESTION_ACTIVE`, lost to a 120-minute activity memory of a walk.

## AQ2. The dinner switch at 21:12 — Part A-ter's reading was wrong

Part A-ter says the mode stayed `ACCEPT` at 21:12/21:17 "because the MPC was not asking above the
1.20 cap". It was: `v3_smb_before_u` = **1.600** against a SOFT catalogue cap of **1.200**. The real
reason is the third term of `liftEligible`: `meal_certainty.level` was **`LOW`** on every dinner tick
(BG peaked at 199, and the effort veto was on), so `mealCertaintySupports` was false.

## AQ3. Three independent blockers, all rooted in the same signal

`HarmoniaSmbArbiter.decide` needs `softMeal && riseConfirmed && mealCertaintySupports &&
wantsMoreInsulin && max(mpc, before) > softCap`. Measured at 12:36 on 2026-08-10:

| term | value | why |
|---|---|---|
| `softMeal` | ✅ SOFT 1.20, `MEAL_UNDECLARED_FAST` | — |
| `max(mpc, before) > softCap` | ✅ 1.60 > 1.20 | — |
| `wantsMoreInsulin` | ❌ intent `PROTECTIVE` | AQ1, gate 2 |
| `mealCertaintySupports` | ❌ forced `false` | `RecursiveBeliefResolver.kt:778` |
| `riseConfirmed` | ❌ forced `false` | same |

The two forced values come from `channelOpen`, which required `basalFirstChannel == NONE`. On these
ticks the channel was `HARMONIA_PRODUCTION_BASAL_FIRST`, granted by
`allowsHarmoniaBasalDuringSoftMealSupport` — the exception added so TBR could still act on a soft
meal. **It requires `releaseAuthority == SOFT`, i.e. RBT still holds SMB authority; it was never meant
to revoke the SMB lift, and that is exactly what it did.** `BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST`
is the dominant blocker of the whole corpus: 110 / 133 / 144 ticks per day, and every tick of both
lunch rises, with `effective_authority` = `SOFT` throughout.

`MealCertainty` already computes the right answer and is simply not consumed by the tree: it reached
**`HIGH`** (`supportsMealOverProtective`) at 12:32–12:56 on 2026-08-10 (BG 216 → 269) and across
14:17–15:06 on 2026-08-09. Its KDoc already says *"digestion + strong rise → meal support beats
protective"*. The tree is built before it, so the intent never sees it.

## AQ4. What was changed

All uncommitted. Two groups: the governed-path unblocking (A), and the change that actually moves the
dose (B), plus the instruments (C).

**A — make `LIFT_WITHIN_ENVELOPE` reachable on a confirmed meal**

| file | change |
|---|---|
| `patient/PhysiologicalTree.kt` | `resolveInsulinIntent` takes `mealOverridesProtective`; when true, **gate 2 only** is skipped — gate 1 is untouched. New `PhysiologicalTreeBuilder.withMealCertainty(...)` re-resolves the intent after `MealCertainty` exists. |
| `DetermineBasalAIMI2.kt` | calls it right after `lastMealCertainty` is set, gated on `mealCertainty.supportsMealOverProtective`; logs `🌳 TREE_INTENT`. Also passes `autodriveEngine.lastCbfPermittedU` into the RBT context. |
| `recursive/RecursiveBeliefResolver.kt` | the soft-meal basal exception no longer closes the SMB arbitration channel: `basalFirstClosesSmbChannel` is true for T3C, and for Harmonia only when `releaseAuthority != SOFT` (the classic mutex). |
| `patient/HarmoniaSmbAuthorityDecision.kt` | `decide` takes `barrierPermittedU`; it bounds the **LIFT only**, and never below `demandBeforeU`. |

Bounds that already existed and are preserved: the envelope is `min(maxSmbEffectiveU, iobHeadroom)` and
`maxSmbEffectiveU = maxSMBHB.coerceAtLeast(maxSMB)` — **a LIFT already reaches the full `maxSMBHB`**,
so nothing had to change for the patient's decision on that point. HARD catalogue caps re-bind after
the arbiter (`RecursiveBeliefResolver.kt` terminal `hardBindingCapU()`), and everything still passes
through `finalizeAndCapSMB` / `applySmbUnits`, so a lift cannot reach the pump past the seal.

The barrier bounds the lift and not the whole dose on purpose: A-bis correction 5 measured that
clamping the dose to the barrier removes 38 % of a day's SMB, and `cbf_permitted_u` is 0.00 on 46 %
(lunch package) to 58 % (dinner package) of ticks. On the 2026-08-10 lunch rise it was 1.68–2.00 U, so
the bound costs nothing there.

**B — the effort SMB floor on a certain meal (patient decision, 2026-08-11)**

`MealCertaintyBuilder.effortSmbFactorFor(certainty, requestedFactor)` floors the effort multiplier at
**0.75** when — and only when — `MealCertaintyLevel.HIGH` holds. `finalizeAndCapSMB` calls it in place
of reading `lastEffortAssessment.smbFactor` directly.

The floor is a **mitigation, not the fix** (see AQ7). It cannot bypass anything: it is at most 1.0, so
the post-effort dose never exceeds the pre-effort value, which the HARD catalogue caps, the
barrier-bounded arbitration and the seal have already bounded. `HIGH` already requires
`DIGESTION_ACTIVE`, an OK rise, BG above the meal band and terminals with no hypo conflict, so effort
protection is untouched outside a confirmed meal — the 2026-08-10 dinner never reached `HIGH` and does
not move at any floor value.

**C — the instruments that were missing**

Two blind spots have each cost one wrong attribution. Both are now written in
`markEstimatorDiagnosticsForExport`, the one point every export path goes through (the fix from A-bis
correction 1), and the effort fields are reset at tick bootstrap so a basal-only tick exports null:

| field | why |
|---|---|
| `effort_smb_factor_requested` / `_applied` | the multiplier was only recoverable by parsing the narrative — the reason AQ5's 7.01 U is a reconstruction |
| `effort_smb_before_u` / `_after_u` | brackets the reduction in units |
| `effort_smb_floored_by_meal` | did the confirmed-meal floor bite this tick |
| `rise_floor_spent_u` | the episode-budget state A-ter had to infer |
| `rise_floor_minutes_since_contribution` | idle time against `RISE_FLOOR_REARM_MS` |

Suite: **1345 tests, 0 failures** (`:plugins:aps:testFullDebugUnitTest`). New tests:
`HarmoniaSmbArbiterTest` ×5 (full-ceiling lift, barrier bound, barrier never lowers, null barrier,
`MEAL_SUPPORT` lifts), `PhysiologicalTreeBuilderTest` ×4 (the 12:36 veto reproduced, the override, the
no-op case, and that the hypo gate still wins), `MealCertaintyBuilderTest` ×4 (floors only at `HIGH`,
never raises above what effort asked, always a reduction incl. NaN and >1 inputs, and a total effort
stop still keeps a quarter on a certain meal).

## AQ5. What it would have delivered — and the number that actually matters

**It delivers essentially nothing, and the reason is worth more than the change.**

`mpcDemandU` passed to the arbiter is `ctx.v3SmbU`, set at `DetermineBasalAIMI2.kt:5235` to the same
`v3Smb` that becomes `htr.v3SmbBeforeU`. The lift's output can therefore only exceed the baseline when
the pre-arbiter RBT demand already exceeds the V3 command, and `rawLifted` keeps
`max(v3SmbBeforeU, …)` regardless. Measured inside the meal windows, `demand_before_u > v3_smb_before_u`
on **1 tick of each lunch** (+0.15 U and +0.28 U *pre-terminal*, less after the terminal chain).

> **`LIFT_WITHIN_ENVELOPE` is a restore mechanism for RBT's own demand, not a lift above the model.**
> No calibration of the intent threshold can make it carry a meal. The name is misleading.

The real limiter, measured per tick from `MEAL_PRIORITY_CHAIN` in the narrative:

```
2026-08-10 12:36  proposed=1.60 baseLimit=1.60 safety=1.36 throttle=1.09  effort×0.56 → 0.61
2026-08-09 14:41  proposed=1.60 baseLimit=1.60 safety=1.36 throttle=1.09  (no effort)  → 1.09
```

Identical chain, identical throttle. The only difference is `EFFORT_PROTECT_SMB`
(`DetermineBasalAIMI2.kt:13183`), applied last, after even the `hyperReleaseFloorU` restore.

| meal window | delivered | effort-multiplied ticks | factors | U removed by effort (reconstructed) |
|---|---|---|---|---|
| lunch 2026-08-09 | 14.09 U | **0** | — | 0.00 |
| lunch 2026-08-10 | 6.81 U | **14** | 0.45–0.56 | **7.01** |
| dinner 2026-08-10 | 9.17 U | 11 | 0.68–0.85 | 2.67 |

**6.81 + 7.01 = 13.82 U against 14.09 U.** The 14.06 → 6.53 collapse is the effort multiplier, to
within 2 %. **Part A-ter's attribution to the episode budget on `aggressiveRiseSmbFloorU` is not
supported** — that budget was never exported, its absence was inferred, and the effort factor explains
the whole gap. Removing the budget was still the right call for other reasons; the reasoning given for
it was wrong.

**Status of these numbers — read this before quoting them.** The **mechanism is measured**: the two
lunches ran the identical cap chain and `meal_certainty.effort_veto` is true on **0 / 19** ticks of the
2026-08-09 lunch and **14 / 16** of the 2026-08-10 lunch (independently confirmed from the export).
The **7.01 U is a reconstruction, not a measurement**: `EFFORT_PROTECT_SMB` had no before/after export,
so the factor was recovered by parsing `🏃effort×…` out of the narrative and the units were
back-computed as `amount / factor − amount`. It is also open-loop — per tick, no IOB feedback — and a
higher SMB would raise IOB and re-enter the chain on later ticks, so treat it as an upper bound. The
export added in AQ4-C is what settles it on the next package.

## AQ6. The effort floor — the numbers behind the chosen 0.75

The target — between 6.53 U and 14.06 U — is reachable only through the effort multiplier, gated on a
confirmed meal. Modelled per tick on the 2026-08-10 packages, flooring `effortFactor` **only when
`MealCertainty.level == HIGH`**:

| floor | lunch 2026-08-10 | dinner 2026-08-10 |
|---|---|---|
| none (as delivered) | 6.81 U | 9.17 U |
| 0.65 | 7.75 U | 9.17 U |
| **0.75 — chosen by the patient 2026-08-11** | **8.40 U** | 9.17 U |
| 0.85 | 9.06 U | 9.17 U |
| 1.00 (no effort cut on a HIGH meal) | 10.04 U | 9.17 U |

The dinner is untouched at every floor because it never reached `HIGH` — the selectivity is right on
its own: it relaxes at BG 216–269 on a confirmed hyper meal and not at BG 179. Same reconstruction
caveat as AQ5.

**Attribution risk, accepted by the patient.** This ships in the same install as
`OApsAIMIHighBGMaxSMB` 1.6 → 2.2. Two dose-moving changes at once is the error Part A-ter records as
having been made twice; the patient accepts it and will disentangle from the next package. The new
exports make that possible: `effort_smb_factor_requested` vs `_applied` isolates the floor's own
contribution independently of the ceiling change.

## AQ7. Still open

- **The upstream defect is untouched, and the floor is a mitigation, not the fix.**
  `EffortActivityBelief` scored `effort = 1.00` on steps15 = 156 (≈10 steps/min) and kept
  `effort_recent = 0.96` two hours after a walk to lunch at HR 74–84. Until that is calibrated, a
  confirmed meal is still fighting a false exercise belief in three places — the tree's intent gate,
  `MealCertainty.effortVeto`, and the terminal multiplier — and the 0.75 floor only softens the third.
  The fix belongs in `EffortActivityBelief`, which is the single activity path; **do not add a parallel
  one.**
- `LIFT_WITHIN_ENVELOPE` is now reachable but remains a restore mechanism. If a genuine lift above the
  MPC command is wanted, that is a different change and needs its own justification.

## AQ8. What the next day's data must answer

The build to install carries: the intent override, the channel fix, the barrier-bounded lift, the 0.75
effort floor, and `OApsAIMIHighBGMaxSMB` = 2.2. From the next package, with meals timestamped:

| question | field(s) | what would confirm it |
|---|---|---|
| Does the floor fire, and only on a certain meal? | `effort_smb_floored_by_meal`, `effort_smb_factor_requested` vs `_applied` | true only on ticks where `meal_certainty.level == HIGH`; `_applied` = max(`_requested`, 0.75) exactly |
| Is the 7.01 U reconstruction right? | `effort_smb_before_u` / `_after_u` summed over the meal window | replaces the narrative-parsed estimate with a measurement |
| Does the intent actually switch? | `physiological_tree.insulin_intent`, console `🌳 TREE_INTENT` | `NEED_MORE_INSULIN` on the HIGH ticks of a rise, `PROTECTIVE` retained on real activity |
| Is the SMB channel open on a meal now? | `recursive_belief.resolution.harmonia_smb.dominant_blocker` | `BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST` should fall sharply on SOFT meal ticks |
| Does a LIFT ever fire, and does it add anything? | `harmonia_smb_authority.mode`, `reason_codes` incl. `LIFT_BARRIER_BOUND_*` | if `LIFT_WITHIN_ENVELOPE` appears with `demand_after_u` ≤ `v3_smb_before_u` on every tick, AQ5's restore-mechanism finding is confirmed in production |
| Did the rise floor carry anything? | `rise_floor_spent_u`, `rise_floor_minutes_since_contribution` | settles A-ter's inference with a measurement at last |
| Attribution: floor vs the 2.2 ceiling | `physiological_patterns.max_smb_hb_u` alongside the effort fields | separates the ceiling's effect from the floor's |

**Do not change the Ra gates or `MPC_TAU_MIN` on this cycle** — three dose-moving changes would make
none of the above attributable.

---

# Part A-quinquies — the morning under-correction, traced (2026-08-13)

Written 2026-08-13 from package `1786605720362` (292 ticks, 24 h to 09:22) plus the three earlier
packages, 952 distinct ticks pooled. The patient reported that the evening before and that morning
"did not correct enough". **This part supersedes Part A-quater's picture of where the dose is lost,
and it corrects two attributions made during this session itself.** All changes below are
uncommitted. Suite: **1393 tests, 0 failures** (was 1345; this part adds 48).

## AQ5-1. The day

| window | BG | SMB | outcome |
|---|---|---|---|
| 12 Aug morning | 166 → **297** | 9.94 U / 22 ticks | plateau ~1.5 h |
| 12 Aug lunch | 126 → 206 | 6.13 U | handled |
| 12 Aug dinner | 117 → 247 → 164 | 10.30 U | handled |
| 13 Aug morning | 94 → **243** | **4.92 U / 15 ticks** | still 241 at 09:16, IOB 6.2 |

Day: 9.1 % below 70, 24.1 % above 180, min BG 45. The evening worked; the two mornings did not.

## AQ5-2. Root cause of the mornings: one mis-classification, five consequences

`PhysiologicalPhase.STRESS_CORTISOL` was applied to both breakfasts. `PHYSIO_CAP` (its
`smbFloorCapU = 0.75`) was the binding stage on **12 of 15** ticks on 13 Aug and **14 of 22** on
12 Aug.

The trigger is `isStressCortisol`: `heartRateBpm > restingHeartRateBpm + 12`. Measured RHR is **49**,
so the test is true above 61 bpm — **every waking heart rate** (observed 72–96). The natural
experiment is on 12 Aug: at 09:22/09:26 the watch reported no HR, the phase was `MEAL_UNDECLARED`
and the doses were 1.50 and 2.20 U; at 09:31 HR 94 arrived, the phase flipped, and the dose fell to
0.23 U for 70 minutes while BG went to 297. **The dose fell tenfold because a heart rate appeared.**

One classification bites in five places, all verified in code:

```
STRESS_CORTISOL
 ├─ smbFloorCapU 0.75                          (BehavioralRiskPolicy.forPhase)
 ├─ mpcInsulinCostMultiplier 2.5 / maxSmbFraction 0.65
 ├─ maxHtrTier EMERGING
 └─ toPatternId() → STRESS_CORTISOL_ACUTE
      → CausalStatePosterior.buildStressResistanceProb → stress branch 0.997
        → PhysiologicalTree.isConflicting (meal 1.00 vs stress ≥ meal − 0.04) → trunk MIXED
          → MealCertainty.treeStateOf(MIXED) = NONE → level never HIGH
             ├─ the A-quater intent override is unreachable
             └─ the A-quater 0.75 effort floor is unreachable
```

The evening proves the same code works when the root is right: meal 1.00 vs stress 0.483 → not
conflicting → `DIGESTION_ACTIVE` → HIGH → the floor fired on 4 ticks → 1.1–1.75 U per tick.

## AQ5-3. The cortisol discriminator, measured

14 distinct `STRESS_CORTISOL` episodes over 952 ticks, labelled from the BG trajectory (not from the
classifier's own opinion):

| group | n | excursion | peak Δ per 5 min | duration |
|---|---|---|---|---|
| genuine cortisol | 11 | 0–26 mg/dL | 1.5–**9.1** | 0–30 min |
| food | 3 | 112 / 140 | 12.9 / 20.1 | 65–70 min |

Threshold search, requiring the gate to fire at the *start* of the rise:

| candidate | food caught | cortisol false |
|---|---|---|
| Δ ≥ 9 | 2/3 | 1/11 |
| Δ ≥ 10, 11 or 12 | 2/3 | **0/11** |
| Δ ≥ 11 **or** (shortΔ ≥ 5 **and** dev ≥ 1.6 × highBand) | 2/3, both at their first rising tick | **0/11** |

`highBand = OApsAIMIHighBg 140 − target 90 = 50`, so the level clause is BG ≥ 170. The third "food"
episode is a single tick at BG 297 where the phase flickered mid-event; the main episode already
fired. Counterfactual across the cortisol episodes: **14.80 U → 27.22 U** (+12.42 U, 26 ticks),
open-loop upper bound.

**`isAcuteMealSurgeAtDawn` cannot be the escape** — its first clause `dev >= highBand * 0.45` is
BG ≥ 112, which fires on **11 of 11** genuine cortisol episodes. Making the existing dead escape
reachable would have deleted the cortisol path. This is why the escape is a new, tighter test.

## AQ5-4. The hysteresis hold, which would have made the escape worthless

`EndogenousPhaseHysteresis.stabilize` arms `HOLD_TICKS_DEFAULT = 4` on **every** `STRESS_CORTISOL`
tick, then re-applies the held phase — through `BehavioralRiskPolicy.forPhase(STRESS_CORTISOL, …)`
and its 0.75 cap — to the next four `MEAL_UNDECLARED` / `OFF` ticks. Because both breakfasts were
cortisol on the ticks *before* the steep rise, the escape would have been suppressed from 08:11 to
08:26 and first bitten at 08:31, BG 201. **A correct classifier fix would have delivered nothing for
the first 20 minutes of the meal.** Fixed by `Output.breaksEndogenousHold`, set when a steep rise
takes the tick out of the cortisol family; `stabilize` drops the hold on it. The hold still damps
ordinary flip-flop.

## AQ5-5. The effort multiplier — AQ5's reconstruction replaced by measurement

The A-quater exports settle AQ5's 7.01 U estimate. Over 24 h: **the effort multiplier removed
21.71 U**, against 42.26 U delivered. Reduced on 82 of 107 ticks, minimum 0.45. **61 of those 82 had
no live movement** (`effort=0.00`), median 12 steps per 15 min. The confirmed-meal floor fired on
**6 ticks of 107**, and `MealCertainty` reached HIGH on only 10 of 292 — the floor is not a
substitute for fixing the belief.

Two defects, both now fixed:

- **Re-arm.** `stepRate = max(rate5, rate15)` with `rate5 = stepsLast5m / 5`, so ~125 steps in any
  one 5-minute window re-stamps `lastEffortMs` and restarts a 120-minute protection at full
  strength. Observed on 12 Aug: the factor decayed 0.45 → 0.54 then jumped back to 0.45. **Proven,
  not inferred**: 12 ticks had `activity_state = ACTIVE` with `steps15 < 375`, which forces
  `steps5 ≥ 200`, and all 12 had a 15-minute rate of only 13.7–22.8 steps/min. Every provable burst
  in the corpus was uncorroborated at 15 minutes. Fix: onset stays fast (the 5-min window may open
  the current reduction) but only the **15-min** window may arm the cross-tick memory.
- **Decay.** Flat 120-minute linear horizon. Now scaled by how long the corroborated effort lasted,
  30 → 120 min; a ≥30-min session reproduces today's behaviour exactly.

Result: **21.71 U → 14.45 U, 7.26 U returned**, reduced ticks 82 → 42, **zero ticks reduced more
than today, and no tick with real movement loses protection**. Residual left on purpose: 2.97 U over
9 single-tick `ACTIVE` bursts, kept to preserve fast onset; `hrConf` (up to 0.50 of strength) is what
saturates them, and HR is measurably a poor discriminator here — the 25 bpm elevation threshold is
crossed at the *median* of the 1–10 steps/min band.

**Same defect family as the cortisol HR test: a threshold calibrated against a sleeping baseline.**

## AQ5-6. Why lunch and dinner start late — and two wrong answers on the way

The patient's second question. **Three attributions were made in sequence; the first two were wrong
and both were mine.** Recorded because the doc already shows this exact question being mis-answered
twice before.

**Wrong answer 1 — the BG-keyed ceiling.** Median `max_smb_high_bg_u` is 0.05 U up to BG 159, 0.22
at 160–179, 2.20 from BG 200, which looks like a purely reactive ceiling. It is not the cause: the
`MAXSMB_*` selection actually picks the 1.8 U preference at BG 109, and something overrides it
afterwards.

**Partly right — basal-first.** `basalFirstActive` sets `maxSMB = 0.0; maxSMBHB = 0.0` and is gated
on `bg < 110.0`, and every meal starts below 110, so the first tick of every meal has zero SMB
authority by construction. Measured per tick: lunch and breakfast were blocked by **Learner
Prudence**, dinner by **Fragile BG**. The existing exemption `isPersistentRise` has a *level* clause
(`bg > targetBg`), unreachable at BG 103 against a target of 110, and `isConfirmedHighRise` needs
BG > 150, structurally dead below 110. Fixed with an anticipated-rise exemption (rate + 30-min
projection, floor at BG 90 to exclude rescue rebounds). **But it is worth only ≈0.4 U across the
three meals** — not the gate.

**Wrong answer 2 — the tube veto.** `StraightLineTubeAdvisor` infeasible sets
`maxSMB = maxSMBHB = 0.05`. The correlation is real and strong: the 0.05 ceiling on 80 ticks,
`tubeCap=0,00` on 81, intersection 80 — 28 % of the day, including the first rising tick of lunch and
dinner. I called it the dominant gate. **It is not.** Measured:

- Feasibility is *exactly* `minPred < hypoFloor` — the candidate ladder ends at `s = 0.0`, where
  `minAfter == minPred`, so the tube runs **no projection of its own**. It restates an upstream
  forecast.
- Of 53 veto ticks, **38 are falling** (Δ5 ≤ −3) with 5–12 U IOB on descents that reached 41–76
  mg/dL within the hour, and 14 are flat with real IOB load. **52 of 53 vetoes are correct**; the one
  wrong veto is 12 Aug 13:16. Removing the veto would have made those descents worse.
- A graded cap on the infeasible branch is arithmetically impossible: headroom is negative by
  construction, `max(minPred − floor) = −3.0` mg/dL, so a headroom-graded cap yields **0.000 U**.
- The remaining 28 zero-cap ticks are *not* vetoes: 17 are a degenerate objective (`W_BG·bgErr²` and
  `W_EV·evErr²` do not depend on `s`, so with `hyperExcess = 0` the cost reduces to a leaky
  integrator decaying to `0.4 × lastScale` and then to zero), 5 are a quantisation dead band, 6 are
  unexplained by the export.

**And the earlier `minPred = 39` attribution is also only partly right**: `minPred == 39` on 53 of
292 ticks but co-occurs with the veto on only 37 of 81, and at meal onset the veto fired with
`min_pred_mgdl` of 64.3, **73.7** and **116.8** — the last two *above* the 70 floor. The tube ran on
an earlier snapshot than the one exported.

**The real gate is the prediction, and it is still open.** A forecast cannot coherently report a path
minimum of 73.7 while its own terminal is 376 and BG is rising 18 mg/dL per 5 min. Nothing was
changed in the tube; observability was added instead (`adjustments.tube_advisor` with
`deciding_stage`, `branch`, `min_pred_used_mgdl`, `snapshot_source_used`, `kappa_mgdl_per_u`,
`s_max_feasible`, `max_smb_baseline_u`), which settles the 6 unexplained ticks by measurement next
package. **No dosing path was touched by that change.**

## AQ5-7. What changed (all uncommitted)

| file | change |
|---|---|
| `physio/PhysiologicalPhaseClassifier.kt` | `isTooSteepForCortisolAlone`; applied at **both** cortisol sites and to the `morningChrono rebound` branch; `classify` wraps `classifyPhase` to stamp `breaksEndogenousHold` |
| `physio/EndogenousPhaseHysteresis.kt` | the hold releases on `breaksEndogenousHold` |
| `activity/EffortActivityBelief.kt` | memory arms only on 15-min corroboration; horizon scales with effort length; non-finite inputs fail open at 1.0 |
| `DetermineBasalAIMI2.kt` | `BasalFirstPolicyMath` extracted pure and testable, plus the anticipated-rise exemption; `adjustments.tube_advisor` export |
| `control/StraightLineTubeAdvisor.kt` | `Branch` enum and diagnostic fields on `Outcome`; `sMaxFeasible` computed **for export only**, not fed back into the ladder |

48 new tests, including the regression guards that matter: a genuine dawn cortisol ramp is still
`STRESS_CORTISOL`; a sustained walk still produces full protection; a falling or flat BG below 110
still gets basal-first with SMB off; every chosen tube scale still respects the hypo floor.

## AQ5-8. What the next package must answer

| question | field(s) | what would confirm it |
|---|---|---|
| Does the cortisol escape fire on breakfast only? | `physiological_phase.phase` / `reason` in the morning | `MEAL_UNDECLARED` on the steep ticks, `STRESS_CORTISOL` retained on modest dawn ramps |
| Is the hysteresis hold still releasing correctly? | phase on the 4 ticks after a cortisol tick | no cortisol policy on a steep rise; hold still visible on ordinary flip-flop |
| Did the effort fix return the units? | `effort_smb_factor_requested` vs `_applied`, `_before_u` / `_after_u` | ≈7 U less removed; no reduction on still ticks; full reduction during real movement |
| Does the basal-first exemption fire? | `[Basal-First: rise exemption]` marker | present on meal-onset ticks, absent on falling ticks |
| **Which snapshot froze the tube?** | `tube_advisor.min_pred_used_mgdl` vs `dose_terminal_snapshot.min_pred_mgdl`, `snapshot_source_used`, `branch` | settles the 6 unexplained ticks, incl. dinner 20:52 |
| Does `MealCertainty` reach HIGH on breakfast now? | `meal_certainty.level`, `physiological_tree.trunk.global_state` | `DIGESTION_ACTIVE` instead of `MIXED` once the stress branch is not inflated |

**Next ticket, ahead of any further tuning: the prediction that populates `minPredictedBg`.** It is
the gate on 28 % of ticks, the tube is a faithful messenger for 52 of its 53 vetoes, and shooting the
messenger buys ≈0.2 U while removing a working hypo defence.

**Attribution risk.** Four dose-moving changes ship together here (cortisol escape, hysteresis
release, effort calibration, basal-first exemption). That is the error this doc records twice. They
were taken together because they are one causal chain and three of them are individually inert — but
the exports above are what make them separable, and the effort fields separate cleanly from the
phase fields.

---

# Part B — prompt for the next session

Paste everything below.

---

## Role and standing rules

You are acting as **senior software architect and senior Kotlin engineer** on OpenApsAIMI, a fork of
AndroidAPS. This is a full closed loop that reaches a real insulin pump. A defect causes
hypoglycaemia. Global coherence of the decision architecture matters more than any local fix.

Read `docs/AIMI_NEXT_SESSION.md` Part A first — it is the evidence base, with measured numbers. Then
`docs/adr/` (especially `0008-isf-decision-architecture.md`), `docs/AIMI_ARCHITECTURE_MAP.md`,
`docs/AIMI_ROADMAP.md`.

**Non-negotiable:**

- `CLAUDE.md` applies in full. Repeat its bash rules verbatim in **every** agent prompt you write:
  never `cd && cmd` or `cd; cmd`; never start a command with `awk`, `cut`, `tr`, `sort`, `uniq`,
  `diff`, `which`, `chmod`, `tar`, `pip`, `npm`, `yarn`; use `git -C <abs path>`; allowed starts
  include `git`, `grep`, `find`, `sed`, `head`, `tail`, `cat`, `ls`, `wc`, `python3`, `echo`,
  `./gradlew`.
- **Do not commit or push** until explicitly asked.
- **Do not change code without confirmation**, except where the user says "do it" / "vas y".
- Gradle: `--no-daemon`, redirect to a log (never pipe — a pipe hides the real exit code), then grep
  for `^e: ` and `BUILD FAILED` / `BUILD SUCCESSFUL`.
- The support packages and `autodrive_dataset.csv` contain **personal health data**. Read them where
  they are. Never copy them into the repo, never paste their contents into a commit, an issue or any
  shared document. Aggregate statistics are fine.
- **Measure, do not reason from medians.** Every quantitative claim in Part A was computed from the
  corpus. Hold yourself to the same standard, and say plainly when you have not measured something.
- Do not take an agent's report at face value. Verify its decisive claims against the code yourself
  before acting on them or relaying them.

## Objective

Updated 2026-08-10 after the production day. The session is done when **all** of these hold:

1. The 2026-08-09/10 work is **confirmed from a clean production day** — one where the app is not
   restarted mid-meal. Specifically: the `ESCAPE_RISE` guard leaves `FULL` at the budget, the rise
   floor serves one prebolus per rise, `smb_seal_refused_count` is observable, and the pattern
   catalogue reports `smb_cap_fraction` against `max_smb_hb_u`.
2. The two calibration questions of A5 are **answered with numbers** — they still are not, because the
   shadow reached 7 ticks out of 93 on 2026-08-09 (see A-bis).
3. The barrier's insulin model is decided. Until `MPC_TAU_MIN` is right, the barrier cannot be made
   binding and the floor cannot be clamped to it — see A-bis correction 5.
4. The full module suite passes: `./gradlew :plugins:aps:testFullDebugUnitTest --no-daemon`.
5. `docs/adr/0008-isf-decision-architecture.md` reflects the code — its chain map still stops before
   the sensitivity floor, and the `safetySi` anchor is a construct step 3 removes.

## Agent split

Give each agent the bash rules, the data-privacy rule, and a pointer to Part A **and A-bis**. **Order: Agent 0 first** — it is
listed below Agent 2 for historical reasons but it outranks them, because until the meal intent can
switch, every other bound on the SMB floor starves meals. Then Agent 1 on the next support package,
then 2, 3, 4 in parallel.

### Agent 1 — `data-validation` (blocking)

Analyse the next support package, from a day with **no mid-meal app restart**, with meals timestamped.

- Confirm the five items of objective 1 above, each as confirmed / refuted / not observable, with the
  field and the count.
- Answer the two calibration questions. For the Ra shadow: distribution inside meal windows versus
  outside, and the crossing rate at 0.6 / 0.7 / 0.8 in each. The question is whether the aligned term
  separates a meal from the mere presence of IOB — on 2026-08-09 it reported 0.60–0.79 on flat glucose
  with 5.5 U on board and a real Ra of 0.00, which is the failure mode, on 7 ticks only.
- For the barrier: `cbf_permitted_unfloored_u - cbf_permitted_u` as a ratio, with
  `cbf_profile_isf_mgdl` alongside.

### Agent 2 — `barrier-calibration` (the one that unblocks the rest)

The barrier is the natural bound on the rise floor and cannot be used as one today: its `lfh` was
wrong by ~12 mg/dL/min during the 2026-08-09 meal, it says zero on ordinary rises (BG 130 → 151 with
4–6 U on board), and clamping to it would remove 38 % of a day's SMB.

- Establish what `MPC_TAU_MIN` should be, from data rather than from the 150–200 min literature range.
  `InsulinActionModel` states the calibration as a time constant precisely so this is one number.
- Quantify what each candidate does to the barrier's binding rate and to the delivered dose, on the
  corpus, before proposing a change. Note that τ = 75 reproduces the pre-unification behaviour exactly
  at ISF 45 and that the `LEGACY_CONTROL_COEFFICIENT` floor currently guarantees the refactor can only
  tighten.
- Only once that number is settled: propose clamping the rise floor to the barrier's verdict, with the
  measured cost.

### Agent 0 — `meal-intent` (runs before everything else; see Part A-ter)

`harmonia_smb_authority.insulin_intent` is `PROTECTIVE` on every tick of an undeclared meal, including
at BG 269 with Ra 5.33. That branch of `HarmoniaSmbArbiter.decide` can only ACCEPT or REDUCE, so
`LIFT_WITHIN_ENVELOPE` — the governed equivalent of the crude SMB floor — is structurally unreachable.

- Trace `insulin_intent` back to its producer. Establish whether `PROTECTIVE` is a deliberate
  classification for high IOB, a threshold miscalibration, or a path that simply never selects the
  meal intent in this state.
- State what would have to change for BG 237 with Ra 4.59 and no COB to produce `MEAL_SUPPORT`.
- Implement the smallest change that makes the LIFT reachable on a confirmed meal, bounded by the
  catalogue cap and the barrier. Then quantify, on both corpora, what it would have delivered: the
  target is **between** 6.53 U and 14.06 U, not at either end.
- Export `riseFloorSpentU` and the time since the last floor contribution. Its absence is why the
  2026-08-10 diagnosis rests on inference rather than measurement.

### Agent 3 — `harmonia-authority`

Harmonia computes on 79 % of ticks and reaches the pump on **9 of 292 (3 %)**.

- `HARMONIA_SMB_NO_RBT_AUTHORITY` on 64 % of ticks, and `requested_authority` is `NONE` on 85 % with
  reason `NO_RELEASE`. The gate is **not** truncating — `requested == max_allowed == effective`. RBT
  simply never asks. Find out why `NO_RELEASE` dominates, and whether that is intended.
- Harmonia's **basal** channel is blocked by **SMB-channel** conditions: `smb_zeroed_by_safety` 39 %,
  `smb_already_requested` 19 %, `smb_authority_active` 1 % — 60 % of its blocks. Reducing a basal rate
  and refusing a bolus are different decisions. Confirm and propose.
- `eligible` is true on 166 ticks and only 9 reach the pump. Trace the other 157.

### Agent 4 — `hygiene-and-observability`

Unchanged from the original list: dead constructor dependencies, workers swallowing failures,
`AutodriveAuditor`'s `isfRatio` unit mismatch (re-check the arithmetic — the sensitivity floor is
gone), per-learner liveness in the export, and the three dormant ML components.

## Loop protocol

Run in loop mode. Each iteration:

1. Pick the highest-priority open item. P0 before P1; anything blocking Agent 1's verification first.
2. Do the work. Compile and run the module suite before reporting anything as done.
3. Report: what changed, what was measured, what is still open. Distinguish "tests pass" from
   "confirmed in production" — never merge the two.
4. Update the task list in this file's Part B, so the next iteration starts from truth.

**Ask the user when, and only when:** two readings of a requirement lead to materially different
work; a change would move the dose and no measurement can settle it; or a measurement contradicts
Part A. Do not ask for permission to continue, and do not ask questions you can answer by reading the
code or the corpus.

**Stop when** the five objective conditions hold, and say so plainly with the evidence for each. If
one cannot be met, finish everything else in full and state exactly what is left and why — do not
scale the objective down silently.

## Known-flaky

`aimiNeuralNetworkTest > test training reduces loss()` fails intermittently in the full suite and
passes 3/3 in isolation. Unrelated to this work. Do not chase it; do not let it mask a real failure
either — always list the failing test names, never just the count.
