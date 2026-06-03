# AIMI — Phase physiologique et risque comportemental

**Statut :** Implémenté — classifieur, fusion Physio MTR, scénario, HTR, MPC, JSONL  
**Lié :** [AIMI_HYPER_TRAJECTORY_RELEASE.md](AIMI_HYPER_TRAJECTORY_RELEASE.md), [AIMI_SCENARIO_PROJECTION.md](AIMI_SCENARIO_PROJECTION.md), [research/TRAJECTORY_SIGNATURE_CLASSIFICATION.md](research/TRAJECTORY_SIGNATURE_CLASSIFICATION.md)

---

## 1. Objectif

Éviter de traiter une **montée hormonale** (cortisol matinal, profil circadien masculin, cycle féminin) comme un **repas non déclaré** (gros SMB HTR + V3 agressif + UAM scénario), tout en conservant HTR pour les vraies montées repas (KFC, etc.).

Deux couches partagent la même **`PhysiologicalPhase`** :

1. **Doseur** — HTR, MPC, cap terminal après coup  
2. **Scénario + Physio MTR** — fusion en amont dans `ScenarioProjectionEngine` et `PhysioMultipliersMTR`

---

## 2. Phases

| Phase | Contexte | Politique doseur |
|-------|----------|------------------|
| `OFF` | Aucune phase dominante | HTR / MPC / scénario inchangés |
| `DAWN_CORTISOL` | 4h–10h, COB≈0, rampe lente, proche cible | HTR **OFF**, bestT cap **+50**, UAM off, Dawn Guard étendu |
| `MALE_CIRCADIAN_HORMONAL` | WCycle off ou ménopause, même signature | Idem |
| `FEMALE_CYCLE_HORMONAL` | WCycle actif, lutéale/ovulation, matin | Idem |
| `STRESS_CORTISOL` | FC↑, Δ aigu, COB=0 | HTR max **EMERGING**, SMB cap **0,75 U** |
| `MEAL_DECLARED` | COB ≥ 5 g | Pas de restriction |
| `MEAL_UNDECLARED` | Δ/gap/projection type repas | HTR plein, UAM actif |
| `HYPER_INSTALLED` | Plateau / tier établi + dwell | HTR `plateauSustain` |

**Priorité :** MEAL_DECLARED → MEAL_UNDECLARED (si cinétique repas) → STRESS → HYPER_INSTALLED → hormonal matin → OFF.

---

## 3. Discriminants repas vs hormonal

**Repas non déclaré** (`mealLike`) si COB &lt; 1 g **et** montée rapide **et** projection qui mène au-dessus du BG :

- Δ ≥ 2,5 ou combinedΔ ≥ 3,2 ou sΔ ≥ 2,2  
- `bestT ≥ BG + 0,35 × highBgBand` et gap scénario crédible  

**Hormonal** si COB &lt; 1 g, **dev &lt; highBgBand** (sous ~140), rampe lente, projection non « repas » (`bestT ≤ BG + 55` ou lead modéré).

---

## 4. Architecture fusion (suite logique)

```mermaid
flowchart TB
    subgraph inputs [Entrées tick]
        ADAPTER[AIMIInsulinDecisionAdapterMTR]
        CGM[BG Δ COB heure]
        WC[WCycle]
        PKPD[Curves hybrid/UAM]
    end

    subgraph fusion [PhysioPhaseFusion]
        CLS[PhysiologicalPhaseClassifier]
        POL[BehavioralRiskPolicy]
        FUSE[applyPhaseToMultipliers]
    end

    subgraph consumers [Consommateurs]
        SCEN[ScenarioProjectionEngine]
        HTR[HyperTrajectoryReleaseEvaluator]
        MPC[MpcController + PSE]
    end

    ADAPTER --> FUSE
    CGM --> CLS
    WC --> CLS
    PKPD --> CLS
    CLS --> POL
    POL --> FUSE
    POL --> SCEN
    FUSE --> SCEN
    POL --> HTR
    POL --> MPC
```

| Fichier | Rôle |
|---------|------|
| `PhysiologicalPhase.kt` | Enum phases |
| `PhysiologicalPhaseClassifier.kt` | Classification tick |
| `BehavioralRiskPolicy.kt` | Plafonds HTR / SMB / scénario / Dawn |
| `PhysioPhaseFusion.kt` | **Point unique** classify + fuse multipliers |
| `HormonalScenarioTerminalCap.kt` | Cap `bestT` (HTR + redondance scénario) |

### 4.1 `PhysioMultipliersMTR` enrichi

Champs ajoutés (pas de nouvelle pref) :

