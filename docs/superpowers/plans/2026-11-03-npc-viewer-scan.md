# NPC viewer-scan implementation plan

**Goal:** Avoid allocation when current viewer admission/rates/identities are unchanged, without reducing scan frequency.
**Architecture:** Read-only cache equality predicate; existing full reconciliation on mismatch.
**Tech stack:** Kotlin/Minestom, JUnit, owned numeric startup probes, real headless protocol clients and audited JFR.
**Spec:** `docs/superpowers/specs/2026-11-03-npc-viewer-scan-design.md`

## Constraints and review focus

Preserve exact ranges/rates/deadlines, initial spawn ordering and native SOLID. Do not import other draft algorithms. Check equal-count exchange, same-UUID replacement, removals, changed transform/rate and empty/resuming instances. Existing tests cover these except explicit equal-count leave/enter, added below. No source-mirror allocation test; performance acceptance uses real load/recordings. No new mutable scratch cache or instrumentation in production.

### Task1 — diagnosis

- [x] Offline ASM overrides only the accepted frozen handle/policy; four call sites/two return sites verified, numeric counters only. Original jar SHA unchanged.
- [x] Bounded1/20/60client diagnostic,20s movement and1reentry complete.525scans/s; steady100%unchanged, moving96.99%;0probe overflow. A controller path preflight failed before starting children; retained, fixed, fresh suffix b. This is not throughput evidence.

### Task2 — candidate and qualification

- [x] Add balanced equal-count leave/enter regression in `NpcViewerUpdatesTest`; verify existing behavior before the mechanical optimization.
- [x] Add `viewersUnchanged(instance: Instance, anchor: Vec3): Boolean` to `AnimatedNpcHandle`, return early from refresh only on exact cache agreement. Preserve the full fallback.
- [x] Full licensed/ordinary suites, formatting, fixed723456pose digest and983-file addon acceptance; freeze and prove only intended bytecode changes. Independent critical review.
- [ ] Fresh uninstrumented baseline/candidate owned-client pair, full adverse windows, sampled allocation-site evidence and cleanup. Original56ms motion outlier remains open unless actually diagnosed.
- [ ] Qualified changes only: exact-head CI merge and separately validated observable Stage rollout. If not qualified, retain report and source without adding an unqualified rollout.

## Ledger

Existing isolated worktree `/home/lukas/grounds/.worktrees/npc-pack-model-host`; branch `perf/npc-viewer-scan-20261103`, base751edf6545951b4be360d59e7ea00145a78a539c (current main). Primary implements; final reviewer is independent. User's continuation supplies execution/integration authorization. Artifacts `/home/lukas/grounds/artifacts/npc-viewer-scan-2026-11-03`. Five current open PRs were inspected and explained to the user; no release/merge/closure inferred from the status question.

- Independent review found a weak-iteration reconnect edge: cached UUID/rate/identity can match while the running minimum is transiently stale. Controlled real removal/replacement through a weak Set reproduces a delivered1.3pose where1.4is expected (RED); original full path passes the same test. Predicate now also verifies primitive minimum against renderInterval, default500. No Long boxing, cadence change or persistent scratch state.

- Reconnect correction GREEN in full195licensed tests0skip; ordinary195pass with5expected asset skips. Formatting,723456fixed states SHA27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e and983-file bundle gate pass. Frozen13f8ca505cead847019573ccd8843892bb9540b3f6b7b84b0835dd4f4899d3d6. Only handle implementation changes; facing initializer differs only in debug info; Scene/Resourcepacks/NpcItemDisplay identical. Probe/ASM classes absent. Final read-only review has no remaining finding. Normal baseline/candidate run uses no overrides or probe classes; diagnosis and throughput are kept separate.
