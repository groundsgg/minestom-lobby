# NPC animation phases implementation plan

**Goal:** Test N21 by spreading regular NPC animation updates across the existing tick grid.
**Architecture:** An internal per-handle clock uses immutable identity-based phases; forced lifecycle updates bypass throttling. Native shared metadata serialization remains unchanged.
**Tech Stack:** Kotlin, Minestom, JUnit 5, existing isolated Java/Azalea harness.
**Spec:** `docs/superpowers/specs/2026-11-02-npc-animation-phases-design.md`

## Global constraints

200/250/500 ms cadence, 64/72 block visibility, full model geometry, saved lighting, personal visuals and native SOLID stay intact. Only local Instance delivery is involved; network-wide player/routing behavior is untouched. Reuse current isolated worktree. User already authorized continuing the backlog, tests, qualified merges and Stage activation; no repeated design approval gate.

## Review focus

Forced state immediately before a regular deadline; interval changes; rewind/restart; large forward jumps; all displays belonging to one NPC remain synchronized. Clock tests and renderer integration cover these cases.

### Task 1: Implement and test the clock

**Files:** `scene/NpcAnimationClock.kt`, `scene/AnimatedNpcHandle.kt`, `scene/LobbyNpcModelRenderers.kt` below `src/main/kotlin/gg/grounds/minestom/lobby/`; corresponding `NpcAnimationClockTest.kt` and existing renderer tests.
**Interfaces:** clock `advance(time: Long, interval: Long, force: Boolean): Boolean`; constructor seed `Int`; renderer passes element/part identity hash.

- [x] Existing focused renderer tests: 19 pass; added regression is the only failure in the 20-test RED run.
- [x] Real renderer RED: tick 1 expected 2 metadata updates, received 0; prior behavior synchronizes all four NPCs.
- [x] Five clock tests cover phases/frequency, forced state, distance changes and seeks; the real-renderer RED demonstrates missing scheduling behavior before implementation.
- [x] Implement clock; 181 licensed tests pass (0 skips), ordinary 181 pass (5 expected asset skips); formatting and 983-file bundle acceptance pass. 723,456 fixed pose records retain SHA-256 `27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e`. This proves fixed-time geometry, not unchanged regular sample timestamps.
- [x] Commit code and tests after complete verification.

### Task 2: Measure and decide

**Files:** isolated artifacts under `artifacts/npc-animation-phases-2026-11-02`; existing load harness copied to fresh run names, frozen baseline `e447d40b5747` and frozen candidate.
**Interfaces:** two frozen host jars, same prelit Map9/Scene/catalogue; actual loopback Java clients; bounded startup-owned safe JFR, no live attach.

- [ ] Run fresh 1/20/60 client baseline/candidate matrix, moving and three exit/reentries; collect CPU, allocation, mean/p99/max and packet counts, plus host contention.
- [ ] Report all windows and limitations. Confirm client counts, no disconnects, complete displays, no JFR loss, original fixtures unchanged and owned cleanup.
- [ ] Obtain fresh correctness review of the complete branch; address material findings with RED/GREEN tests.
- [ ] If measured benefit justifies it, merge with exact-head CI gate and activate private Stage image through the existing workflow; otherwise preserve a draft with the measured rejection reason. Update backlog and final outcome.

## Execution ledger

- Ruling: continue inline without new design questions under the user's repeated instruction to work through sensible optimizations; selected phase scheduling is explicitly recorded in the spec.
- Base: `83e1fbaad23142c24dc0dee6c728bc453851b672`; worktree `/home/lukas/grounds/.worktrees/npc-pack-model-host`, branch `perf/npc-animation-phases-20261102`.

- Frozen candidate jar SHA-256: `809b673727b800d6635a1601e01dfef26bf8c8d920ef015246d3575e6a131a00`. A fresh baseline/candidate connected matrix is running; no Stage changes yet.

- Independent correctness review: one Important finding, rewind inside a skipped interval is not detected against lastRendered. Fix via last-observed timestamp and RED/GREEN regression; no other actionable findings. Review did not assess GUI, performance or rollout.
- Measurement ruling: baseline throughput windows finish before compiling the seek fix. Baseline JFR is diagnostic only and may overlap local verification of the candidate; do not use that recording for quantitative baseline/candidate CPU claims. Candidate start is gated on a new frozen reviewed jar; initial `809b...` is preserved and will not be measured/deployed as the final candidate.

- Review fix: separate last-observed timestamp detects seek within a suppressed interval. New test observed RED (expected true, got false), then whole licensed and neutral suites passed (182 tests); 723,456 fixed-time pose records and 983-file bundle acceptance still match. Frozen final jar `084388a2a8619adef07220a432584babcd76c3f842603eb15ad6744cd1b82297`.
- Bookkeeping correction: first freeze-proof script addressed the wrong XML parent after copying the jar; proof creation failed without changing the jar. The corrected script verified identical source/frozen hashes and captured the neutral 182-test result (5 expected skips). Licensed XML was overwritten by the subsequent neutral run; preserve full logs and recheck licensed counts after throughput measurements, rather than inferring archived XML contents.
