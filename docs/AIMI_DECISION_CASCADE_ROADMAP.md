# AIMI — Roadmap cascade décisionnelle (réalisation)

**Statut :** roadmap active — à compléter au fil de la réalisation  
**Validée produit :** 2026-07-18  
**Contrat :** [AIMI_DECISION_CASCADE_CONTRACT.md](AIMI_DECISION_CASCADE_CONTRACT.md)

**Phrase produit :** Arbre informe → Harmonia décide (sur la branche finale) → Auditor double-check.  
**Principe natif :** ce chemin est l’**architecture logicielle par défaut**, pas une option « assistant » à activer.

---

## 0. Index documentaire

| Document | Rôle |
|----------|------|
| [AIMI_DECISION_CASCADE_CONTRACT.md](AIMI_DECISION_CASCADE_CONTRACT.md) | Contrat rôles / inputs / veto / MealCertainty |
| **Ce fichier** | Roadmap détaillée + suivi réalisation + décisions prefs natives |
| [aimi-harmonia-implementation.md](aimi-harmonia-implementation.md) | Lots H4–H7, état terrain Harmonia |
| [aimi-harmonia-simulation-branch.md](aimi-harmonia-simulation-branch.md) | Sandbox sim vs pompe |
| [AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md](AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md) | Arbre Lot 1 |
| [AIMI_PREDICTION_DIVERGENCE.md](AIMI_PREDICTION_DIVERGENCE.md) | Multi-terminaux / clamp reconcile |
| [AIMI_ARCHITECTURE_MAP.md](AIMI_ARCHITECTURE_MAP.md) | Carte modules |
| [AIMI_ROADMAP.md](AIMI_ROADMAP.md) | Roadmap AIMI globale (P1–P3) — pointer cascade ici |
| [AIMI_RECURSIVE_BELIEF.md](AIMI_RECURSIVE_BELIEF.md) | RBT / canaux autorité |
| [docs/PKPD_KINETICS_ARCHITECTURE.md](PKPD_KINETICS_ARCHITECTURE.md) | PKPD / kinetics |

**Décisions produit figées (2026-07-18) :**
- Q1 contrat : certitude **sync** = Harmonizer + `MealCertainty` ; Auditor LLM reste async/cache, ne contredit pas un BLOCK sync.
- Q2 : `MealCertainty.HIGH` peut ouvrir les ponts H4/H4c **sans** dépendre de flags sticky parallèles.
- Cascade = **défaut natif** (voir §1).

---

## 1. Architecture native vs options (non-négociable)

### 1.1 Doit être le chemin par défaut (plus un kill-switch dose)

| Élément | État actuel | Décision roadmap |
|---------|-------------|------------------|
| **Déploiement arbre intégral** (trunk + branches + leaves) chaque tick loop | Gaté par `AimiPhysioAssistantEnable` (**default false**) → tree null → Harmonia dark | **Natif always-on** sur le path dose. La pref ne doit plus tuer l’arbre ; au plus UI/extras physio (LLM, narrative). |
| **Harmonia sim + chooseAction** sur trunk/branches | Suit l’arbre (donc OFF si pref OFF) | **Natif always-on** dès que l’arbre est natif. Pas de nouvelle pref Harmonia. |
| **Harmonia Harmonizer** CONFIRM/SOFTEN/BLOCK sync | Déjà always-on | **Garder natif** |
| **ClampPkpdScenarioReconcile** | Always-on, gaté par évidence | **Garder natif** |
| **Prediction Authority** apply | Pref default **true** | **Natif always-on** (shadow reste debug) |
| **Contrat MealCertainty** + export tick | Absent | **Natif** (pas de pref) |

### 1.2 Reste légitimement optionnel (coût, amplitude, rollout)

| Élément | Pref | Pourquoi garder une option |
|---------|------|----------------------------|
| Auditor LLM | `AimiAuditorEnabled` default false | Coût / latence / réseau — double-check **enrichi**, pas le pilote |
| StraightLine Tube | `OApsAIMIStraightLineTubeAdvisorEnabled` default false | Amplitude SMB opt-in ; pas l’intention clinique |
| Physio LLM analysis | `AimiPhysioLLMAnalysisEnable` | Coût |
| Sources data sleep/HRV | `AimiPhysioSleepDataEnable` / `HRVDataEnable` | Disponibilité capteur |
| RBT / T3C authority knobs | RecursiveBelief*, T3c* | Rollout safety / arbitration — **mais** ne doivent pas rendre la cascade « off » par défaut |
| PKPD enable (setup) | `OApsAIMIPkpdEnabled` | Wizard / setup pompe |

