# N13 cohort delivery — Stage-trial candidate

Date: 2 November 2026. Source [PR239](https://github.com/groundsgg/minestom-lobby/pull/239), accepted base `83e1fba` (#237). **Decision: qualified for a private Stage trial as a traffic optimization, with the measured costs below. No uniform CPU/tick improvement or Production certification is claimed. Stage activation is pending API connectivity and a subsequent live/client check.**

## Outcome

Final candidate B reduces actual server→client Minecraft TCP payload by **24.1% at20 mixed clients,26.4% at60 mixed,21.0% in60-client motion**. At 60 mixed this is 4.654→3.425 MB/s, saving 1.229 MB/s; motion 5.786→4.570 MB/s, saving 1.216 MB/s. These are measured network payload bytes, not JVM allocation or estimates from packet counts.

60 mixed processCPU falls 2.7%, mean tick work 3.1%, p99 10.9%. Motion mean is effectively flat and CPU rises 1.4%, with p99 lower 10.7%. Settled all-near reentry means range -4.5% to+1.9%, CPU +1.0–1.6%. This is not a general CPU optimization. The 20-client mean/p99 regress 1.061→1.199 ms /4.615→5.405 ms, despite flat processCPU. Allocation increases 2.8% at60 mixed,10.9% in motion, and roughly3% after reentry. Peak resident memory 308.7→328.2 MB in these finite runs; no leak/steady-heap claim.

**Adverse transition retained:** full moving-phase maximum 33.625→56.210 ms, including at least one candidate tick beyond 50 ms. Its settled measurement-window maximum is33.625→13.345 ms; the larger candidate tick occurs before that settled counter window. Reentry3 whole-phase maximum 18.708→27.647 ms also worsens. These are not removed as outliers or attributed entirely to external load. The first complete candidate A had motion maximum approximately 33.27 ms, so the56 ms observation is not established as a deterministic direct-path regression. Identifying its exact cause requires separate event-aligned profiling; no causal claim is made. Stage trial is justified by repeated mixed-audience byte reduction and near-flat steady CPU, while retaining these transition/memory costs as explicit limitations. Live visual/transition acceptance remains required before treating this as a completed rollout.

## Complete final table

Old accepted jar → final B. MB/s is decimal10^6bytes/s. CPU is processCPU cores. Metadata/s sums server dispatches across all clients; it differs from total received packets and measured TCP bytes. p99 uses raw matched per-tick wall times.

| Phase | Mean ms | p99 ms | CPU cores | JVM allocation MB/s | TCP server→client MB/s | Metadata/s |
| --- | --- | --- | --- | --- | --- | --- |
| steady-1 | 0.815 → 0.863 | 3.294 → 2.659 | 0.0569 → 0.0556 | 15.64 → 16.10 | 0.0768 → 0.0804 | 1700 → 1779 |
| steady-20 | 1.061 → 1.199 | 4.615 → 5.405 | 0.0666 → 0.0666 | 19.12 → 20.64 | 1.4584 → 1.1066 | 31725 → 23884 |
| steady-60 | 1.600 → 1.550 | 4.372 → 3.895 | 0.0991 → 0.0965 | 26.50 → 27.24 | 4.6543 → 3.4250 | 97990 → 70717 |
| moving-60 | 2.135 → 2.134 | 9.180 → 8.196 | 0.1382 → 0.1401 | 34.24 → 37.98 | 5.7861 → 4.5698 | 96622 → 70879 |
| reentry-0 | 1.640 → 1.630 | 4.455 → 4.165 | 0.0993 → 0.1003 | 36.69 → 37.90 | 4.7580 → 4.9545 | 100603 → 104896 |
| reentry-1 | 1.607 → 1.638 | 4.416 → 4.165 | 0.0963 → 0.0979 | 36.65 → 37.79 | 4.7309 → 4.9181 | 99855 → 104133 |
| reentry-2 | 1.558 → 1.488 | 3.782 → 3.319 | 0.0983 → 0.0993 | 36.99 → 38.09 | 4.8383 → 5.0244 | 102351 → 106496 |

Single/near-only audiences do not gain network savings:1 near +4.6%bytes, and all-near reentries +3.8–4.1%. Actual cadence/tick rounding, retained rate bands and sampled animation windows affect counts. Do not extrapolate mixed-cohort savings to every player distribution. All complete windows sustain approximately 20 TPS with zero disconnects.

## First complete candidate A and refinement

A used native cached fanout, enum membership, grouped recipients and immutable cohort accumulation, but queued even when only one cohort existed. B adds direct cached delivery only for an empty single-cohort accumulator; preparing snapshots, any pending delta and renders before an existing delivery deadline still defer. This avoids the extra scheduling layer for homogeneous audiences without dropping one-time fields or advancing existing viewers early.

All first-run results remain available; B reuses the original baseline A. These are two complete candidates against one control, **not two independent A/B pairs**.

| Initial A phase | Mean ms | CPU cores | Allocation MB/s | TCP MB/s |
| --- | --- | --- | --- | --- |
| steady-1 | 0.815 → 0.860 | 0.0569 → 0.0566 | 15.64 → 15.70 | 0.0768 → 0.0784 |
| steady-20 | 1.061 → 1.206 | 0.0666 → 0.0689 | 19.12 → 20.92 | 1.4584 → 1.1076 |
| steady-60 | 1.600 → 1.690 | 0.0991 → 0.0958 | 26.50 → 27.32 | 4.6543 → 3.3873 |
| moving-60 | 2.135 → 2.396 | 0.1382 → 0.1366 | 34.24 → 38.13 | 5.7861 → 4.5709 |
| reentry-0 | 1.640 → 1.856 | 0.0993 → 0.1038 | 36.69 → 37.60 | 4.7580 → 4.9240 |
| reentry-1 | 1.607 → 1.825 | 0.0963 → 0.1018 | 36.65 → 37.46 | 4.7309 → 4.9003 |
| reentry-2 | 1.558 → 1.811 | 0.0983 → 0.1003 | 36.99 → 37.62 | 4.8383 → 4.9520 |

## Correctness and isolation

- 193 licensed host tests pass with0 failures/errors/skips; ordinary published-dependency193 tests pass with5 expected licensed-asset skips. Formatting and983-file production bundle acceptance pass. Fixed-time723,456 pose/metadata records are bit-identical (SHA256`27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e`). This is not unchanged per-viewer delivery timing: distance-based delivery is the feature.
- Existing 12 cohort regressions were run against main first:7 failed for missing behavior, then passed after implementation. Additional tests cover native cached sharing within groups, actual display-viewer membership, slow one-time fields, personal scale, stop, reentry, rate bands, queued ownership and seek inside a skipped interval.
- Independent review found one concrete same-UUID reconnect bug. The new regression failed with0 updates instead of 2; rebuilding cached recipients on Player identity changes fixes it. Full suites pass. A subsequent review of the new homogeneous fast path found no actionable findings. Reviews were static and do not assert GUI/performance/Stage readiness.
- The direct-delivery regression failed before the fast path (0 immediate metadata packets instead of 1). A second test caught an early 900 ms update after a prepared 500 ms flush; deadline-aware capture fixes it. Full193-test suites pass. Initial unspawned-viewer test setup incorrectly used removeViewer on an automatic viewer; corrected to updateViewableRule, with failed logs preserved.
- Class digest comparison proves Scene and Resourcepacks classes unchanged against accepted Stage. Only AnimatedNpcHandle, its file facade, NpcItemDisplay and NpcRenderPolicy change, plus new NpcPendingMetadata/Delta and NpcViewerRate. No phase clock#238, prepared math#236, #233runtime instrumentation, geometry/culling change, or collision code is imported. NativeSOLID, full model geometry,64/72 block admission/retention and saved lighting remain in the accepted paths.

## Measurement method and limits

- One owned fresh JVM per complete run, same pinned prelit Map9/Scene/catalogue/addon.193 tests and bundle checks use the exact asset catalogue`0.0.0-edge.1793492850.ge7a80f9731a7`. Full scene 619 displays; test platform 15 unique models / 471 displays / 765 authored cubes. Every sampled audience/zone/display count is checked, including command server-side completion; no disconnect/profile/proxy error in measured windows. Source map checksum unchanged after every run.
- Real protocol777 headless Java clients grow1→20→60 in each JVM;1near,20/60 mixed, deterministic server-owned motion confirmed by clients, three all-near reentries. This is not GUI/FPS/Bedrock/walking-physics or a cross-proxy identity/routing test. External navigation/queue backends are unavailable local stubs; no user account or live players are involved.
- Backend pinned to CPU0 / 1active processor / 512 MiB heap; host CPU/scheduler/JIT/GC counters recorded. Stage has a different cgroup/heap context. Warmup 45 s for first load and 20 s later; measured deltas 30 s steady / 25 s motion / 20 s reentry. Host contention varies; inclusive tickCPU counters include pacing and are not a decomposition of TickMonitor wall time.
- Both variants route Minecraft through the same loopback-only Python proxy (64 active-connection cap, 65536-byte reads, buffer limit 131072). It records byte lengths/counters only, never payloads; proxy thread affinity excludes backend CPU0. Exact 150000-byte bidirectional echo test passes, and reviewer found no obvious proxy counter/safety issue. Counters measure forwarded TCP payload after Minecraft framing/compression, excluding TCP/IP headers, retransmissions, dashboard HTTP, production TLS/Velocity/Geyser effects and resourcepack downloads. Networkcounter boundary follows metric snapshot by a small controller delay; do not treat the results as NIC throughput or a live Internet bandwidth guarantee.
- JFR is a separate post-throughput 60 s / 2 ms startup-owned whitelist recording, audited schema 2, zero data loss in all complete recordings. Baseline diagnostic JFR overlaps candidate verification work on the host, so quantitative comparisons rely on throughput counter windows, not JFR sample counts. Native wait samples are not CPU time; allocation weights include prehistory and are not absolute allocation rates.

## Frozen evidence

Accepted baseline jar SHA256`e447d40b57476606dd9df3fc7223b71997c2c03ec4f4d878252f45e84b44743b` in `/home/lukas/grounds/artifacts/npc-shared-metadata-2026-11-01/candidate-refined.jar`.

Final B SHA256`2a058aeb42f56543a6c39f0386569d3966b2e2d4a23f524a5369052e63eb30ab` in `/home/lukas/grounds/artifacts/npc-cohort-dispatch-2026-11-02/candidate-fast.jar`. Complete A SHA256`cd1a319d8d89db13c191448ba776c21520f4ccc31cd44c51d0249a69df6b436b`. Initial pre-review9574e4f4jar remains preserved and was never measured as a candidate.

Reports/tests/controllers/proxy proofs: `/home/lukas/grounds/artifacts/npc-cohort-dispatch-2026-11-02/`. Raw complete runs: `/home/lukas/grounds/artifacts/npc-load-profile-2026-11-01/cohort-dispatch-{baseline-a,candidate-a,candidate-b}/`. Scene SHA256`662a203e9d56b64a51075728d885b8877ba9f1220a25cbbf0ab55b72ef65c23e`, harness SHA256`e72378596306e8aeb51ea67f4c024aa1e1de2faa65f7984784d362057c701ec5`.

## Stage handoff

Source is qualified for Stage evaluation as a traffic optimization. Prepare a digest-pinned private image and a draft image-only Stage deployment PR. The bounded read-only API probe still times out; do not merge GitOps or claim Stage activation/live health. Preserve all bindings, Scene/addon pins, saved lighting, SOLID and Production. The prior user acceptance of scale/SOLID belongs to the earlier #237 Stage image, not this candidate.
