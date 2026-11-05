# NPC Viewer Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline.

**Goal:** Remove redundant player resolution/copying from per-display viewer snapshots.
**Architecture:** Capture the pinned Minestom snapshot iterator before updateViewableRule and consume it afterward.
**Tech Stack:** Kotlin/JVM25, pinned Minestom, existing renderer tests and offline connected harness.
**Spec:** docs/superpowers/specs/2026-11-05-npc-viewer-snapshot-design.md

## Constraints and review focus

Keep independent display membership, complete state on entry, retained rate changes, same-UUID replacement, empty/singleton/multi-viewer audiences, immutable queued packets and all prior SOLID/scale fixes. Do not change cadence, visibility, models, dependencies or network identity/routing.

### Task 1: Snapshot iteration and regression

Modify AnimatedNpcHandle.kt and NpcViewerUpdatesTest.kt under src/{main,test}/kotlin/gg/grounds/minestom/lobby/scene/.
- [x] Run baseline full test suite.
- [x] Add real InstanceContainer entity-resolution budget for a viewer cohort rate transition; record failing old implementation. Add per-display missing-body/reentry snapshot regression.
- [x] Replace only toList() with the already stable iterator; retain the capture/update/consume order.
- [x] Run focused tests, full test/check/format and independent critical review.

### Task 2: Qualification and integration

Use artifacts/npc-viewer-snapshot-2026-11-05 for exact runtime/probes/load evidence.
- [x] Verify exact accepted baseline and candidate differ only in intended Host runtime entries; run tests against the candidate JAR.
- [x] Compare connected20/60 clients with motion, boundary and reentry; retain adverse phases and reverse the comparison when needed.
- [ ] If qualified, create reviewed PRs, roll out only Stage Lobby image/pin, verify both lobbies and obtain native acceptance before final Source integration.
