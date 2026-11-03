# NPC activation preparation implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline. Primary implements all changes; final independent review.

**Goal:** Reduce first-viewer construction bursts while publishing complete NPCs and preserving gameplay.
**Architecture:** Prepared immutable item templates and direct metadata flags; existing whole-element Scene transition budget; hidden model attachment until complete initial state.
**Tech Stack:** Kotlin, Minestom26.3, Scene0.4.4, JUnit, owned protocol777 fixtures.
**Spec:** `docs/superpowers/specs/2026-11-03-npc-activation-preparation-design.md`

## Global constraints

Preserve SOLID position confirmation, model geometry, interpolation/scale coherence, view distance, viewer cohort rates, addon/catalogue/Map9 inputs, cross-proxy behavior and current Stage announcements. No Production/release action. A count budget is not advertised as a hard time ceiling.

## Review Focus

- Partial/failed attachment must never expose sibling displays or leak entities.
- Success must prepare all part poses before first viewer spawn.
- Budgeted initial admission must spread starts and eventually complete all NPCs.
- Shutdown during a pending activation queue must prevent later spawns.
- Immutable templates and packed flags must not leak state between NPCs or viewers.

### Task1: construction, flags and atomic initial state

Files: LobbyNpcModelRenderers.kt, NpcItemDisplay.kt; tests NpcActivationPreparationTest.kt, NpcSharedMetadataTest.kt.
- [x] Add real attachment cancellation/publication tests; observe RED for premature spawn.
- [x] Prepare immutable per-part stacks; attach with viewers denied until complete handle initialization. Read packed flags directly. Characterize all flag bits and template mutation isolation; no test that only checks cache implementation.
- [x] Full ordinary suite and formatting; bytecode/allocation evidence follows final freeze.

### Task2: bounded complete-NPC transitions

Files: LobbySceneActions.kt; test LobbyNpcActivationBudgetTest.kt.
- [x] Real Scene runtime test with three nearby automatic NPCs, at most one factory start per tick and eventual completion; shutdown while queued prevents subsequent starts. Observe RED before config change.
- [x] Configure only Lobby Scene request transitionBudgetPerTick=1. Verify full suite, commit.

### Task3: research and qualification

- [x] Refresh primary sources for ModelEngine, BetterModel, MythicMobs, ItemsAdder/Oraxen and update backlog with applicable unresolved items and caveats.
- [x] Licensed/ordinary suites, unchanged golden/bundle, exact candidate freeze; fresh connected cold baseline/candidate fixture with full-model admission latency retained. Independent final correctness review.
- [ ] Qualified source PR and exact-head CI. Preserve current Stage overlay/pins in separate deployment only if evidence qualifies and management is observable; verify actual rollout and ask fresh gameplay check. Record all adverse evidence and handoff.

## Ledger

Ruling: use existing complete-element count budget instead of introducing a wall-clock renderer queue. A single complex model remains indivisible; risk is a remaining single-model peak, to be measured. This is announced to the user and must not be described as a hard2ms cap.
Ruling: pure allocation changes preserve behavior and use existing/new semantic characterization plus allocation/bytecode evidence rather than a fabricated RED test for an internal cache.

Task1: atomic-publication RED observed (2 failures: pre-pose partial spawns and rejected sibling exposed); GREEN full ordinary suite {'tests': 199, 'failures': 0, 'errors': 0, 'skipped': 5} via Gradle spotlessApply test, task1-green.log. Prepared templates and direct flags covered by mutation/packed-bit characterization and existing semantics.

Ruling: Scene close is asynchronous and marshalled to its owner; one in-flight activation may settle before close completion. Shutdown regression pins no new starts after the returned close future completes and verifies all entities removed. Enforcing no work from the instant close() is requested would change the upstream lifecycle contract.

Task2: real three-NPC admission RED (all three factories at tick1) → GREEN with one complete transition/tick; ordinary full201 tests,0failures/errors,5expected licensed skips. Task2-green.log; shutdown completion boundary pinned.

Final review: no new merge-blocking source finding at1719b93. Minor (pre-existing, deferred): a direct caller cancellation during pending attachment may briefly publish then remove a complete model; normal Scene0.4.4 does not cancel renderer futures. Budget tests prove distinct start ticks for real placeholder renderers; licensed completion latency is measured separately in connected runs.
Ruling: native prepared ItemStacks require bound Minestom material registries. Production module installation already follows MinecraftServer.init; the standalone bundle gate now explicitly initializes the registry first, without starting a listener.

Qualification: ordinary201tests/5asset skips; licensed201/0skips; exact announcements-preserving Stage jar201/0skips with loaded-class origin proof. Golden723456states unchanged27daa369..., addon983files accepted after explicit registry init. Frozen Stage f74f3e927c2d..., base46da882e...; exactly3entry payloads differ, duplicate archive entries preserved. Cold ABBA complete, full latency retained; sustained connected load next due possible naturally staggered render phases.

Artifact review: all40,415 physical ZIP entries retain order and duplicates; only3reviewed classes change and match the built source candidate. No harness class shadows application classes. The exact active Stage base is the independently pinned Announcements revision ab5c739, not deploy/main. Any Stage pin must preserve that base and ownership settings.

Bytecode proof: both sendVisualFlags/sendPoseState had1getMetadataPacket call and now have0; each now reads MetadataHolder.get directly. Semantic packed-bit and personal-highlight tests pass. No whole-JVM allocation percentage is inferred from this component removal.

Sustained qualification must require final exitCode0, unchanged original map, exact jar hash and cleanup success, not the early verified-complete marker alone. Exit/reentry in this harness tests viewer re-admission while619entities remain attached (Y-only travel, Scene activation uses X/Z); it is not cold reconstruction. One baseline/candidate pair does not prove reproducibility.

Final sustained qualification: both full20/60client runs exit0 with unchanged map/exact jars/cleanup success, no disconnect/proxy errors,619displays and no recording. Mixed CPU/means/tails retained, including steady60mean+6.72%/p99+28.72% and first-readmission maximum+70.70%. Cold first-entry maxima reduced with335–437→743–1152ms full-scene latency tradeoff. Qualified only for bounded Stage trial; no general mean-tick or historical441.802ms fix. Full [qualification report](2026-11-03-npc-activation-preparation-results.md). Source/deployment integration remains contingent on exact-head CI and actual live readback.
