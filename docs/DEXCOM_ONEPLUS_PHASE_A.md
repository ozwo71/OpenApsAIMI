# Dexcom ONE+ — Phase A status

**Date:** 2026-07-18  
**Branch:** `feature/dexcom-oneplus-native`

## Today (Phase A)

| Path | Status |
|------|--------|
| Notification Reader (`com.dexcom.d1plus`, `com.dexcom.dexcomone`) | Tags BG as `AAPS-DexcomOnePlus` → `SourceSensor.DEXCOM_ONEPLUS_NATIVE` |
| xDrip Direct → AAPS xDrip source | Supported user path; sensor tagging depends on xDrip/AAPS source config (not One+ native enum unless configured) |
| Native BLE (`:plugins:dexcom_oneplus` / `DexcomOnePlusPlugin`) | **Stub only** — no GATT session / GV insert yet |
| BYODA `DexcomPlugin` | Unchanged (G6/G7 packs; not One+) |

## AIMI

- G6 lead / BYODA Δ compensation apply **only** when `sourceSensor == DEXCOM_G6_NATIVE`.
- One+ (`DEXCOM_ONEPLUS_NATIVE`) must **not** receive G6 lead; treated as a fast sensor (same class as G7).
- `GlucoseStatusCalculatorAimi` / plugin path propagate `sourceSensor` from the latest bucketed GV when known.

## See also

- [DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md](DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md)
- [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md)
