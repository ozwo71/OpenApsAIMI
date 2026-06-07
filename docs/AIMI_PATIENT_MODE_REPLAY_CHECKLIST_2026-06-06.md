# AIMI — Checklist replay patient mode / go-no-go

**Statut :** checklist de validation produit  
**Date :** 2026-06-06  
**Docs liées :** [AIMI_PHYSIO_RUNTIME_ACTIVATION_2026-06-06.md](AIMI_PHYSIO_RUNTIME_ACTIVATION_2026-06-06.md), [AIMI_RECURSIVE_BELIEF.md](AIMI_RECURSIVE_BELIEF.md), [AIMI_PHYSIOLOGICAL_PHASE.md](AIMI_PHYSIOLOGICAL_PHASE.md)

---

## 1. Objet

Cette checklist sert à valider la couche produit récente :

- `PatientStateSnapshot`
- `PatientModeOrchestrator`
- `RecursiveBeliefAuthorityGate` modulé par le mode patient
- export `ReplayQualityExport` enrichi
- affichage clinique dans `ContextActivity`

Elle ne déclare pas le système "terminé". Elle sert à décider si la passe est assez cohérente pour une montée supervisée.

---

## 2. Préconditions de validation

Avant replay :

- build principal vert sur la variante cible ;
- tests ciblés plugin APS verts ;
- options utiles actives :
  - `ApsUseUam`
  - `OApsAIMIautoDrive`
  - `OApsAIMIHyperTrajectoryRelease`
  - `OApsAIMIRecursiveBeliefShadow`
  - `OApsAIMIPkpdEnabled`
- `OApsAIMIRecursiveBeliefAuthority` à activer seulement après validation shadow.

---

## 3. Champs à vérifier dans les exports

Chaque scénario de replay doit renseigner de façon cohérente :

- `adjustments.patient_state`
- `adjustments.patient_mode`
- `patient_mode`
- `patient_strategy_hint`
- `patient_mode_reasons`
- `context_intent_active`
- `context_intent_count`
- `context_intent_dominant`

Si ces champs sont absents ou incohérents, le scénario est `NO-GO`.

---

## 4. Scénarios minimum à rejouer

### 4.1 Dawn sans repas

Attendu :

- `patient_mode = DAWN_ENDOGENOUS`
- `patient_strategy_hint = BASAL_BRIDGE`
- pas de posture `FAST_MEAL`
- pas d’autorité `HARD`

### 4.2 Repas rapide réel

Attendu :

- `patient_mode = FAST_MEAL`
- `patient_strategy_hint = SMB_PRIORITY`
- `meal_bias` élevé
- pas de suppression faux repas si la montée repas est nette

### 4.3 Repas gras / prolongé

Attendu :

- `patient_mode = PROLONGED_MEAL`
- `patient_strategy_hint = MEAL_SUPPORT`
- continuité de support plutôt qu’un pic unique

### 4.4 Rebond post-hypo

Attendu :

- `patient_mode = POST_HYPO_RECOVERY`
- `patient_strategy_hint = HYPO_RECOVERY`
- autorité effective `NONE`
- traces de protection visibles dans `qualityTags`

### 4.5 Stress / maladie légère

Attendu :

- `patient_mode = STRESS_RESISTANCE`
- `patient_strategy_hint = CONSERVATIVE_OBSERVE`
- pas de confusion dominante avec un repas

### 4.6 Exercice / afterburn

Attendu :

- `patient_mode = EXERCISE_AFTERBURN`
- posture conservatrice
- pas d’escalade RBT `HARD`

### 4.7 Mauvaise nuit / dette de sommeil

Attendu :

- `patient_mode = POOR_SLEEP_DAY`
- modulation prudente
- présence claire du signal `sleepDebtScore`

### 4.8 Absorption incertaine / lag capteur

Attendu :

- `patient_mode = ABSORPTION_UNCERTAIN`
- `patient_strategy_hint = PKPD_REASSESS`
- biais de protection élevé

---

## 5. Critères go / no-go

### GO supervisé

Le lot peut être monté en supervision si :

- les scénarios protecteurs n’ouvrent pas `HARD` ;
- dawn, stress et post-hypo ne déclenchent pas de faux `FAST_MEAL` dominants ;
- les exports replay montrent bien le mode patient et ses raisons ;
- l’écran `AIMI Context` affiche une lecture cohérente du corps au moment du replay.

### NO-GO

Le lot doit être bloqué si un des cas suivants apparaît :

- `POST_HYPO_RECOVERY` avec autorité effective différente de `NONE` ;
- `DAWN_ENDOGENOUS` avec `meal_bias` dominant sans preuve repas claire ;
- `ABSORPTION_UNCERTAIN` qui ouvre une posture agressive ;
- champs replay patient manquants ou vides ;
- divergence flagrante entre l’écran clinique et l’export JSONL.

---

## 6. Recommandation finale

Ordre conseillé :

1. replay shadow sur jeux ciblés ;
2. revue manuelle de `patient_mode` et `patient_strategy_hint` ;
3. vérification UI `AIMI Context` ;
4. seulement ensuite ouverture progressive de l’autorité RBT.
