# NPC transition metadata implementation plan

**Goal:** Avoid full metadata collection for flags and duplicate full-pose serialization during cohort transitions.
**Architecture:** Typed flag access; fresh snapshots grouped only within one display/rate transition, using the existing personal/common send path.
**Tech Stack:** Kotlin, pinned Minestom, JUnit, owned Java protocol clients and safe JFR.
**Spec:** `docs/superpowers/specs/2026-11-02-npc-transition-metadata-design.md`

## Constraints and review focus

Preserve all admission/rate/deadline/identity and visual semantics. No geometry/SOLID/Scene/Resourcepacks modifications. No Stage activation without observability. Default zero and signed flags must survive the typed lookup; personal glow and scale must remain isolated; initial spawn must precede snapshots; different durations must never share a frame; removed/replaced viewers must not receive stale frames. Existing tests cover lifecycle/identity/deadlines, new tests cover transition frame sharing and queued ownership.

### Task 1 — typed flag reads

- [x] Record baseline bytecode and use existing semantic suites for the mechanical lookup refactor (no source-mirror test).
- [x] Replace only the two full-map flag reads; freeze a flags-only intermediate jar for attribution.

### Task 2 — grouped transition snapshots

- [x] Add behavioral regression tests in `NpcViewerUpdatesTest` proving shared immutable snapshots on an actual rate transition, separate durations and personalized isolation. Observe failure on the unoptimized path.
- [x] Extract `poseState(duration)` in `NpcItemDisplay`, preserve the single-player send method, add a collection overload reusing `sendMetadata` with membership checking. Group only the old transition predicate's recipients in `AnimatedNpcHandle.refreshViewers`.
- [ ] Run formatting/full licensed and ordinary suites, fixed pose proof and bundle acceptance; freeze the final candidate. Independently review correctness and ownership before merge.
- [ ] Run fresh matched owned-client measurements; retain exact input hashes, include adverse windows and limit conclusions to the measured pair. Update backlog/results.
- [ ] Exact-head CI merge only if qualified. Refresh private deployment draft and all deployment checks; live rollout requires verified management access.

## Ledger

Reused clean isolated worktree `/home/lukas/grounds/.worktrees/npc-pack-model-host`; branch `perf/npc-transition-metadata-20261102`, base `751edf6545951b4be360d59e7ea00145a78a539c`. Primary implements; fresh reviewer before merge. User's repeated continuation supplies execution/integration authorization; no additional design approval loop. Artifacts: `/home/lukas/grounds/artifacts/npc-transition-metadata-2026-11-02`.

- Typed flags intermediate:193 licensed tests pass. Transition regression RED on individual packet path, then full195 licensed tests0skip; ordinary195 with5expected asset skips; formatting green. Candidate2fce737357ac9f5adf87810d7c12933023dae84bb075c5d281deeee863d705f2. Fresh read-only reviewer found no actionable issue. Class diff changes two implementation classes plus debug-only facing initializer (non-debug javap identical); Scene/Resourcepacks unchanged. Golden723456states hash unchanged.
- Matched load pair in progress; unchanged owned recorder/harness. Controller import failure occurred before starting children and was corrected by copying the existing counting-proxy module. Initial acceptance accidentally used the older addon fixture and was rejected by the hash gate; rerun with the pinned isolated addon, without changing the gate or product.
