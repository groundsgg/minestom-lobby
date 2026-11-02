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
- [x] Isolated lifecycle/accumulator port, enum rates and grouped/native-cached dispatch. 190 licensed tests pass0skips, ordinary190with5expected asset skips; 723456fixedposebits unchanged,983addonfiles accepted.
- [x] Added cache/actual-viewer/small-seek regressions. Core12cohort cases observed7RED on main, all15pass in candidate. Minestom automatic viewers require updateViewableRule in the test setup; removeViewer returned false (test setup failure preserved).
- [x] Independent correctness review completed; one same-UUID replacement invalidation bug reproduced RED (2 expected updates,0 received), fixed by detecting Player object identity, full191licensed/neutral suites GREEN. No other blockers; corrected artifact cd1a319d is frozen and used for candidate run.

### Task 2: Measure and decide

**Files:** isolated artifacts under `/home/lukas/grounds/artifacts/npc-cohort-dispatch-2026-11-02`; raw owned test fixture runs in the existing load-profile artifact root.
**Interfaces:** frozen accepted `e447d40b5747` baseline and new candidate, identical bounded connected-client workload with server position confirmation.

- [x] Complete baseline A and two complete candidates A/B with 1/20/60 clients, motion, three reentries, separate audited JFR and actual loopback TCP bytes; B uses the same control, not another independent pair.
- [x] All complete/failed runs and input/count proofs preserved. Full report includes adverse20client mean/p99, moving56ms transition, reentry3maximum, increased allocation and all-near traffic. Backlog updated; source qualified for Stage trial only.
- [ ] Merge qualified source after final exact-head CI; private Stage image and draft GitOps pin prepared. Stage API still fails bounded10s read; do not activate GitOps or claim live verification. No Production change.

## Execution ledger

- Reuse existing isolated worktree; previous experiment retained on its own branch. No new subagent implementer; primary owns architecture/integration/correctness decisions. Fresh final reviewer only as required by review skill.

- Independent review: one Important same-UUID reconnect issue in cached Player recipients; new real reconnect regression pending RED/GREEN, no other product blockers. Proxy review no obvious counting/safety defect; exact150000byte bidirectional local echo passed.
- Final benchmark runner is gated on a new reviewed frozen artifact; initial9574e4f4 jar preserved, not eligible for candidate execution. Baseline throughput is uncontaminated by rebuilds; candidate verification will overlap only the separate diagnostic reference profile. Do not compare profile sample counts quantitatively.

- Refined validation: 191licensed tests0skips, neutral191with5expectedasset skips; golden723456records hash27daa369 unchanged,983file acceptance passes. Frozen final jar cd1a319d8d89db13c191448ba776c21520f4ccc31cd44c51d0249a69df6b436b. Only metadata membership/delivery changes are included.

- Initial full candidate A cd1a319d: mixed20/60wirebytes -24.1%/-27.2%, CPU +3.5%/-3.3%, allocation +9.4%/+3.1%; moving bytes -21.0%, CPU -1.2%, allocation+11.4%. All-near reentry CPU +2.0–5.7%, mean+13.2–16.2%; retain complete summary-a.json, no rollout acceptance.
- Ruling: add a single-cohort direct-delivery fast path to avoid accumulating when no receiver needs a different rate. Snapshot preparation still defers changes; a pending delta or future cohort deadline keeps regular cadence. Homogeneous direct metadata test observed RED(1expected,0received), then GREEN. A second test exposed an early900ms update after the500ms deferred flush; RED, fixed with deadline-aware preparation, all193licensed/neutral tests GREEN.
- Frozen B fast jar:2a058aeb42f56543a6c39f0386569d3966b2e2d4a23f524a5369052e63eb30ab;193licensed0skip,neutral1935expectedskip,golden723456unchanged,983file acceptance pass. Candidate B reuses baseline A; do not present it as a second independent A/B pair.

- Additional independent review of new direct path (24cb6c5..0efd8ae) found no actionable correctness findings. Final B193tests remain green. Full results:2026-11-02-npc-cohort-dispatch-results.md. Stage trial accepted for repeatable21–26%mixed workload byte savings with steadyCPU near parity; explicit56ms transient, allocation and small-audience regressions carried forward. No generalMSPTgain claim.
