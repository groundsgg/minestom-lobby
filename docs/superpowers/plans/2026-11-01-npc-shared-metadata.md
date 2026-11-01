# Shared NPC Metadata Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Steps use checkbox syntax.

**Goal:** Reduce per-viewer serialization work without changing NPC packets, recipients or appearance.
**Architecture:** Partition one display delta into unchanged common recipients and personalized recipients; pass the common packet through Minestom PacketSendingUtils.sendGroupedPacket, keeping the native component-translation/cache policy.
**Tech Stack:** Kotlin, pinned Minestom 26.3/JDK25, JUnit5, licensed resourcepack/Scene composite, protocol777 headless clients.
**Spec:** ../specs/2026-11-01-npc-shared-metadata-design.md

## Constraints and review focus

- Base origin/main6702b89, accepted c3e662a9 private fixture. No mathematics/cohort changes from drafts #236/#233.
- Preserve Map9 sourcea1bec43f/lit0834f795, catalogueedge1793492850, addona0065344, all765test-platformcubes/471parts and fullscene619displays.
- Preserve nativeSOLID,64/72visibility, personal scale/glow, interpolation, full first-visibility state and queued frame ownership.
- Native grouped/cached packet flags and translatable-component fallback remain effective; do not build independent raw framing/protocol caches.
- Retain per-player outbound events and independent personalized packets. No identity/routing/proxy assumptions beyond existing instance viewers.

### Task1: Prove and implement common frame sharing

Files: scene/NpcItemDisplay.kt; tests/scene/NpcSharedMetadataTest.kt and RendererTestSupport.kt.

- [x] Capture original SendablePacket envelopes in test support, with optional deferred decoding.
- [x] Add and observe RED for two common viewers reusing one serialized frame; hand-check pose data for a third scaled/highlighted viewer.
- [x] Add old-frame delayed encoding and translatable-name fallback regressions; test empty/personal-only audience where relevant.
- [x] Implement partition and native grouped send in NpcItemDisplay only; avoid grouped work for empty audiences.
- [x] Run complete licensed and ordinary suites, formatting and all983addon acceptance checks; freeze candidate and review full branch independently.

### Task2: Connected acceptance and Stage

Files: owned private artifact scripts/manifests; results report and optimization backlog.

- [x] Hash-pin baseline/candidate; measure1/20/60actual clients including movement and three visibility reentries with matched counters. JFR outside throughput windows only.
- [x] Record CPU, allocations, tick tails and dispatch counts honestly. Reject any unsupported win; no client packet-count reduction claim.
- [ ] Publish reviewed source/results. If accepted, activate only private Stage image, verify live pins/readiness and clean up owned processes.

## Execution ledger

User's standing request authorizes sensible optimizations and continued execution; no repeated permission/design gate. These implementation choices are the assistant's calls. Reuse the existing clean Host worktree on a new independent branch. The mathematics candidate remains draft after the added attribution test reproduced no high tail and no consistent CPU benefit. This change targets confirmed repeated packet framing/translation work instead.

Task1 checks: default licensed suite174/174 with0skips; ordinary published dependencies174tests with5expectedasset skips; translation-enabled focused4/4; spotlessCheck,983addon acceptance and723456bit-identical pose records pass. Candidate SHA256 b3a9e34e03d8c05839aab208842e98567afa15aacb0d0d0460765be7fe4ed430. Host class diff is confined to NpcItemDisplay and its file facade; no other renderer/math/Scene classes change.

Ruling: automatic translation defaults false in the pinned framework. Test its configured policy explicitly and run the same focused suite in a fresh worker with translation enabled; do not mutate process-global immutable server properties or add test hooks to product code.

Task1 complete: fresh independent review of0603235 found no correctness blockers. Framework socket/cancellation/translation/compression paths and replacement quaternion-array ownership checked against pinned bytecode. Capturing-connection tests are not real-socket race tests; connected acceptance remains required. Review did not judge performance or Stage readiness.

Measured refinement before acceptance: first candidate b3a9e34e at1client allocates17.09MB/s versus15.87MB/s baseline. Shared serialization needs at least2common recipients. Add the single-common (including beside a personal viewer) regression first, observe RED, then bypass native grouping for one common recipient. The current frozen a-pair must finish unchanged; rebuild and verify the refined artifact separately before activation.

Refinement checks: the new single-common test first failed with an unexpected CachedPacket (RED), then passed after adding the cardinality guard.175licensed tests pass with0skips;175ordinary tests pass with5expectedasset skips;5translation-enabled focused tests pass. The refined e447d40b57476606dd9df3fc7223b71997c2c03ec4f4d878252f45e84b44743b artifact again passes983addon files and723456bit-identical pose records. Re-run the full connected candidate schedule against the same frozen baseline; disclose control reuse rather than calling it another independent pair.

Task2 numerical decision: retain for repeatable allocation reduction (final60mixed −24.2%) and lower60client processCPU(−4.3%), with exact geometry/data and no operational failures. Do not claim uniform tick/tail/RX improvements; the final unfavorable reentry window and scheduling counters are included in the report. Final e447candidate completes1/20/60, movement,3reentries and strict separateJFR with0disconnects/DataLoss; source map unchanged. Stage activation remains next.
