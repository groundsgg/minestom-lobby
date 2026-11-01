# NPC Performance Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. User authorized direct implementation.

**Goal:** Reduce NPC packet traffic and render allocations while retaining existing visual behavior.
**Architecture:** Preserve scene semantics and the model asset contract. Bound and coalesce renderer updates; cache immutable model transforms; apply field deltas and per-viewer distance rules.
**Tech Stack:** Kotlin, Minestom 26.3, Gradle/JUnit, Python asset compiler.
**Spec:** ../specs/2026-11-01-npc-performance-design.md

## Global Constraints

- Preserve existing clips, sounds, personal scale/highlight and head/body limits.
- Preserve scene collision/interaction and network-wide action routing.
- Do not touch the concurrent native-collision proof of concept or deploy/release processes.

## Review Focus

- Viewer re-entry must show current animation and personal state.
- Updates without viewers must retain elapsed animation time.
- Rotations near wrapping and signed/zero/nonuniform scale must retain affine geometry.
- Closing during attachment or chunk movement must not leak entities or send stale packets.
- Pack and renderer geometry changes must stay bound to their exact digest.

### Task 1: Field deltas and stationary entities

Files: DisplayTransform.kt, LobbyNpcModelRenderers.kt, NpcItemDisplay.kt and renderer tests.
- [x] Write failing packet delta and no-teleport-on-yaw tests; verify RED.
- [x] Set only changed serialized fields, exit converged eigen loop, disable decorative physics and teleport only when position changes.
- [x] Verify GREEN and full host baseline regressions (143 tests, 0 failures).

### Task 2: Cached skeletal evaluation

Files: resourcepacks-npc-runtime NpcModel.kt and NpcModelTest.kt.
- [x] Pin geometry and repeated-pose behavior with tests, verify baseline (129544 bytes per fifty-part pose fails the 50000-byte regression).
- [x] Precompute bone origins, parent indices, head and static part matrices; preserve public JVM overloads and thread-safe sampling.
- [x] Verify original model tests and 15-model equivalence (881280 matrix values, max error 5.3e-16).

### Task 3: Bounded updates and viewers

Files: LobbyNpcModelRenderers.kt, NpcItemDisplay.kt, focused performance tests.
- [x] Failing tests for cadence, accumulated head angles, per-viewer 3D distance/instance isolation, no-viewer suspension and current pose on re-entry.
- [x] Coalesce transform/head work into animation cadence, personalize only when needed, refresh distance rules safely and interpolate to cadence.
- [x] Verify focused regressions and full host suite.

### Task 4: Plugin comparison and measured integration

- [x] Record primary-source research and separate documentation from inference.
- [x] Evaluate per-bone compiler grouping with geometry checks; preserve old-pack compatibility for runtime improvements.
- [x] Build exact diagnostic candidate, compare original vs candidate packet counts/bytes/allocation and 15 model geometry.
- [x] Independent whole-change review; fix material issues, rerun relevant checks and present concrete result.

## Results

153 Host, 326 Resourcepack and 13 compiler tests pass with no skips on the current shared source baseline, including native SOLID. Formatting passes. Whole-change independent review found one queued-head-on-stop defect, reproduced and fixed with regression. Local benchmark records 68–77% fewer metadata packets, 79–84% fewer serialized metadata payload bytes, and 86–91% fewer temporary allocations. Geometry comparison preserves all 765 textured cubes with 471 displays; sampler comparison checks 881280 matrix values. Full evidence and paired candidate artifacts are under /home/lukas/grounds/artifacts/npc-performance-2026-11-01.

The private Stage candidate was activated through deploy#512 and its catalogue-binding recovery deploy#513. The final server acceptance records both lobbies Ready/Allocated, zero restarts, unchanged Map9, native SOLID and the matching grouped addon. The production-JAR acceptance tool rejects the wrong catalogue and altered ZIP metadata. Client visual acceptance remains separate. The current-main source PRs feed the single coordinated Resourcepacks 1.3 / Lobby 2.15 release with the Queue/Scene work; this task does not start another regular release.
