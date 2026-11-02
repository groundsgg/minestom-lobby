# NPC rate allocation and motion diagnostic plan

**Goal:** Remove confirmed per-viewer rate boxing and gather event-aligned evidence for the motion-start outlier.
**Architecture:** Maintain primitive rate minimum inside the existing visibility scan. Use an owned startup-only diagnostic harness, safe JFR and numeric counters; production gains no instrumentation.
**Tech stack:** Kotlin/JUnit, JDK25, local Azalea clients, existing loopback byte counter.
**Spec:** `docs/superpowers/specs/2026-11-02-npc-rate-allocation-design.md`

## Constraints and review focus

No behavior/rate/deadline/geometry/SOLID change. Preserve no-viewer fallback, actual instance admission, rate hysteresis, same-UUID reconnect and queued ownership. Existing193tests exercise these. No new source-mirror unit test for a mechanical aggregation refactor. Stage API unavailable; source and draft preparation may proceed, GitOps activation may not.

### Task1 — remove confirmed boxing

**Files:** `src/main/kotlin/gg/grounds/minestom/lobby/scene/AnimatedNpcHandle.kt`; docs/backlog. Artifacts under `/home/lukas/grounds/artifacts/npc-viewer-rate-allocation-2026-11-02`.

- [x] Inspect published candidate bytecode: rate minimum boxes each interval and compares Comparable. Preserve baseline proof.
- [x] Audited Long samples point to refreshViewers minimum: approx30.7MB weighted pressure in animation updates and9.7MB in visibility task after boundary cutoff (not exact rates). Removed boxed second pass; candidate bytecode uses primitive Math.min.
- [x] 193licensed tests0skip, neutral193with5expectedasset skips; formatting,723456bit-identical fixed pose records and983-file addon acceptance pass. Frozen jar92154af1cc775098008a2da4d0e46092526ab19b65261ac9af260b88c8c7ea44.
- [x] Independent read-only review found no actionable issue; checked fixed rate minimum, no-viewer fallback, admission/hysteresis, cached identity replacement and pinned Minestom duplicate-UUID registration guard. Source committed ca91972; draft PR240 CI green.

### Task2 — matched measurements and outlier diagnostic

**Files:** owned Java harness copy, numeric JFR analyzer and local controller in the artifact root. New run names under the existing load-profile root.

- [x] Owned harness adds24000-capped numeric tick counters and recording-active callback; existing ten-event whitelist unchanged. Record first movement at60clients before later unprofiled motion/reentries, then20ssettle. These later windows have warmed movement/JFR classes, so compare only the fresh matched pair.
- [x] Fresh old/new1/20/60unprofiled steady windows; separate90s first/second motion diagnostic,20ssettle, then unprofiled warmed motion and3reentries. All counts/fixture checks passed, no disconnect/loss.
- [x] Complete results recorded in2026-11-02-npc-rate-allocation-results.md, including near-flat whole-JVM allocation, higher traffic and adverse p99/maxima. First diagnostic peaks51.431→37.543ms, second7.456→9.101ms; no matching GC pause. Scheduler barrier waits observed; exact original56ms cause remains unresolved.
- [ ] If qualified, exact-head CI merge; prepare a new private artifact and update draft #555 with all128deployment tests. Keep live rollout blocked by unavailable API. Report source/draft states accurately.

## Ledger

Reused isolated worktree `/home/lukas/grounds/.worktrees/npc-pack-model-host`, branch `perf/npc-viewer-rate-allocation-20261102`, base `ac9b33a292d4dc2b238ef67169b1a7cebf4bce40`. User's repeated continuation and earlier merge/Stage preferences authorize sensible work without another design/approval loop. Primary implements; critical final review is separate.

- Baseline motion diagnostic: first-start51.431ms tick at+164ms, no estimated GC overlap; second-start max7.456ms. Three tick-thread samples inside the first peak include refreshViewers twice and TriggerEngine once. Full-pose-shaped network counters rise later; do not attribute all delay to serialized snapshot traffic or GC. Await matched candidate evidence. Original unprofiled56.210ms peak was+281ms after motion mark, before first counter sample+1011ms.

- Source accepted as small verified allocation-site refactor, not a universal memory/TPS gain. Matched unprofiled moving CPU-5.0%, mean-11.5%; total allocation nearly unchanged and network+1.5–4.8% in several windows. Single-run observations only.
- Infrastructure access diagnosis: management APIs intentionally tailnet-only per grounds-pulumi README. Existing Windows client status NoState/offline. Asked user asynchronously to activate existing connection; no device routing/auth/firewall change. Draft#555 will hold updated private92154af1 artifact until actual access is verified.
