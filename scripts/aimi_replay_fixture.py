#!/usr/bin/env python3
"""Project an AIMI support package into a replay fixture.

A full ``AIMI_Decisions_Last24h.jsonl`` is 8-13 MB per day, dominated by blocks the replay harness
never reads (``recursive_belief``, ``physiological_tree``, the free-text narrative). This script
keeps only the harness input contract and writes one flat JSON object per line, which brings a day
down to roughly 150 KB.

It also drops everything identifying: the narrative (which carries local file paths), and every
block outside the allow-list below. The ``Diagnostic_Report.txt`` of a package is never read.

Usage:
    python3 scripts/aimi_replay_fixture.py <package>/AIMI_Decisions_Last24h.jsonl <out.jsonl>

Bundled fixtures live in ``plugins/aps/src/test/resources/replay/``. A larger private corpus can be
kept outside the repository and pointed at with the ``AIMI_REPLAY_CORPUS`` environment variable;
see ``docs/adr/0001-replay-harness.md``.
"""
import json,os,sys
def g(d,*path):
    cur=d
    for p in path:
        if not isinstance(cur,dict): return None
        cur=cur.get(p)
    return cur
def flat(r):
    b=r["baseline_state"]; a=r.get("adjustments",{}) or {}; o=r.get("outcome") or {}
    st=a.get("smb_binding_trace") or {}; rq=a.get("replay_quality") or {}
    bt=a.get("basal_terminal") or {}; sr=a.get("safety_risk") or {}
    phd=a.get("post_hypo_delivery") or {}
    d={
      "t":r["timestamp"],"trig":r.get("trigger"),
      "bg":b.get("current_bg_mgdl"),"iob":b.get("iob_u"),"cob":b.get("cob_g"),
      "pisf":b.get("profile_isf_mgdl"),"pbasal":b.get("profile_basal_uph"),
      "sisf":b.get("profile_isf_static_mgdl"),"cisf":b.get("command_isf_mgdl"),
      "isrc":b.get("isf_source"),"iage":b.get("isf_age_ms"),
      "ikey":b.get("isf_cache_key"),"iglu":b.get("isf_cache_glucose_mgdl"),
      "ikal":b.get("isf_kalman_fast_mgdl"),"iadj":b.get("isf_adj_engine_mgdl"),
      "islow":b.get("isf_fused_slow_mgdl"),"itrust":b.get("isf_trust_fast"),
      "idyn":b.get("isf_dynamic_factor"),"itraj":b.get("isf_trajectory_multiplier"),
      "ra":b.get("estimated_ra_mgdl_per_min"),
      "dec":o.get("decision"),"amt":o.get("amount"),"basal":o.get("target_basal_rate_uph"),
      "owner":st.get("origin_owner"),"fowner":st.get("final_owner"),
      "maxsmb":st.get("max_smb_u"),"iobhead":st.get("iob_headroom_u"),
      "tier":rq.get("correction_aggression_tier"),"safety":rq.get("safety_source"),
      "phguard":rq.get("post_hypo_guard_state"),
      "pmode":g(a,"patient_mode","mode"),
      "tgt":bt.get("target_bg_mgdl"),"mealmode":bt.get("meal_mode_active"),
      "posthypo":bt.get("post_hypo_active"),
      "sgate":sr.get("safety_gate"),"halt":sr.get("halt_remaining_pipeline"),
      "uam":g(a,"uam_hypotheses","dominant"),
      "absorb":g(a,"meal_absorption_phase","phase"),
      "phase":g(a,"physiological_phase","phase"),
      "disf":g(a,"dynamic_isf","final_value_mgdl"),
      "ev":g(a,"dose_terminal_snapshot","eventual_mgdl"),
      "minpred":g(a,"dose_terminal_snapshot","min_pred_mgdl"),
      "phd_active":phd.get("active"),"phd_reason":phd.get("reason_tag"),
      "phd_before":phd.get("smb_before_cap_u"),"phd_after":phd.get("smb_after_cap_u"),
    }
    return {k:v for k,v in d.items() if v is not None}
src,dst=sys.argv[1],sys.argv[2]
recs=[json.loads(l) for l in open(src,encoding="utf-8",errors="replace") if l.strip()]
recs=[x for x in recs if "baseline_state" in x]
recs.sort(key=lambda x:x["timestamp"])
with open(dst,"w",encoding="utf-8") as fh:
    for r in recs: fh.write(json.dumps(flat(r),separators=(",",":"),sort_keys=True)+"\n")
print(f"{os.path.basename(dst)}: {len(recs)} ticks, {os.path.getsize(dst)/1024:.0f} Ko")
