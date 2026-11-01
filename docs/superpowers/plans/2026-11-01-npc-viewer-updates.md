# NPC viewer updates implementation plan

> Execute inline with superpowers:executing-plans. User authorized continued implementation.

**Goal:** Reduce repeat affine work and far-viewer traffic, expose bounded cost metrics,
and report the existing Stage improvement without overstating causality.
**Architecture:** Prepared immutable affine mapper; shared pose with three pending delta
cohorts; optional metrics through the existing registry.
**Spec:** ../specs/2026-11-01-npc-viewer-updates-design.md

## Constraints and review focus

- Work after released Host 2.15.0; keep this follow-up separate from the NPC resource-isolation hotfix.
- Preserve64/72-block range,32 vertical separation, default/personal visuals and instance isolation.
- No changes to licensed assets, Map9/e7 binding, addon983files, queue routing or native SOLID.
- Rebased on the scale correction in Host#230. A later private activation must retain
  the Scene#22 corner correction through the matching private overlay or a released
  dependency containing it; the declared Scene0.4.4 alone does not contain that fix.
- Current SOLID client acceptance is still open; do not activate this performance follow-up
  while diagnosing that issue. Native BOUNCE is tracked separately as N31.
- Integrate Host#232's mounted-pose initialization before any later activation. The frozen
  correctness benchmark candidate predates that fix and is not a deployable replacement
  for the latest Stage collision build.
- A one-time rotation change must survive skipped viewer sends; stop/seek cannot leave stale deltas.
- Immutable packet maps, bounded cohort state, cleanup and timer/cardinality overhead matter.
- General signed/zero-scale/shear math remains; cached preparation must invalidate on root/local changes.

## Tasks

- [x] Record fresh Stage query samples and matched-player-window analysis; freeze local baseline.
- [x] Add failing mixed-viewer cadence/delta/final-state regressions in NpcRenderPerformanceTest.
- [x] Implement cohort accumulation/flush in NpcItemDisplay and delivery deadlines in AnimatedNpcHandle.
- [x] Add prepared-affine geometry regressions, implement cached root/local conversion and primitive quaternions.
- [x] Add registry behavior tests and bounded model/phase timers/counters; wire through existing loader/renderers.
- [x] Run full suites, formatting, production-JAR map/addon acceptance and repeated before/after benchmark.
- [x] Independent review, resolve material findings and update backlog/impact report.
- [ ] Activate after the current Java-client correctness issues are resolved, then compare live costs.
