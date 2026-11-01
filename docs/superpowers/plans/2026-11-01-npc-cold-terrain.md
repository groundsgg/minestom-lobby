# NPC Cold Terrain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove first-visit lighting stalls and deploy the verified fix to Stage, then pursue measured NPC CPU improvements.

**Architecture:** Prepare terrain before module admission/ticking. Load all selected chunks before lighting any; use existing runtime lighting API. Keep the candidate cohort optimization separate until its CPU regression is resolved.

**Tech Stack:** Kotlin, Minestom26.3, Grounds runtime0.13.0, JUnit5, safe startup JFR, mc-stress protocol777.

**Spec:** ../specs/2026-11-01-npc-cold-terrain-design.md

## Global Constraints

- Preserve Map9, addon a0065344, all765 cubes, view distance and mounted native SOLID.
- No global resourcepack release or Production changes. Stage deploy explicitly authorized.
- Never attach to live JVMs; use isolated owned startup recordings with strict event whitelist.
- Do not load all21,774 map chunks. Fail preparation before opening admission.

## Review Focus

- Overlapping preparation areas must all load before light is computed.
- Negative coordinates and distant NPC centres must receive the configured view radius plus halo.
- Light must remain correct after subsequent block edits.
- Preparation failure must not retain registered worlds or admit players.
- Startup memory/time must fit the existing1Gi/1CPU Stage budget.

### Task1: Terrain preparation and lifecycle integration

Files: create LobbyTerrain.kt and LobbyTerrainTest.kt; modify LobbyModule.kt and lifecycle tests.
Interface: LobbyTerrain.prepare(world: LobbyMap, centres: List<Point>, radius: Int = ServerFlag.CHUNK_VIEW_DISTANCE + 1): Unit. Installation supplies authored NPC positions, spawn is always included.

- [x] Write regression tests proving real loaded dirty LightingChunks become clean, distant/overlapping/negative centres are loaded, and block mutation invalidates light again.
- [x] Run the focused test and observe missing production behavior.
- [x] Implement load-union then light, integrate before admission, remove post-start preload; test lifecycle failures.
- [x] Run full tests, formatting and bundle acceptance with pinned licensed assets.
- [ ] Commit and obtain fresh whole-change correctness review.

### Task2: Cold verification and Stage deployment

- [ ] Add a new hash-pinned candidate to private isolated harness; retain historical immutable artifacts.
- [ ] Compare same-harness fresh JVM spawn join and first platform visit; inspect tick series, JFR and process memory.
- [ ] Build private image and update only Stage lobby image pin; verify readiness, invariant pins and numerical metrics.
- [ ] Publish measured results and request the already-authorized user test on Stage.

### Task3: Profile-backed follow-up

- [ ] Assess N20 early interaction filtering with deterministic order/oversized fallback tests and before/after profile.
- [ ] Assess N32 bounds inverse caching if still material after N20; test affine invalidation and hit equivalence.
- [ ] Update backlog with evidence and defer unproven or risk-heavy changes.

## Execution ledger

Task1 BASE: 01a89bd05c0a84ec41ed54b39ef498e2dc63f817.
Ruling: Execute inline under existing user authorization; no redundant plan approval. Stage deployment explicitly requested after the fix passes.
Ruling: Existing GroundsWorld0.13.0 already has correct light preparation; reuse it instead of a Minestom fork or full-map preparation. Historical cold harness omitted production spawn preload, so new comparisons must represent it explicitly.

Review ruling: Future timeouts/interruptible waits cannot cancel Minestom chunk loads. Await owned startup work with join (allOf waits for every load, even failures) before propagating failure/unregistering. Startup remains unavailable until local disk preparation finishes; deployment/process health owns failed-start termination. The delayed-loader interruption regression failed before this correction. Removed unreachable null-loader fixture shortcut: Minestom normalizes absent loaders to NoopChunkLoaderImpl.

Task1 validation:166 licensed tests passed,0 failures/skips; spotlessCheck passed. Bundle acceptance validates all983 addon files and15models/471displays. Only LobbyModule generated classes differ from stable jar, plus new LobbyTerrain; native SOLID bytecode unchanged. Fresh independent review: initial async ownership issue fixed and re-reviewed with no remaining blockers.