- `physiologicalPhase`  
- `phaseConfidence`  
- `source` peut devenir `Deterministic+Phase`

En phase hormonale : **SMB× ≤ 0,92**, **react× ≤ 0,90**, **basal× ≥ 1,02** (léger renfort basale vs micro-bolus).

### 4.2 `ScenarioProjectionContext` enrichi

- `physiologicalPhase`  
- `suppressMealLikeUam` — ne fusionne pas UAM « momentum » si faux repas  
- `scenarioBestCapAboveBgMgdl` — plafond terminal (ex. **50**)

Couche scénario : `ScenarioContributorId.PHYSIOLOGICAL_PHASE` — damp courbe + cap terminal.

### 4.3 Ordre dans `DetermineBasalAIMI2`

1. **T9** — `AIMIInsulinDecisionAdapterMTR` → `PhysioMultipliersMTR` (Health Connect / état physio MTR)  
2. **Pred pipe** — preview `bestT` (hybrid ou rampe) → `classifyAndFuse` → `ScenarioProjectionEngine.build` avec contexte fusionné  
3. **Post-scénario** — `refreshPhysiologicalPhase` (terminaux réels + tier HTR)  
4. **Autodrive V3** — `physioExtendedDawnGuard` + HTR avec même `BehavioralRiskPolicy`

---

## 5. Pic de cortisol — traitement concret

| Étape | Comportement |
|-------|----------------|
| Détection | 4h–10h + COB=0 + rampe lente + BG proche cible + pas de signature repas |
| Scénario | **Pas d’uplift UAM** ; `bestT ≤ BG+50` ; couche PHYSIOLOGICAL_PHASE |
| Physio assistant | SMB/react dampés dans `PhysioMultipliersMTR` |
| HTR | **OFF** (tier max OFF) |
| MPC | Dawn Guard même **avec activité** (pas &lt; 200 pas) |
| PSE | Ra meal-like dampé (comme avant, élargi) |

**Pas de mesure cortisol** — risque comportemental explicite dans les logs et JSONL.

---

## 6. Lien avec Physio MTR existant

| Composant | Lien |
|-----------|------|
| `AIMIInsulinDecisionAdapterMTR` | Entrée **base** des multiplicateurs (état OPTIMAL/STRESS, cosine gate, etc.) |
| `PhysioPhaseFusion` | Applique la **phase** par-dessus (ne remplace pas l’adapter) |
| `CosineTrajectoryGate` / inflammation | Inchangés — phase ajoute une couche **hormonale matin** |
| `AimiPhysioAssistantEnable` | Si OFF → multiplicateurs NEUTRAL, mais **classifieur phase** tourne quand même pour HTR/scénario |

---

## 7. Consommateurs doseur (rappel)

| Module | Effet |
|--------|--------|
| `ScenarioProjectionEngine` | UAM off + cap + damp (source) |
| `HormonalScenarioTerminalCap` | Cap `bestT` (filet HTR) |
| `HyperTrajectoryReleaseEvaluator` | HTR off / plafonné |
| `MpcController` | Dawn Guard étendu |
| `ContinuousStateEstimator` | PSE Dawn élargi |

---

## 8. JSONL

```json
"physiological_phase": {
  "phase": "MALE_CIRCADIAN_HORMONAL",
  "confidence": 0.84,
  "behavioral_risk": "MALE_CIRCADIAN_HORMONAL",
  "reason": "maleCircadian h=7",
  "extended_dawn_guard": true,
  "scenario_best_capped": true,
  "max_htr_tier": "OFF",
  "smb_floor_cap_u": 0.55,
  "physio_smb_factor_fused": 0.92,
  "physio_phase_source": "Deterministic+Phase"
}
```

`scenario_projection.contributors` peut contenir `PHYSIOLOGICAL_PHASE`.

Logs : `🌅 PHYSIO_FUSE:` (fusion), `🌅 PHYSIO_RISK:` (autodrive).

---

## 9. Options utilisateur

**Aucune nouvelle pref.** Tout est automatique si :

- `AimiPhysioAssistantEnable` (modulation ISF/basal/SMB adapter — optionnelle mais fusion phase toujours active pour scénario/HTR)  
- `OApsAIMIHyperTrajectoryRelease` + Autodrive actif  

---

## 10. Validation terrain

- Matin ~120, Δ modéré, COB=0 : `MALE_CIRCADIAN` ou `DAWN_CORTISOL`, contributor `PHYSIOLOGICAL_PHASE`, pas de SMB HTR 1,5–2 U.  
- KFC : `MEAL_UNDECLARED`, UAM contributor présent, HTR actif.  
- Comparer `best_terminal` scénario avant/après sur JSONL matinal.
