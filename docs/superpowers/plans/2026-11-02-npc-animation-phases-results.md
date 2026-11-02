# N21 animation phase experiment — not accepted for rollout

Date: 2 November 2026. Source PR: https://github.com/groundsgg/minestom-lobby/pull/238. Reviewed code commits `8d4dbf6` and rewind fix `fb09be0`, based on accepted main `83e1fba` (#237). **Decision: retain draft; no merge, private image build/push or Stage/GitOps activation.** The user confirmed scale and SOLID on the existing Stage build before this experiment; that is not GUI acceptance of this candidate.

## Result

Stable phases distribute synthetic simultaneous-handle work and preserve nominal 200/250/500 ms intervals. They do not produce a sufficiently consistent real-workload gain. In the 60-client mixed window mean tick work falls 7.2%, but process CPU is essentially unchanged (-0.7%), p99 improves only 2.6%, allocation increases 4.7% and metadata dispatches increase 7.0%. During motion CPU increases 14.6%, mean tick work 23.0%, p99 7.3%, allocation 6.2% and metadata dispatches 12.0%. Two reentries also consume more CPU. Lower small-audience tails alone do not justify deployment.

The fixed phase grid admits updates sooner after a prior/forced render and avoids some accumulated tick-rounding delay. Compared with the old elapsed-since-render gate, actual update/dispatch counts increase even though nominal intervals are unchanged. This is a workload change, not a packet saving. Host contention and JIT also differ; the table is an observation from one completed pair, not a causal claim that all timing changes come from phase scheduling.

## Complete primary comparison

Values are previous accepted shared-serialization build → phase candidate. CPU is process CPU cores used; allocation MB/s is JVM object allocation, **not network throughput**. Metadata packets/s are server dispatches summed over all clients. p99 is computed from raw per-tick wall times inside the same measured counter window.

| Phase | Mean ms | p99 ms | CPU cores | Allocation MB/s | Metadata packets/s |
| --- | --- | --- | --- | --- | --- |
| steady-1 | 1.441 → 0.948 | 6.154 → 2.933 | 0.0639 → 0.0556 | 15.62 → 16.11 | 1704 → 1786 |
| steady-20 | 1.764 → 1.268 | 7.246 → 5.113 | 0.0989 → 0.0842 | 18.95 → 19.75 | 31952 → 33534 |
| steady-60 | 2.104 → 1.952 | 6.029 → 5.870 | 0.1600 → 0.1589 | 25.94 → 27.16 | 95269 → 101973 |
| moving-60 | 2.343 → 2.881 | 10.171 → 10.919 | 0.1804 → 0.2066 | 33.36 → 35.44 | 92526 → 103621 |
| reentry-0 | 1.802 → 1.731 | 5.523 → 5.116 | 0.1467 → 0.1463 | 36.82 → 37.64 | 102215 → 106128 |
| reentry-1 | 2.329 → 2.172 | 7.015 → 7.311 | 0.1464 → 0.1572 | 36.54 → 37.60 | 99739 → 105802 |
| reentry-2 | 1.644 → 1.859 | 3.958 → 4.563 | 0.1388 → 0.1447 | 36.86 → 37.83 | 102892 → 107470 |

The complete moving phase maximum rises **38.757 → 56.327 ms** (at least one candidate tick exceeds the 50 ms tick budget); this is retained, not removed as an outlier. The corresponding strictly measured interval maximum is 21.589 → 56.327 ms. Whole-phase maxima for reentries improve 20.250→15.907, 25.108→22.619 and 24.172→22.698 ms, but their mean/p99/CPU results are mixed as above. Both runs average approximately 20 TPS throughout; no disconnects occurred.

Host CPU0 busy time differs substantially at small audiences (1 client 49.8→32.6%; 20 clients 51.7→36.3%). At 60 mixed it is 52.1→52.9%, and in motion 47.6→55.2%. Moving-phase JIT time is 1019→1415 ms and GC time 50→66 ms. These observations limit attribution; they neither identify the owner of other core work nor prove that all regression is external. Tick-thread CPU/scheduler counters include between-tick pacing and cannot be added as a decomposition of TickMonitor wall time. Full p95, maxima, GC/JIT, scheduling and counter evidence are in the raw summary.

## Method and integrity

