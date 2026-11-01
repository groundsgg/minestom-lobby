# NPC Renderer Math Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce renderer matrix/allocation work while preserving exact visible NPC geometry and verify20/40/60-client behavior.
**Architecture:** One immutable parent affine per handle transform; model matrices and packet fields stay owned; primitive quaternion/scale temporaries retain old arithmetic.
**Tech Stack:** Kotlin, Minestom26.3/JDK25, JUnit5, mc-stress protocol777, strict safe startup-owned JFR.
**Spec:** ../specs/2026-11-01-npc-renderer-math-design.md

## Global Constraints

- Preserve Map9sourcea1bec43f/lit0834f795, catalogueedge1793492850 and addona0065344.
- Preserve all765cubes/471displays, nativeSOLID, interpolation and64/72view ranges.
- Isolate math from draftHost#233's CPU-regressing delivery/instrumentation changes.
- No shared mutable metadata arrays, no live JVM attach, no Production/global-pack changes.

## Review Focus

- Root/local/scale/rotation updates must replace preparation before rendering.
- Late anchor move callbacks must respect revision/closed ownership.
- Sheared, negative and zero scales preserve client-visible corners.
- Two handles/model frames cannot mutate one another's prepared or retained output.
-20/40/60requested clients must be actual spawned clients; clients themselves must not bottleneck measurement.

### Task1: Shared immutable renderer preparation

Files: DisplayTransform.kt, AnimatedNpcHandle.kt, DisplayTransformTest.kt, NpcRenderPerformanceTest.kt.
Interface: DisplayTransform.prepareModel(transform:SceneRenderTransform):(NpcMatrix)->DisplayTransform.

- [x] Add independent prepared-geometry and committed-transform regression tests.
- [x] Observe missing prepareModel behavior RED.
- [x] Implement preparation and both handle invalidation paths; primitive quaternion and inverse-scale temporaries.
- [x] Run complete licensed Host tests, formatting and all983asset acceptance checks.
- [x] Freeze jar and compare actual-model serialized metadata against stablec3e662a9; fresh correctness review.

### Task2: Connected performance and scaling

Files: owned private artifact scripts/manifests; repository results report/backlog.

- [ ] Hash-pin baseline/candidate and run matched20-client settled comparison with separate safeJFR.
- [ ] Run20/40/60actual clients in bounded20-client workers, including look/movement and reentry bursts; validate zones/counts.
- [ ] Record CPU/MSPT/allocation/traffic/tail/spawn metrics and tradeoffs; retain only an evidence-backed change.
- [ ] Commit/publish reviewed source and results, activate private Stage image, verify both lobby pins/readiness/SOLID and cleanup.

## Execution ledger

BASE6702b89. User explicitly approved the proposed renderer-first sequence. Execute inline, no repeated design/permission gate. Existing worktree reused clean. The discarded whole PR233 is only a source reference for the isolated math change.

Task1 validation:172licensedHosttests,0failures/skips; spotlessCheck and all983addon-file acceptance pass. Actual15models produce723456serialized pose/dirty-state records with identical SHA25627daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e in frozenStagec3e662a9 and candidate8dfbb05b. Fresh independent review: no blockers. Ruling: scene root/local scales must be positive by format; golden fixture probes signed/collapsed scales through model matrices instead. No production change was needed for that harness correction.
