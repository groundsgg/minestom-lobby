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

- [ ] Refresh primary sources for ModelEngine, BetterModel, MythicMobs, ItemsAdder/Oraxen and update backlog with applicable unresolved items and caveats.
- [ ] Licensed/ordinary suites, unchanged golden/bundle, exact candidate freeze; fresh connected cold baseline/candidate fixture with full-model admission latency retained. Independent final correctness review.
- [ ] Qualified source PR and exact-head CI. Preserve current Stage overlay/pins in separate deployment only if evidence qualifies and management is observable; verify actual rollout and ask fresh gameplay check. Record all adverse evidence and handoff.

## Ledger

Ruling: use existing complete-element count budget instead of introducing a wall-clock renderer queue. A single complex model remains indivisible; risk is a remaining single-model peak, to be measured. This is announced to the user and must not be described as a hard2ms cap.
Ruling: pure allocation changes preserve behavior and use existing/new semantic characterization plus allocation/bytecode evidence rather than a fabricated RED test for an internal cache.

Task1: atomic-publication RED observed (2 failures: pre-pose partial spawns and rejected sibling exposed); GREEN full ordinary suite {'tests': 199, 'failures': 0, 'errors': 0, 'skipped': 5} via Gradle spotlessApply test, task1-green.log. Prepared templates and direct flags covered by mutation/packed-bit characterization and existing semantics.

Ruling: Scene close is asynchronous and marshalled to its owner; one in-flight activation may settle before close completion. Shutdown regression pins no new starts after the returned close future completes and verifies all entities removed. Enforcing no work from the instant close() is requested would change the upstream lifecycle contract.

Task2: real three-NPC admission RED (all three factories at tick1) → GREEN with one complete transition/tick; ordinary full201 tests,0failures/errors,5expected licensed skips. Task2-green.log; shutdown completion boundary pinned.
