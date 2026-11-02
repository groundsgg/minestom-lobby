# NPC transition metadata: complete measured results

## Decision

[Host #241](https://github.com/groundsgg/minestom-lobby/pull/241) stays **draft, unmerged and unactivated**. Typed flags remove a confirmed full-map construction site and grouped transitions reuse serialized snapshots correctly, but a broad CPU benefit is not established. Do not select only the favorable moving window. No private image is published for either new candidate. The previously qualified #239/#240 image remains the separate Stage rollout through [deploy #555](https://github.com/groundsgg/deploy/pull/555), now that existing Tailscale management access is restored.

## Method and correctness

One fresh baseline (#240 jar92154af1) was measured against initial A and then tuned B. The baseline is reused: these are not two independent pairs and B selection has tuning/post-selection bias. Each fresh isolated JVM follows the same1→20→60 actual protocol777 headless-client sequence, first/second motion under a separate90s2ms audited JFR,20ssettle, warmed unprofiled motion and3exit/reentries. CPU affinity0,512MiBheap, identical copied prelitMap9, byte-identical recorder/harness, strict ten-event whitelist and off-server-core loopback byte counter. No live attach, payload, environment, arguments or properties capture. Profiles are not throughput windows. Load rises in one JVM; later windows have more JIT warmup. Single observed controls are not guarantees or statistical significance.

195licensed tests pass without skips; ordinary195pass with5expected licensed-asset skips. The actual-transition sharing regression failed on the original individual packet path, then passes with different durations, personal scale/glow, unchanged recipients and immutable queued states. Additional wire-level flag tests preserve current on-fire/invisibility bits under glow overlays. Formatting,723456fixed pose/metadata states (SHA27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e) and983-file pinned addon acceptance pass. Two read-only correctness reviews found no actionable issue. Scene/Resourcepacks classes are unchanged; facing initializer differs only in debug info. Native SOLID, geometry, visibility, rates, deadlines and initial spawn order are preserved.

Both complete candidate runs and the baseline retain619displays,0disconnects/proxy errors/JFR loss and original map hash. No GUI/FPS, Bedrock or cross-proxy integration result is claimed.

## Initial candidate A (retained adverse result)

A built filtered transition lists and groupBy maps even when no existing viewer changed rate. Despite removing allocation at the full metadata lookup site, CPU/mean worsened throughout. All percentage changes below compare against the same baseline, with negative meaning lower.

| Scenario | Mean % | p99 % | CPU % | Allocation % | TCP % |
|---|---:|---:|---:|---:|---:|
| 1 nah | +9.25 | +2.84 | +13.92 | +2.26 | +3.47 |
| 20 gemischt | +16.04 | +34.09 | +7.32 | -1.14 | -1.37 |
| 60 gemischt | +6.95 | +21.89 | +3.52 | -2.26 | -3.02 |
| 60 bewegt | +4.19 | +0.03 | +3.77 | -1.77 | -1.80 |
| Wiedereintritt 1 | +4.53 | +3.50 | +5.17 | -1.56 | -3.93 |
| Wiedereintritt 2 | +11.50 | +16.88 | +6.48 | -2.07 | -5.02 |
| Wiedereintritt 3 | +15.72 | +47.70 | +8.60 | -2.16 | -5.81 |

## Tuned B, complete raw comparison

B lazily creates at-most-three rate buckets only after finding a transition and sends singleton groups through the original direct path after membership recheck. Bytecode has no captured ObjectRef. This removes the avoidable empty-list/group work, but the overall result remains mixed.

Each cell is baseline→B. CPU is process CPU-seconds per wall-second; allocation is whole-JVM MB/s; network is actual Minecraft TCP payload MB/s, excluding TCP/IP headers, external proxies/TLS and client dashboard traffic. Phasenmax includes the complete transition window, beyond the settled sample-counter endpoints.

| Scenario | Mean ms | p99 ms | CPU cores | Allocation MB/s | TCP MB/s | Full phase max ms |
|---|---:|---:|---:|---:|---:|---:|
| 1 nah | 0.754→0.860 | 3.106→3.164 | 0.050→0.059 | 15.471→15.976 | 0.076→0.080 | 7.758→7.389 |
| 20 gemischt | 1.010→1.145 | 3.941→5.052 | 0.064→0.066 | 20.446→20.691 | 1.085→1.096 | 6.603→9.058 |
| 60 gemischt | 1.479→1.464 | 3.778→3.321 | 0.093→0.093 | 26.853→26.863 | 3.398→3.387 | 4.721→7.342 |
| 60 bewegt | 1.931→1.811 | 6.823→5.889 | 0.116→0.113 | 37.534→37.247 | 4.585→4.579 | 11.739→8.240 |
| Wiedereintritt 1 | 1.633→1.610 | 4.827→4.879 | 0.096→0.098 | 37.632→37.661 | 4.973→5.002 | 12.292→12.218 |
| Wiedereintritt 2 | 1.559→1.631 | 3.837→4.037 | 0.093→0.097 | 37.507→37.461 | 4.950→4.913 | 11.390→12.961 |
| Wiedereintritt 3 | 1.571→1.595 | 3.369→4.277 | 0.093→0.102 | 37.470→37.445 | 4.930→4.906 | 14.872→13.024 |

At60moving clients, mean-6.21%,CPU-2.12%,allocation-0.76%; at60steady, CPU essentially flat and allocation+0.04%. At1/20clients mean worsens14.16%/13.28%, CPU16.52%/4.19%. Some reentry p99/CPU values worsen substantially. Network changes do not establish traffic savings: no rate or field-format change is intended. Peak RSS324083712→333688832bytes is a finite-process maximum, not a leak test.

## Allocation and first-motion diagnostic

Numeric/method-name-only analysis of the owned audited recordings, after the first recording second: full-metadata construction within the snapshot/flags path has36sampled events/36250440weighted bytes in the baseline,0inAandB. Snapshot-path sampled pressure is67events/78265104weighted bytes baseline,36/46949664Aand29/33619272B. These overlapping sampled weights are not exact allocation rates or total heap savings. Initial spawn may still legitimately collect full metadata.

First diagnostic motion peak:36.462msbaseline,45.404msA,36.913msB; second:9.848ms,9.143ms,8.686ms. Neither baseline nor B's largest first-start tick overlaps a recorded GC pause within the millisecond estimated work bounds; B has no Java tick sample inside that peak. The original unprofiled56ms event remains unresolved. Numeric packet deltas span monitor intervals including async network writes and do not prove a sole serialization cause. Unrecorded safepoints, scheduling and JIT remain possible.

## Artifacts and execution notes

Root: `/home/lukas/grounds/artifacts/npc-transition-metadata-2026-11-02`. Raw runs under `npc-load-profile-2026-11-01/transition-metadata-{baseline,candidate}-a` and `transition-metadata-candidate-b`; all summaries, CSVs, audited JFR, fixed jars, class diffs and test XML retained. Initial A SHA2fce737357ac9f5adf87810d7c12933023dae84bb075c5d281deeee863d705f2; tunedB SHA1566d640794b4c695cd7bd630f1139815030b63c37f4fff165215c2a68f3893f; flags-only intermediate11a252b8df1dd428129027072528f6b9f471a0e04ec024733d4077c90bb721ba.

The first controller import failed before creating children; copied the existing proxy module. The addon gate correctly rejected an accidentally selected older fixture; the pinned isolated addon then passed without weakening validation. The motion analyzer retained its ownership/window bounds and was scoped to the new owned run names; analysis waits for the finished CSV. No measured run or frozen input was overwritten.

A silent pre-results CI test stall also occurred previously on#240; cancelled attempt logs retained, no logged assertion failure and no root-cause claim. Test-only2minute Gradle task timeout and started/failed/skipped events now bound and identify future stalls. Corrected a Kotlin DSL java-extension package-name shadow with an explicit Duration import; ordinary suite verifies it. Code head69c6271passes all CI checks. No test diagnostics ship in the runtime jar.

Next: profile redundant refreshViewers scans/allocation before changing their cadence. Preserve immediate spawn, visual-state and identity/lifecycle guarantees; no admission throttle is implemented by this report.