### 1.3 Règle d’or prefs

> Si retirer la pref casse la cascade Tree→Harmonia, ce n’est **pas** une option : c’est un défaut d’architecture.  
> Les prefs restantes moduluent **amplitude, coût LLM, sources data**, jamais l’existence de la spine décisionnelle.

---

## 2. Exigences structurelles (avant les lots code)

### E1 — Arbre se déploie **intégralement**

Chaque tick `determine_basal` loop (BG valide) doit produire un snapshot non-null avec :
- roots / trunk (`globalState`, `riskLevel`, confidence)
- **toutes** les branches significatives (meal, digestion, hypoRisk, activity, postActivity, sensor, resistance, stress, insulinEffectiveness, …)
- leaves (notes Auditor / raisons)
- export JSON cohérent

**Interdit :**
- `enabled=false` → `null` sur le path dose
- refresh `CONTEXT_INTENT` / `PHYSIO_SIGNAL` qui publient `physiologicalTree = null` et empêchent le chicken-egg
- arbre « partial » sans trunk alors que Harmonia doit décider

**Critère done E1 :** JSONL tick : `physiological_tree` présent **>95 %** des ticks BG valides (hors trous CGM) sans dépendre d’une case « Physio Assistant ».

### E2 — Harmonia comprend **sur quelle branche il finit**

`chooseAction` / sim / production / export doivent exposer explicitement :
- `trunk.globalState` (branche finale / état global)
- branches contributives (confidences) qui ont mené à l’action
- `action` + `reason` / bridge markers (`h4_meal_rise_bridge`, etc.)
- `MealCertainty.level` (quand D1 livré)

**Interdit :** décider meal/protective sans lire le trunk ; label `branch` décoratif déconnecté du chooseAction.

**Critère done E2 :** pour tout tick Harmonia non-null, `harmonia_simulation.branch == tree.trunk.globalState` (ou mapping documenté) et l’action est une fonction monotone de ce trunk + blockers.

### E3 — Cascade par défaut

Pipeline sync minimal toujours exécuté :
```
Tree (full) → MealCertainty → Harmonia.chooseAction → (RBT/T3C channel) → production? → Harmonizer → pompe
Auditor LLM (si enabled) → CONFIRM/SOFTEN différé, ne rouvre pas un BLOCK
```

---

## 3. Suivi global (mettre à jour à chaque livraison)

| Phase | Titre | Statut | Date |
|-------|-------|--------|------|
| **D0** | Contrat cascade | ✅ Done | 2026-07-18 |
| **R0** | Roadmap + index + prefs natives (ce doc) | ✅ Done | 2026-07-18 |
| **R1** | E1 — Arbre always-on / déploiement intégral | 🔄 In progress (R1.1–R1.3 code) | 2026-07-18 |
| **R2** | E2 — Harmonia branch-aware + export certitude | ⬜ Pending | — |
| **D1** | Spec + type `MealCertainty` (+ tests purs) | ⬜ Pending | — |
| **D2** | H6 — Harmonizer sync + Auditor double-check aligné | ⬜ Pending | — |
| **D3** | Authority / meal_rise dérivés de MealCertainty | ⬜ Pending | — |
| **D4** | Snapshot terminal dose (C1) si encore nécessaire | ⬜ Pending | — |
| **D5** | Validation device + JSONL + markers | ⬜ Pending | — |
| **Dx** | Docs / labels stale / prefs UI cleanup | ⬜ Pending | — |

Légende : ⬜ Pending · 🔄 In progress · ✅ Done (code) · 🧪 Device OK (user confirm) · ⏸ Blocked

---

## 4. Roadmap détaillée par phase

### R0 — Index & gouvernance prefs ✅

- [x] Contrat [AIMI_DECISION_CASCADE_CONTRACT.md](AIMI_DECISION_CASCADE_CONTRACT.md)
- [x] Ce roadmap + index
- [x] Décisions Q1/Q2 + native-vs-optional (§1)
- [x] Pointer depuis [AIMI_ROADMAP.md](AIMI_ROADMAP.md) / arch map / contrat / harmonia-implementation

