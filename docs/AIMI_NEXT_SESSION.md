# AIMI — session handover, 2026-08-09

Written at the end of the morning session. It has two halves:

- **Part A — the record.** What was decided, what was measured, what was wrong and is now fixed.
  Read this first; the numbers in it are the evidence base for everything in Part B.
- **Part B — the prompt for the next session.** Paste it as-is. It defines the objective, the agent
  split, the loop, and when to stop.

Everything in Part A is committed: `eda530a545` then `6d24095023`. Working tree clean.
Full module suite at the end of the session: **1294 tests, 0 failures, 12 skipped**.

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

The session is done when **all** of these hold:

1. The four fixes of `eda530a545` + `6d24095023` are **confirmed from production data**, not from
   tests alone (A5, first table).
2. The two calibration questions are **answered with numbers** — `ESTIMATOR_TAU_MIN` and the
   `LEGACY_CONTROL_COEFFICIENT` floor — and either implemented or explicitly deferred with the
   measurement that justifies the deferral.
3. Every P0 in the task list below is either fixed or closed with evidence that it is not a defect.
4. The full module suite passes: `./gradlew :plugins:aps:testFullDebugUnitTest --no-daemon`.
5. `docs/adr/0008-isf-decision-architecture.md` reflects the code — its chain map currently stops
   before the `0.1` floor, which was the load-bearing number.

## Agent split

Spawn these four. Give each the bash rules, the data-privacy rule, and the pointer to Part A. They
work in parallel; you integrate and verify.

### Agent 1 — `data-validation` (start first; everything else waits on its findings)

Analyse the support package the user provides tonight, with the two meals timestamped.

- Verify the four fixes from A5's first table. Report each as confirmed / refuted / not observable,
  with the field and the count.
- Answer the two calibration questions. For the Ra shadow: distribution overall, and separately
  **inside the two meal windows** versus outside. The question is whether the aligned term separates
  a meal from the mere presence of IOB — report the gate-crossing rate at 0.6 / 0.7 / 0.8 in each
  window. For the barrier: the distribution of `cbf_permitted_unfloored_u - cbf_permitted_u`, as a
  ratio, and the fraction of ticks where the difference exceeds 0.1 U.
- Report absolute numbers with denominators. If a field is null everywhere, say so — that is a
  finding, not a gap.

### Agent 2 — `safety-architecture`

- **P0:** `ControlBarrierShield.enforce`, the `safeU > 0.2` branch: `tbr = min(profileBasal, raw.tbr)`
  is set **before** the budget check, then `smbBudget = safeU - tbr/12`. If `tbr/12 > safeU`, `smb`
  clamps to 0 but the delivered total is `tbr/12`, which **exceeds `safeU`**. Confirm against the
  code, quantify from the corpus how often it happens and by how much, then fix so the barrier's own
  bound holds.
- Re-measure the profile-relative exit bound on the quantity the barrier and the MPC actually read
  (`pkpdRuntime.fusedIsf`), not `oapsProfile.sens`. The withdrawn "3 %" figure needs replacing (A4.7).
- `aggressiveWindowUntilEpochMs` is set from shadow and proposal calls, before the
  `if (!state.isActive) return null`. A shadow tick can open a 12-minute aggressive window for the
  engaged controller. Confirm and fix.
- `OnlineLearner.learnAndUpdate` is stepped once per `tick()` call, not once per tick. Inert today
  only because the factor is discarded — one line from being undone. Align it with `observationId`.
- Update ADR 0008: record the `0.1` floor in the chain map, and note that the `safetySi` anchor is a
  construct step 3 removes.

### Agent 3 — `learning-pipeline` — **DONE except one item, deliberately left**

Implemented, uncommitted, compiles, `1313 tests / 0 failures / 12 skipped` on
`:plugins:aps:testFullDebugUnitTest` (was 1294 before; +19 new). Tests pass — **nothing here is
confirmed in production yet.**

Measured on the corpus before changing anything (17 068 rows, 2026-02-28 → 2026-07-11):

