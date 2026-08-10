# AIMI — session handover, 2026-08-09

Started at the end of the 2026-08-09 morning session, extended 2026-08-10. Three parts:

- **Part A — the record of the morning.** Decisions and measurements. Several of its conclusions are
  superseded; read Part A-bis before acting on any of them.
- **Part A-bis — the production day of 2026-08-09.** What actually happened on the pump, what caused
  it, and which of Part A's conclusions were wrong. **This is the current state of knowledge.**
- **Part B — the prompt for the next session.** Paste it as-is.

Part A is committed: `eda530a545` then `6d24095023`. **Everything in Part A-bis is uncommitted** — 18
files, +1248/-373. Full module suite: **1332 tests, 0 failures, 14 skipped**.

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

Give each agent the bash rules, the data-privacy rule, and a pointer to Part A **and A-bis**. Agent 1
runs first; the others use its findings.

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