- One complete baseline A / candidate B pair, fresh JVM per variant, 1→20→60 actual protocol-777 headless Java clients. 1 client near; 20/60 mixed near/mid/far; deterministic server-owned motion confirmed by clients; three exits and all-near reentries. This is not GUI/FPS/Bedrock/walking-physics validation or a cross-proxy integration test.
- Existing bounded harness, one CPU affinity core, 512 MiB max heap, empty application environment except PATH, local-only listeners, external queue/navigation backends unavailable. Stage has a different CPU/cgroup/heap context; no universal live speedup is claimed.
- Same copied prelit Map9 and Scene, same catalogue, same addon; original fixture checksum unchanged after every run. Full scene retains 619 displays. Published test platform contains 15 unique models, 471 displays and all original 765 authored cubes.
- Warmups: first 45 s, later 20 s; settled counter deltas approximately 30 s, motion 25 s, reentry 20 s. JFR starts only after all throughput windows, 60 s at 2 ms, owned JVM, explicit safe event whitelist and audited schema 2. Both recordings report zero lost bytes. Baseline diagnostic JFR overlaps candidate verification work on the host, so do not use JFR sample counts as quantitative A/B CPU evidence. Native waiting samples are not CPU use; raw allocation weights include prehistory and are not allocation rates.
- Candidate A aborted before its measured 60-client windows because the controller interpreted command dispatch acknowledgment as server-side completion. Its first warmup-60 sample saw 7 near/7 mid/6 far and 40 still outside. Owned cleanup succeeded, exit code 0, original fixture unchanged; no product crash/disconnect. Candidate B adds a bounded server-position confirmation before warmup and otherwise uses the same workload and frozen jar. Baseline A already passed all required sampled zone checks. All A raw results remain available; this is not two complete A/B pairs.
- Supplementary incomplete A observations: 1-client mean/p99 1.249/4.610 ms, CPU .0579, allocation 16.09 MB/s; 20-client mean/p99 1.532/5.821 ms, CPU .0922, allocation 19.82 MB/s. They also show increased packet/allocation counts, and are not substituted for the complete B results.
- Independent correctness review found one rewind-within-skipped-interval issue. A new regression failed before the fix, then the complete suite passed. No further actionable review findings; reviewer did not assess benchmark/GUI/Stage readiness.
- 182 licensed host tests, 0 failures/errors/skips. Ordinary published-dependency run: 182 tests, 0 failures/errors, 5 expected licensed-asset skips. Formatting passes; 983 addon files pass production loader acceptance. Fixed-time comparison of 723,456 metadata/pose records remains bit-identical (SHA-256 `27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e`). This does not assert unchanged regular sample timestamps.
- Frozen host class comparison changes only AnimatedNpcHandle, its file facade, LobbyNpcModelRenderers and added NpcAnimationClock. Native SOLID, interpolation durations, personal visual path, common metadata cache, 64/72-block view ranges and saved lighting are unchanged in source. No new visual acceptance is claimed.

## Frozen artifacts and local evidence

Baseline jar: `/home/lukas/grounds/artifacts/npc-shared-metadata-2026-11-01/candidate-refined.jar`, SHA-256 `e447d40b57476606dd9df3fc7223b71997c2c03ec4f4d878252f45e84b44743b`.

Final candidate: `/home/lukas/grounds/artifacts/npc-animation-phases-2026-11-02/candidate-refined.jar`, SHA-256 `084388a2a8619adef07220a432584babcd76c3f842603eb15ad6744cd1b82297`. Initial pre-review jar `candidate.jar` / `809b6737…` remains preserved and was not used for candidate A or B.

Report/summary/controller/test evidence: `/home/lukas/grounds/artifacts/npc-animation-phases-2026-11-02/`. Raw baseline A, incomplete candidate A and complete candidate B: `/home/lukas/grounds/artifacts/npc-load-profile-2026-11-01/animation-phases-{baseline-a,candidate-a,candidate-b}/`. Frozen Scene SHA-256 `662a203e9d56b64a51075728d885b8877ba9f1220a25cbbf0ab55b72ef65c23e`; matched harness source SHA-256 `e72378596306e8aeb51ea67f4c024aa1e1de2faa65f7984784d362057c701ec5`.

## Backlog decision

N21's stable-phase scheduling variant stays draft. Its separate initial-entity construction/spawn-budget aspect remains open; no cold-start gain is claimed. Do not activate #233 or #236 together with this experiment. The next useful direction is N13's actual per-viewer traffic reduction with the now accepted shared serialization path; its former implementation must still overcome its measured CPU overhead. Do not compensate for this result by lowering NPC view distance or discarding geometry.

Owned test listeners 25581/19081/18081–18083 are closed and no owned client containers remain. A fresh read-only Stage verification could not connect to the game cluster API (two TCP timeouts); the owned read process was stopped. No deployment mutation was attempted, so this task made no Stage change, but it does not claim a fresh live health verification.
