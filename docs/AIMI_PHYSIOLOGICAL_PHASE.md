# AIMI — Phase physiologique et risque comportemental

**Statut :** Implémenté (classifieur + HTR + MPC + scénario + JSONL)  
**Lié :** [AIMI_HYPER_TRAJECTORY_RELEASE.md](AIMI_HYPER_TRAJECTORY_RELEASE.md), [research/TRAJECTORY_SIGNATURE_CLASSIFICATION.md](research/TRAJECTORY_SIGNATURE_CLASSIFICATION.md)

---

## 1. Objectif

Éviter de traiter une **montée hormonale** (cortisol matinal, profil circadien masculin, cycle féminin) comme un **repas non déclaré** (gros SMB HTR + V3 agressif), tout en conservant HTR pour les vraies montées repas (KFC, etc.).

Une variable **`PhysiologicalPhase`** + **`BehavioralRiskPolicy`** est calculée à chaque tick et exportée dans `adjustments.physiological_phase` (JSONL).

---

## 2. Phases

| Phase | Contexte | Politique doseur |
|-------|----------|------------------|
| `OFF` | Aucune phase dominante | HTR / MPC inchangés |
| `DAWN_CORTISOL` | 4h–10h, COB≈0, rampe lente, proche cible | HTR **OFF**, bestT plafonné +50, Dawn Guard **étendu** |
| `MALE_CIRCADIAN_HORMONAL` | WCycle off ou ménopause, même signature | Idem |
| `FEMALE_CYCLE_HORMONAL` | WCycle actif, lutéale/ovulation, matin | Idem |
| `STRESS_CORTISOL` | FC↑, Δ aigu, COB=0 | HTR max **EMERGING**, SMB cap **0,75 U** |
| `MEAL_DECLARED` | COB ≥ 5 g | Pas de restriction |
| `MEAL_UNDECLARED` | Δ/gap/projection type repas | HTR plein |
| `HYPER_INSTALLED` | Plateau / tier établi + dwell | HTR `plateauSustain` |

**Priorité :** MEAL_DECLARED → MEAL_UNDECLARED (si cinétique repas) → STRESS → HYPER_INSTALLED → hormonal matin → OFF.

---

## 3. Discriminants repas vs hormonal

**Repas non déclaré** (`mealLike`) si COB &lt; 1 g **et** montée rapide **et** projection qui mène au-dessus du BG :

- Δ ≥ 2,5 ou combinedΔ ≥ 3,2 ou sΔ ≥ 2,2  
- `bestT ≥ BG + 0,35 × highBgBand` et gap scénario crédible  

**Hormonal** si COB &lt; 1 g, **dev &lt; highBgBand** (sous ~140), rampe lente, projection non « repas » (`bestT ≤ BG + 55` ou lead modéré).

---

## 4. Consommateurs

| Module | Effet |
|--------|--------|
| `HormonalScenarioTerminalCap` | `bestT ≤ BG + 50` (hormonal) |
| `HyperTrajectoryReleaseEvaluator` | Tier plafonné, HTR **off** si hormonal |
| `MpcController` | `physioExtendedDawnGuard` → insuline chère, maxSMB ×0,45 |
| `ContinuousStateEstimator` | Dawn Guard PSE sans exiger pas &lt; 200 |

---

## 5. JSONL

```json
"physiological_phase": {
  "phase": "DAWN_CORTISOL",
  "confidence": 0.83,
  "behavioral_risk": "DAWN_CORTISOL",
  "reason": "dawnWindow COB=0 slowRamp",
  "extended_dawn_guard": true,
  "scenario_best_capped": true,
  "max_htr_tier": "OFF",
  "smb_floor_cap_u": 0.55
}
```

---

## 6. Validation terrain

- Matin ~120, Δ modéré, COB=0 : phase hormonal, **pas** de SMB 1,5–2 U HTR.  
- KFC après-midi : `MEAL_UNDECLARED` ou `HYPER_INSTALLED`, HTR actif.  
- Vérifier logs `🌅 PHYSIO_RISK` et `scenario best capped`.