| measurement | value |
|---|---|
| rows with the 18-column layout (no `Engaged`, trainer reads `1.0`) | **17 068 / 17 068 — 100 %** |
| rows carrying a label, i.e. actually trained on | 17 011 |
| labelled `Hypo_Occurred = 1` | 91 — **0.53 %**, not the ~3 % assumed |
| rows whose 60-min outcome window the CSV does **not** cover continuously | **12 456 / 17 011 — 73.2 %** |
| inter-row gaps > 15 min (the old pass's give-up condition) | 1 808 / 17 067 — 10.6 % |
| re-deriving the label from the CSV's own BG series | reproduces the file **exactly** (91/91) |

That last line is the point: the censoring is **invisible from the CSV**, because the CSV is what the
broken pass read. It can only be corrected against CGM history. And since the 18-column layout and
the CGM-based labelling landed in the same commit (`1135038d55`), "has no `Engaged` field" and
"labelled by the broken method" are the **same 17 068 rows** — perfect collinearity.

- ✅ **P0 dataset confound.** New `AutodriveDatasetSchema` (single source for the header + indices,
  previously copied into three files). 20th column `Schema_Version`. v0 = 18 cols, outcomes not
  trustworthy; v1 = 19 cols, CGM-labelled, only needs the stamp; v2 = current. The backfiller blanks
  the outcome columns on v0 rows so they are re-derived from CGM, or stay unlabelled and out of
  training. Belt and braces: the trainer now **refuses** any row below v1, so `engaged` is never
  defaulted to `1.0` again.
- ✅ **Lock gaps closed** (the KDoc was right, the code was not): `trainAttentionWeights`,
  `isDatasetReadyForTraining` and the file scan inside `loadGlucoseWindow` all run under
  `AutodriveDatasetLock`. The **database** query in `loadGlucoseWindow` stays outside on purpose —
  it touches no file, and holding the file lock across a DB round-trip would expose the APS thread.
- ✅ **Blocking I/O off the decision path.** Measured: read-modify-rename over a 17 068-row / 2 MB
  file is ~14 ms of pure I/O on a desktop SSD (5 trials, 13.3–17.5 ms); an append is 0.02 ms. On a
  phone, with `readLines` into 17 000 strings, a `split` per row and the `copyTo` fallback, that is
  an order of magnitude worse and unbounded from the caller's side. `AutodriveDatasetLock` is now a
  `ReentrantLock` with `tryWithDataset` (zero timeout); the data lake takes that path and, on
  contention, **carries the row forward** in a bounded buffer (48 rows ≈ 4 h) instead of dropping it.
  Counters: `contendedWriteCount`, `deferredRowCount`, `droppedRowCount`, plus a warn log on drop.
  Not yet wired into any JSON export — see Agent 4's liveness item.
- ✅ **Header migration.** The header is rewritten from the schema on every pass, never echoed back,
  including on a file that holds only a header.
- ✅ **Trainer: kept scheduled, given a real objective.** Reasoning: even a perfect classifier can
  only ever *raise* `estimatedSI` (the permissive arm stays pinned at 1.0), which is the safe
  direction, so the pipeline is worth having — but the model it produced was not a model. On a 0.53 %
  positive class, 100 epochs at lr 0.01 from zero moves the intercept ≈ -0.45 of the -5.2 it needs.
  Now: class-balanced loss, lr 0.1 / 300 epochs, weights bounded to ±5, chronological 20 % holdout,
  and the incumbent is displaced only if the candidate beats **both** it and a base-rate predictor on
  balanced holdout log-loss by ≥ 0.005. Minimums: 500 rows, 20 train positives, 5 holdout positives.
  **Prior correction on save** — balancing calibrates to a 50 % prior, and left there the gate's
  `score > 0.5` would fire on about half of all ticks: a permanent sensitivity inflation dressed up
  as a detection. The intercept is shifted back onto the true base rate, so today's behaviour does
  not move. Feature normalisation was *not* added: measured mask ranges are [0, 1.000], [0, 0.963],
  [0, 0.990] and `Engaged` is an indicator — the features are already on one bounded scale.
  Liveness metadata written alongside the weights (rows, positives, holdout size and losses, schema
  version, timestamp).
- ✅ **`SensitivityRatioEstimator`.** COB > 0 or a bolus of **any** origin (`lastBolusMs`, not just
  `smbU`) in the window or its run-up now rejects the window — this was the dangerous one, biasing R
  low and therefore giving *more* insulin. `deliveredBasalUph` is the **running** temp basal
  (`ctx.currentTemp`, profile rate when duration is 0), not `finalResult.rate`, which is what the
  tick is about to request. EMA α is keyed on **elapsed time since the last fold** (so `TAU_HOURS`
  means what it says), with a 30-minute minimum spacing so overlapping windows cannot fold the same
  episode repeatedly, and a 2-hour cap so one window after a quiet night cannot take over. State
  (ratio, observation count, last fold) is persisted to `sensitivity_ratio_state.json` and reloaded,
  discarded if older than 7 days or stamped in the future.
- ❌ **Deliberately left: `bgModulation`.** It bakes in a BG-dependence ADR 0007 calls unmeasured.
  Changing it moves the quantity proposed to replace the commanded sensitivity, in both directions,
  and no measurement in hand settles the shape. It needs the corpus, not a judgement call.

### Agent 4 — `hygiene-and-observability`

- Dead constructor dependencies: `dataBackfiller` in `AutodriveEngine`, `context` in
  `AutodriveDataBackfiller` and `AutodriveNeuralTrainer`. Unused `LTag` imports in both workers.
- `AutodriveBackfillWorker` computes `linesModified` and discards it; both workers swallow failures
  into `Result.success()`; `processPendingLinesLocked` returns a count while swallowing a rewrite
  failure, so a caller is told N rows were backfilled when none were persisted.
- `AutodriveAuditor` computes `isfRatio = state.estimatedSI / baseProfileIsf`, a unit mismatch that
  penalises `health` on every tick. Now that the floor is gone the numerator changed — re-check the
  arithmetic before fixing.
- Per-learner liveness in the export: weights present, last-trained timestamp, sample count. Today it
  is impossible to tell "the model says safe" from "there is no model".
- `OrefPersonalMlTrainer` writes two model files nobody reads; `WCycleLearner.retrainFromCsv` has no
  caller populating its columns; `AimiSmbTrainer` only trains when `OApsAIMIMLtraining` is on. Decide
  per item: finish, delete, or document as intentionally dormant.

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
