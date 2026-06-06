# AIMI — Implémentation runtime physio / UAM / PKPD / RBT

**Statut :** implémenté en code et validé par tests ciblés  
**Date :** 2026-06-06  
**Branche :** `dev_OAPSAIMI_mergeDEV`  
**Docs liées :** [AIMI_PHYSIOLOGICAL_PHASE.md](AIMI_PHYSIOLOGICAL_PHASE.md), [AIMI_RECURSIVE_BELIEF.md](AIMI_RECURSIVE_BELIEF.md), [AIMI_MEAL_ABSORPTION_PHASE.md](AIMI_MEAL_ABSORPTION_PHASE.md), [PKPD_ABSORPTION_GUARD_AUDIT.md](PKPD_ABSORPTION_GUARD_AUDIT.md)

---

## 1. Objet

Ce document résume **ce qui a été réellement codé** dans la passe qualité physio récente, **sans changer la grammaire de nommage existante du runtime**.

Il répond à trois questions :

1. Quelles briques ont été ajoutées ou raccordées ?
2. Quelles **préférences existantes** pilotent ces briques ?
3. Quelle stratégie d’activation progressive est recommandée ?

---

## 2. Point important

### 2.1 Aucune nouvelle préférence utilisateur n’a été ajoutée

La passe a été conçue pour **réutiliser les options déjà présentes** dans `OpenAPSAIMIPlugin.kt`, afin d’éviter :

- une deuxième couche de réglages redondants ;
- une divergence entre les noms documentés et les clés runtime ;
- un risque d’activation partielle mal comprise.

### 2.2 La doc décrit les noms runtime exacts

Les noms décrits ici sont volontairement alignés sur les structures déjà présentes dans le code :

- `PhysioLatentState`
- `UamHypothesisState`
- `PhysiologicalStressMask`
- `RecursiveBeliefAuthorityGate`
- `ReplayQualityExport`
- `SmbRefinementFeatureSchema`

---

## 3. Ce qui a été codé

### 3.1 Lot 1 — Export qualité et replay

Une couche d’export qualité/replay a été consolidée pour rendre visibles les décisions runtime.

Fichier principal :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/quality/ReplayQualityExport.kt`

But :

- exposer les états utiles au replay ;
- rendre lisible l’application effective de l’autorité RBT ;
- tracer les raisons de blocage ou de limitation.

### 3.2 Lot 2 — Stress mask physio réellement branché

Le stress physiologique n’est plus laissé à un masque vide côté Autodrive.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/autodrive/learning/PhysiologicalStressMaskBuilder.kt`

Intégration principale :

- `DetermineBasalAIMI2.kt`

But :

- alimenter `MechanismAttentionGate` avec un masque réel ;
- mieux moduler les estimations en contexte de stress / récupération / contexte hormonal.

### 3.3 Lot 3 — État latent physio partagé

Un état latent commun a été introduit pour éviter que chaque moteur interprète le patient avec sa propre lecture isolée.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/PhysioLatentState.kt`

Champs métiers principaux :

- `mealProb`
- `endogenousGlucoseDrive`
- `circadianSiFactor`
- `transientResistanceProb`
- `sleepDebtScore`
- `sensorConfidence`

But :

- fournir une sémantique commune à UAM, PKPD, Autodrive, RBT et qualité/replay.

### 3.4 Lot 4 — Prior circadien individualisé repas

Le prior horaire repas a été amélioré en direction d’un profil plus individualisé.

Briques clés :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/CircadianMealProfileStore.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/MealAbsorptionPhaseEngine.kt`

But :

- réduire les faux repas dawn / cortisol ;
- conserver la détection des vrais repas matinaux ;
- sortir d’une logique trop dépendante d’horaires fixes.

### 3.5 Lot 5 — UAM multi-hypothèses

La logique UAM ne reste plus limitée à une lecture quasi binaire repas / pas repas.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/UamHypothesisState.kt`

Hypothèses suivies :

- repas ;
- drive endogène / dawn ;
- stress ;
- rebond post-hypo ;
- late-fat.

But :

- mieux arbitrer les montées ambiguës ;
- éviter les faux positifs repas sur un contexte hormonal ou de contre-régulation.

### 3.6 Lot 6 — PKPD, poids et état physio

La PKPD a été reconnectée à des signaux plus physiologiques que de simples caps de sécurité.

Briques principales :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/autodrive/estimator/ContinuousStateEstimator.kt`

But :

- mieux représenter la dynamique d’absorption et de récupération ;
- tenir compte du poids et du contexte physio dans les ajustements.

### 3.7 Lot 7 — Raffinement SMB ML aligné sur la physiologie

Le pipeline `modelUAM.tflite -> refine()` a été mieux formalisé et sécurisé.

Briques principales :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ml/SmbRefinementFeatureSchema.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ml/AimiSmbTrainer.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/UamInputSchemaValidator.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AimiModelHandler.kt`

But :

- aligner les features SMB sur `PhysioLatentState` ;
- conserver la compatibilité CSV historique ;
- bloquer proprement une exécution TFLite si le schéma d’entrée diverge.

### 3.8 Lot 8 — Autorité RBT progressive

L’autorité Recursive Belief n’est plus un simple booléen implicite.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/recursive/RecursiveBeliefAuthorityGate.kt`

Modes effectifs :

- `NONE`
- `SOFT`
- `HARD`

But :

- empêcher une autorité trop précoce ;
- garder un fonctionnement shadow même si la préférence d’autorité est activée ;
- moduler le lift HTR / RBT via `liftBlend` selon la qualité du contexte.

---

## 4. Préférences existantes qui pilotent le runtime

### 4.1 Détection UAM / repas

Préférence existante :

- `ApsUseUam`

Fonction :

