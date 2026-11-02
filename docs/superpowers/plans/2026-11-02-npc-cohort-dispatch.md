# NPC cohort dispatch implementation plan

**Goal:** Reduce irrelevant animation delivery per viewer without reintroducing #233's avoidable dispatch overhead.
**Architecture:** Per-handle enum rate membership and recipient groups; per-display bounded immutable delta accumulators; native cached fanout per group. Preserve accepted pose math and normal cadence.
**Tech stack:** Kotlin/Minestom/JUnit, existing isolated Java/Azalea test harness.
**Spec:** `docs/superpowers/specs/2026-11-02-npc-cohort-dispatch-design.md`

## Global constraints

Same models/catalogue/Map9/addon, 64/72 block range and vertical32, nominal200/250/500ms rates, native SOLID and personal scale/highlight. Do not import math #236 or phase clock #238, nor runtime metrics from #233. Reuse `/home/lukas/grounds/.worktrees/npc-pack-model-host` on `perf/npc-cohort-dispatch-20261102`, base `83e1fba`. The user authorized continued sensible backlog work; implement inline without new approval gates.

## Review focus

Snapshots vs pending deltas at rate change; entry without actual Entity viewer membership; backward seek inside a throttled interval; no actual viewers left after disconnect; delayed cache/array ownership. Existing cohort/scale/cache tests plus narrow new regressions own these cases.

### Task 1: Isolate and test cohort dispatch

**Files:** main scene `AnimatedNpcHandle.kt`, `NpcItemDisplay.kt`, `NpcRenderPolicy.kt`, new `NpcPendingMetadata.kt` and `NpcViewerRate.kt`; tests `NpcViewerUpdatesTest.kt`, existing performance/scale/cache tests.
**Interfaces:** `NpcViewerRate.interval: Long`, `configureUpdates(Set<NpcViewerRate>)`, `flushUpdates(rate, recipients)`, shared `sendMetadata(changes, recipients)` filtering actual spawned viewers.

- [x] Reuse 12 existing real-renderer cohort regressions without runtime metrics. Observe missing behavior on accepted main: 7 fail / 5 pass, including incorrect slow-viewer duration and unwanted fast delivery.
- [ ] Port lifecycle/accumulator semantics only, remove metrics and prepared math. Use shared cached packet fanout and grouped recipients. Run focused tests then full licensed/neutral suites, fixed-pose and addon acceptance.
- [ ] Add regressions for shared cache per cohort, no deltas to unspawned viewers, and seek within a suppressed interval; demonstrate RED/GREEN. Optimize only proven hot paths, preserving single-group behavior.
- [ ] Independent correctness review of complete branch and fixes; commit tested code.

### Task 2: Measure and decide

**Files:** isolated artifacts under `/home/lukas/grounds/artifacts/npc-cohort-dispatch-2026-11-02`; raw owned test fixture runs in the existing load-profile artifact root.
**Interfaces:** frozen accepted `e447d40b5747` baseline and new candidate, identical bounded connected-client workload with server position confirmation.

- [ ] Fresh 1/20/60 clients, motion and three reentries; CPU/allocation/mean/p99/max/dispatches, safe bounded JFR separately. Confirm actual traffic bytes if supported by the client/harness; otherwise state limitation.
- [ ] Preserve all complete/failed runs, verify counts/fixtures/cleanup. Update optimization backlog and PR with acceptance/rejection reason.
- [ ] Only merge/activate a qualified result; if Stage API remains unavailable, report the actual rollout limitation and complete unaffected work. No Production change.

## Execution ledger

- Reuse existing isolated worktree; previous experiment retained on its own branch. No new subagent implementer; primary owns architecture/integration/correctness decisions. Fresh final reviewer only as required by review skill.