**Réalisation :** doc only — 2026-07-18

---

### R1 — Déploiement intégral de l’arbre (natif) ⬜

**Objectif :** l’arbre n’est plus un « assistant » optionnel ; c’est la spine.

| # | Tâche | Fichiers probables | Statut |
|---|-------|-------------------|--------|
| R1.1 | Séparer **build tree (always)** de **physio assistant extras** (LLM/UI) | `DetermineBasalAIMI2`, `PhysiologicalTreeBuilder`, prefs | ✅ |
| R1.2 | Path dose ne lit plus `AimiPhysioAssistantEnable` comme kill-switch tree ; string EN clarifiée | `BooleanKey`, `strings.xml` | ✅ |
| R1.3 | Fix refresher : CONTEXT + PHYSIO rebuild tree always (plus chicken-egg) | `PatientStateRuntimeRefresher` | ✅ |
| R1.4 | Garantir branches effort/meal/hypo alimentées (BG/Δ/effort injectés) sur tout build loop | `PhysiologicalTreeBuilder` call sites | ⬜ (loop OK ; refresher sans BG/effort encore) |
| R1.5 | Métrique / log : `TREE_DEPLOYED trunk=… conf=…` chaque tick | DetermineBasal / export | ⬜ |
| R1.6 | Tests refresher tree always-on + chicken-egg | `PatientStateRuntimeRefresherTest` | ✅ |

**⚠️ ASYNC IMPACT :** aucun si sync build ; attention cache runtime publish.

**Validation R1 :** JSONL `physiological_tree` rate ; Harmonia evaluate non-null rate ↑ sans case assistant.

---

### R2 — Harmonia branch-final conscious ⬜

**Objectif :** Harmonia décide **parce que** et **sur** la branche trunk finale, de façon exportable.

| # | Tâche | Fichiers probables | Statut |
|---|-------|-------------------|--------|
| R2.1 | Structurer `decisionBasis` : trunk + top branches + blockers dans sim/production export | `HarmoniaDecision.kt`, export JSON | ⬜ |
| R2.2 | Assert / debug : action incompatible avec trunk → log `HARMONIA_BRANCH_MISMATCH` | chooseAction / planProduction | ⬜ |
| R2.3 | Documenter matrice trunk → actions autorisées (DIGESTION→MEAL_SUPPORT, HYPO_RISK→protective/block, STABLE→stabilize…) | doc + tests table-driven | ⬜ |
| R2.4 | Production path : si T3C/RBT skip, exporter `skipped_owner` **sans** effacer le basis de décision sim | `planHarmoniaProductionBranch`, T3C bridge | ⬜ |
| R2.5 | UI/Hormonitor : afficher branche finale + action (lisibilité cascade) | viewer si existe | ⬜ |

**Validation R2 :** audit JSONL — action vs trunk cohérents ; plus de sim « orpheline ».

---

### D1 — `MealCertainty` (contrat unique repas) ⬜

| # | Tâche | Statut |
|---|-------|--------|
| D1.1 | Type pur Kotlin `MealCertainty` (level, tree_state, rise_geometry, terminals_agree, effort_veto, soft_corroboration) | ⬜ |
| D1.2 | Builder depuis tree + phases + terminaux + effort (HR = soft only) | ⬜ |
| D1.3 | Tests purs (HIGH digestion+rise ; veto effort ; falling → not HIGH) | ⬜ |
| D1.4 | Export JSON tick `meal_certainty` | ⬜ |
| D1.5 | Harmonia `chooseAction` consomme `MealCertainty` (H4 bridge dérivé, plus flags parallèles) | ⬜ |

**Validation D1 :** un seul langage meal dans JSONL ; sticky `meal_rise_confirmed` planifié en D3.

---

### D2 — H6 Harmonizer + Auditor double-check ⬜