- active la logique UAM utilisée par la détection repas et ses hypothèses.

Référence d’exposition UI :

- `OpenAPSAIMIPlugin.kt` dans l’écran AIMI principal.

### 4.2 Autodrive / HTR / Recursive Belief

Préférences existantes :

- `OApsAIMIautoDrive`
- `OApsAIMIautoDriveActive`
- `OApsAIMIautoDriveAuthoritative`
- `OApsAIMIHyperTrajectoryRelease`
- `OApsAIMIHyperTrajectoryReleaseAggressive`
- `OApsAIMIRecursiveBeliefShadow`
- `OApsAIMIRecursiveBeliefAuthority`
- `OApsAIMIRecursiveBeliefWavelet`

Fonction :

- pilotent l’activation d’Autodrive ;
- autorisent HTR ;
- activent le shadow ou l’autorité RBT ;
- la nouvelle gate runtime peut malgré tout limiter l’autorité effective à `NONE` ou `SOFT`.

### 4.3 PKPD / dyn ISF / surveillance IOB

Préférences existantes :

- `OApsAIMIPkpdEnabled`
- `OApsAIMIPeakGovernorEnabled`
- `OApsAIMIDynIsfTrajectoryTuningEnabled`
- `OApsAIMIDynIsfTrajectoryShadowOnly`
- `OApsAIMIPkpdPragmaticReliefEnabled`
- `OApsAIMIIobSurveillanceGuard`

Fonction :

- activent la couche PKPD ;
- autorisent l’apprentissage / gouvernance du peak ;
- activent l’adaptation DynISF trajectoire ;
- laissent la surveillance IOB jouer comme garde-fou.

### 4.4 Raffinement ML

Préférence existante :

- `OApsAIMIMLtraining`

Fonction :

- permet au trainer CSV asynchrone de compléter le résultat du modèle UAM de base ;
- ne remplace pas `modelUAM.tflite`, il l’affine.

---

## 5. Préférences existantes non ajoutées mais importantes à connaître

Certaines briques codées restent dépendantes de l’environnement fonctionnel déjà présent :

- `AimiPhysioAssistantEnable`
- `AimiPhysioSleepDataEnable`
- `AimiPhysioHRVDataEnable`
- `AimiPhysioLLMAnalysisEnable`
- `AimiPhysioDebugLogs`

Elles ne font pas partie d’une nouvelle passe de préférences, mais elles influencent la richesse du contexte physio disponible.

---

## 6. Recommandation d’activation

### 6.1 Profil recommandé pour première mise en route

- `ApsUseUam = ON`
- `OApsAIMIautoDrive = ON`
- `OApsAIMIautoDriveActive = ON`
- `OApsAIMIHyperTrajectoryRelease = ON`
- `OApsAIMIRecursiveBeliefShadow = ON`
- `OApsAIMIRecursiveBeliefAuthority = OFF` au départ
- `OApsAIMIPkpdEnabled = ON`
- `OApsAIMIPeakGovernorEnabled = ON`
- `OApsAIMIIobSurveillanceGuard = ON`
- `OApsAIMIMLtraining = ON` seulement si l’on veut le raffinement CSV actif

### 6.2 Deuxième étape après observation terrain

Si les premiers cycles sont propres :

- laisser `RecursiveBeliefShadow = ON`
- activer `OApsAIMIRecursiveBeliefAuthority = ON`

Important :

- même avec la préférence d’autorité activée, `RecursiveBeliefAuthorityGate` peut rester en `NONE` ou `SOFT` selon le contexte ;
- l’autorité effective n’est donc pas strictement équivalente à la préférence utilisateur.

---

## 7. Ce qu’il faut surveiller après activation

### 7.1 Contexte repas / dawn

Vérifier :

- absence de faux repas au réveil ;
- maintien d’une bonne détection sur vrai petit-déjeuner ;
- absence de sur-réaction sur cortisol / drive endogène.

### 7.2 Contexte correction / stacking

Vérifier :

- pas de rafale SMB injustifiée après une première correction ;
- comportement cohérent avec `IOB surveillance` ;
- récupération propre après hypo ou rebond post-hypo.

### 7.3 Contexte RBT / HTR

Vérifier :

- que `authority_effective` passe bien par `NONE -> SOFT -> HARD` seulement quand le contexte est crédible ;
- que les raisons de blocage ou de limitation remontent bien dans l’export qualité.

---

## 8. Logs et exports à regarder

Les points d’observation importants sont :

- `ReplayQualityExport`
- `RecursiveBeliefExport`
- `authority_gate`
- `physiological_phase`
- `recursive_authority_gate`
- `rbtMode`
- `authorityRequested`
- `authorityEffective`
- `authorityReadinessScore`
- `authorityGateReasons`

Ces champs sont les meilleurs indicateurs pour comprendre pourquoi une autorité RBT a été appliquée, limitée ou bloquée.

---

## 9. Résumé opérationnel

### 9.1 Ce qui est vrai

- les briques physio/UAM/PKPD/RBT/ML ont été intégrées ;
- elles réutilisent les préférences existantes ;
- aucune nouvelle option cachée n’est requise pour les activer.

### 9.2 Ce qui n’est pas vrai

- il n’y a pas de “nouveau mode magique” à activer ;
- `RecursiveBeliefAuthority = ON` ne garantit pas une autorité hard permanente ;
- le trainer CSV ne remplace pas le modèle UAM TFLite.

### 9.3 Verdict pratique

Pour utiliser la passe récente :

1. activer les préférences existantes utiles ;
2. démarrer avec `RecursiveBeliefShadow` ;
3. n’ouvrir `RecursiveBeliefAuthority` qu’après observation ;
4. suivre les exports qualité sur les premiers cas dawn / repas / correction.

