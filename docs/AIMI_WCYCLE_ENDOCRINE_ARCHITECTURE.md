# AIMI WCycle Endocrine Architecture

Status: **Lot A implemented** (belief + tree + Harmonia posture + JSONL).  
Dose-path owner migration (Lots B–D) is **not** applied yet — legacy `rate *= wCycle` / SMB scale remain until Lot C.

## Problem

WCycle was a parallel post-decision scaler. The physiological tree’s `hormonalResistance` ignored menstrual phase/day/amplitude. Harmonia therefore could not see luteal uplift vs hypo load, and Stability/HYPO_GUARD writebacks silently disabled T3C adaptive basal.

## Authority layers (target)

| Layer | Role | Lot A |
|-------|------|-------|
| `WCycleAdjuster` | Legacy dose multipliers (still applied) | Unchanged |
| `EndocrineAmplitudeGovernor` | Single belief + hypo-dampen math for tree/Harmonia | **Live** |
| `PhysiologicalTree.hormonalResistance` | Encodes phase + effective amp + hypo dampen | **Live** |
| `HarmoniaDecisionEngine` | Posture: BASAL_FIRST vs PROTECTIVE when hypoRisk high | **Live** |
| Harmonia production / direct scale | Dose uplift owner | Lot C (not yet) |
| Safety / LGS / Harmonizer↓ | Absolute veto | Unchanged |

## Lot A artifacts

- `WCycleBelief` — full endocrine snapshot (intended vs legacy-applied vs governor-effective)
- `EndocrineAmplitudeGovernor.from(...)` — hypo-load dampen, inflam budget visibility, application mode
- Tree + seasons consume `WCycleBelief`
- JSONL: `adjustments.endocrine_belief`
- `physio_context.cycle_phase` refreshed after WCycle resolve (was always `Unknown` when exported early)
- Adaptive basal: default remains `true`; one-shot upgrade re-enable; Stability family no longer forces OFF

## Invariants (Lot A)

1. Governor does **not** change pump doses in Lot A.
2. Tree remains context/modulation — not a dose owner.
3. When `hypoRisk ≥ 0.45` and hormonal resistance would otherwise pick BASAL_FIRST → **PROTECTIVE_REDUCTION**.
4. Safety still zeros SMB before legacy WCycle SMB scale.
5. Adaptive basal re-enable is **one-shot**; user may disable again afterward.

## Later lots

- **B:** `WCYCLE_VS_HYPO` RBT paradox; shadow-compare governor vs legacy
- **C:** Hormonal basal uplift only via Harmonia production channel
- **D:** Remove parallel `rate *= wCycle`; close H5 dawn yoyo