| # | Tâche | Statut |
|---|-------|--------|
| D2.1 | Harmonizer : CONFIRM/SOFTEN/BLOCK explicites sur intention Harmonia + terminaux (sync) | ⬜ |
| D2.2 | Auditor payload : `physiological_tree` + sim + **production** + `meal_certainty` + `decisionBasis` | ⬜ |
| D2.3 | Golden prompts : CONFIRM si MEAL_SUPPORT + DIGESTION + rise ; SOFTEN surcorrection ; ne pas rouvrir BLOCK | ⬜ |
| D2.4 | Cache confidence → SafetyNet soft-landing **aligné** (ne pas booster contre un BLOCK) | ⬜ |
| D2.5 | Auditor reste **pref** (`AimiAuditorEnabled`) — absence Auditor ≠ cascade off | ⬜ |

**⚠️ ASYNC IMPACT :** Auditor orchestration async — ne pas bloquer le tick pompe.

---

### D3 — Fin des vérités meal parallèles ⬜

| # | Tâche | Statut |
|---|-------|--------|
| D3.1 | `DecisionPredictionAuthority.mealEvidence` inclut trunk digestion/meal / MealCertainty | ⬜ |
| D3.2 | `meal_rise_confirmed` dérivé de MealCertainty (dés-sticky) ou déprécié | ⬜ |
| D3.3 | H4/H4c canaux basés sur MealCertainty.HIGH (Q2) | ⬜ |
| D3.4 | Tests + markers `CASCADE_MEAL_CERT` | ⬜ |

---

### D4 — Snapshot terminal dose (C1) si besoin ⬜

Uniquement si après R1–D3 les gates (SafetyNet/stacking/tube) restent empoisonnés par multi-terminaux.

| # | Tâche | Statut |
|---|-------|--------|
| D4.1 | Un eventual/minPred autoritaire par tick pour tous les consumers dose | ⬜ |
| D4.2 | Brancher ScenarioProjectionApplicator ou équivalent | ⬜ |
| D4.3 | Clamp reconcile devient no-op ou filet fin | ⬜ |

---

### D5 — Validation terrain ⬜

| # | Critère | Statut user |
|---|---------|-------------|
| D5.1 | Tree deploy rate | ⬜ |
| D5.2 | Harmonia action ↔ trunk | ⬜ |
| D5.3 | Meal HIGH → support sans crush faux floor | ⬜ |
| D5.4 | Hypo réel → protective / pas meal reopen | ⬜ |
| D5.5 | Droite → STABILIZE / Auditor SOFTEN | ⬜ |
| D5.6 | Pref physio OFF (si encore UI) n’éteint plus la cascade | ⬜ |

**Ne jamais marquer « fully functional » sans confirmation user.**

---

### Dx — Cleanup docs & UI ⬜

| # | Tâche | Statut |
|---|-------|--------|
| Dx.1 | Retirer labels « Lot 1 read-only / context only » obsolètes restants | ⬜ |
| Dx.2 | Préférence UI : renommer Physio Assistant pour ne plus suggérer « disable tree » | ⬜ |
| Dx.3 | Sync AIMI_ROADMAP P3 / harmonia-implementation H6 → ce fichier | ⬜ |

---

## 5. Ordre d’exécution (discipline)

```
R0 ✅ → R1 (arbre natif) → R2 (branche Harmonia)
         → D1 (MealCertainty) → D2 (H6) → D3 (unifier meal)
         → D4 (C1 si besoin) → D5 (device) → Dx
```

**Règle réalisation :** une phase à la fois ; cocher ce fichier à chaque merge logique ; pas de « fully working » sans D5 user.

**Règle prefs :** toute nouvelle pref proposée pendant l’implémentation doit passer le test §1.3 — sinon refusée (native).

---

## 6. Journal de réalisation

| Date | Phase | Note |
|------|-------|------|
| 2026-07-18 | D0 | Contrat cascade validé |
| 2026-07-18 | R0 | Roadmap créée ; prefs natives inventoriées (`AimiPhysioAssistantEnable` = principal anti-pattern) |
| 2026-07-18 | R1 | Tree `enabled=true` sur dose path + refresher ; pref ne gate plus que multiplicateurs/extras ; tests chicken-egg |

---

## 7. Prochaine action concrète

**R1.1–R1.2** : découpler le build de l’arbre de `AimiPhysioAssistantEnable` pour que Tree→Harmonia soit le chemin natif par défaut.

Attendre confirmation « vas-y » code sur R1 avant modification runtime.
