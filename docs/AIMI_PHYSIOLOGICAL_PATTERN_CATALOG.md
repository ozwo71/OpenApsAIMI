# AIMI Physiological Pattern Catalog

Companion to [AIMI_RECURSIVE_BELIEF.md](./AIMI_RECURSIVE_BELIEF.md) §15 (meta leaves) and
[AIMI_PHYSIOLOGICAL_PHASE.md](./AIMI_PHYSIOLOGICAL_PHASE.md) (single-label phase classifier).

## Purpose

AIMI must **understand the patient's body**, not only instantaneous BG geometry. The pattern
catalog bridges:

- **Tick-sync signals** — CGM deltas, IOB, meal absorption phase, physiological phase classifier
- **MTR async context** — sleep debt, HRV deviation, physio state (`PhysioContextMTR`)
- **User context intents** — illness, stress, activity

into a **multi-label snapshot** consumed by RBT (leaf credibility, paradoxes) and HTR merge
(SMB caps, meal/hyper suppression).

```
Signaux (CGM, HR, HRV, sleep, COB, IOB, context, chrono)
  → PhysiologicalPatternDetector
  → PhysiologicalPatternSnapshot (dominant + policies)
  → RBT τ=480 meta leaves + meal/hyper/wavelet scale
  → MR-7 RESOLVE → SMB/TBR caps (with physio phase + IOB stacking)
```

## Package layout

| File | Role |
|------|------|
| `PhysiologicalPatternId.kt` | 28 pattern enum values |
| `PhysiologicalPatternCatalog.kt` | Static definitions (caps, suppress flags, leaf scales) |
| `PhysiologicalPatternDetector.kt` | Multi-signal detection |
| `PhysiologicalPatternPolicy.kt` | Aggregate active readings → snapshot |
| `PhysiologicalPatternHysteresis.kt` | 20 min hold on sticky endocrine/recovery patterns |
| `PhysiologicalPatternInputBuilder.kt` | Tick input assembly |
| `PhysiologicalPatternExport.kt` | JSONL export |

## Pattern categories

### Endocrine / circadian

| Pattern | Typical trigger | Policy |
|---------|-----------------|--------|
| `DAWN_CORTISOL` | Phase classifier, 5–10h rise | SMB cap 0.55 U, suppress hyper + wavelet |
| `MALE/FEMALE_CIRCADIAN_HORMONAL` | W-cycle / chrono | SMB cap 0.60 U |
| `ENDOGENOUS_COUNTER_REGULATORY` | Post-hypo rebound, low IOB meal misread | SMB cap 0.50 U, suppress meal/hyper/wavelet |
| `NGR_NIGHT_GROWTH` | Night Δ+, no COB | Moderate hyper damp |

### Meal / absorption

| Pattern | Trigger | Notes |
|---------|---------|-------|
| `MEAL_*`, `LATE_FAT_PROTEIN` | Meal absorption engine | **Confirmed meal wave (≥0.70)** clears meal suppression |

### Stress / recovery / sleep

| Pattern | Trigger | Policy |
|---------|---------|--------|
| `POOR_SLEEP_MORNING_RISE` | 5–11h, Δ≥1.5, COB<1, sleep/HRV burden | **SMB cap 0.50 U**, suppress meal/hyper/wavelet |
| `SLEEP_DEBT`, `HRV_DEPRESSED`, `RECOVERY_NEEDED` | Wearable + MTR context | Recovery caps |
| `STRESS_CORTISOL_ACUTE`, `PSYCHOSOCIAL_STRESS` | Phase / MTR state | Hyper damp |

### Activity / insulin / context

Exercise lockout, IOB stacking surveillance, post-hypo rebound, compression artifact, context
intents — each mapped in `PhysiologicalPatternCatalog.kt`.

## RBT integration

### Meta leaves (τ = 480 min)

- `SLEEP_QUALITY`, `HRV_DEVIATION`, `SLEEP_DEBT`, `PHYSIO_MTR_STATE`, `PATTERN_RISK`

### Leaf credibility scaling

`PhysiologicalPatternSnapshot.credibilityScaleFor()` down-weights meal, hyper, and wavelet leaves
when a suppressing pattern is active (e.g. dawn cortisol → `PKPD_BEST`, `HTR_RELEASE`).

### Paradoxes

| Paradox | Resolution |
|---------|------------|
| `SLEEP_DEBT_VS_HYPER` | Pattern suppresses hyper release |
| `HRV_CRASH_VS_MEAL` | Pattern suppresses meal interpretation |
| `RECOVERY_VS_AGGRESS` | Pattern caps correction aggression |
| `EXERCISE_VS_CORRECTION` | Exercise lockout + pattern cap |

## Loop wiring (`DetermineBasalAIMI2`)

Each RBT resolve tick:

1. `PhysiologicalPatternInputBuilder.build()` — phase, MTR context, stacking, context intents
2. `PhysiologicalPatternDetector.detect()` — hysteresis + policy
3. `RecursiveBeliefTickContext.physiologicalPatterns` + `physioContext`
4. HTR merge: `min(lifted, physioCap, stackingCap, patternCap)`
5. JSONL: `adjustments.physiological_patterns`
6. Console: `🧬 PATTERN: dominant=...`

## Production incident (reference)

Morning post-hypo + cortisol + poor sleep → BG rise misread as meal/hyper → SMB stacking.

**Target behaviour:** `POOR_SLEEP_MORNING_RISE` + `ENDOGENOUS_COUNTER_REGULATORY` + IOB
surveillance → pattern SMB cap **0.50 U**, meal suppression, wavelet boost gated, RBT authority
capped (not overridden by V3 `max()`).

## Settings

No separate toggle — active when RBT runs (same prefs as recursive belief). Wavelet remains
**ON** with contextual gating via `suppressWaveletBoost`.
