# Common NPC visual dispatch implementation plan

> Implement inline with superpowers:executing-plans, then obtain one independent critical review. The user has repeatedly authorized implementing the sensible NPC optimization backlog and requested continuation; no additional design-choice gate is needed.

**Goal:** Avoid repeated personal-state lookups and metadata personalization for ordinary viewers, preserving every emitted value and native grouped serialization.
**Architecture:** A null `NpcItemDisplay.visualState` represents the existing default visual state. AnimatedNpcHandle installs one shared provider only while its override map is nonempty and clears it when the last override disappears. SendMetadata normalizes default flags once, then collects eligible recipients exactly as before; retain the personal fallback. No recipient-list caching, spatial index, rate or geometry change.
**Tech stack:** Kotlin, pinned Minestom, actual renderer fixtures and complete private source builds.
**Spec:** Accepted NPC backlog N24/global versus personal rendering paths; current full-build Stage Map12 and addon remain the baseline. This is a local performance candidate until complete controlled measurements qualify it.

## Global constraints

- Keep 64/72-block view hysteresis,32-block vertical bound and200/250/500ms rates.
- Preserve player-specific scale/glow, all other flags, entry/reentry, cohort membership and queued packet ownership.
- A default viewer clears the glow bit even if base metadata contains it: default normalization must still occur once.
- Only current eligible viewers receive pending cohort updates; use native grouped sending and its translation rules.
- No private asset/public channel changes; compile whole private host with the existing source/addon/catalog pins.
- Compare unprofiled real protocol-client runs; include adverse windows. A component-only saving does not establish total CPU/TPS/RX improvement.

## Review focus

1. First personal override must take effect before any snapshot/metadata send.
2. Clearing the last override must restore default bytes and cost; remaining overrides must stay active.
3. Common flags with glow/on-fire must match prior per-viewer behavior.
4. Old enqueued frames must remain immutable after later scale/flag changes.
5. Removed viewers and individual/cohort paths must preserve actual recipients and native translation behavior.

### Task1: Default dispatch and lifecycle

Files: AnimatedNpcHandle.kt, NpcItemDisplay.kt, NpcRenderPerformanceTest.kt, NpcSharedMetadataTest.kt.

- [x] Add a real-renderer read-budget test:12default viewers/two changed parts must produce24correct packets without24personal override-map reads; verify RED. Add default-glow characterization.
- [x] Install/remove a single shared visual-state provider on override lifecycle transitions; normalize default metadata once per publication. Keep native recipient grouping unchanged.
- [ ] Verify GREEN, mixed scale/glow/clear and all regular/license-qualified tests; build private JAR and compare exact serialized outputs.

### Task2: Qualification and integration

Files: backlog/result note and private measurement artifacts.

- [ ] Build complete candidate from clean source with unchanged Resourcepacks76316571 and Lobby40b50d7 inputs, approved addon and catalog build1793977589. Production loader must accept actual Map12.
- [ ] Controlled baseline/candidate/candidate/baseline connected comparison with20/60clients, native map/terrain/collision, motion, view boundaries and reentry. No class overlays.
- [ ] Independent critical review, retain unfavorable measurements and make an evidence-based activation decision. Publish/deploy only a qualified candidate using the existing normal Stage process; otherwise retain accepted Stage and document the result.
