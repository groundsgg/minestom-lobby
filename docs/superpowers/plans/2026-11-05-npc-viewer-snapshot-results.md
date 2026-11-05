# NPC viewer snapshot qualification

Replace only the per-display toList() with Minestom's eager stable snapshot iterator, captured before updating the viewable rule. The pinned dependency resolves all player IDs in size() and already snapshots them in iterator(). Keep independent membership, rate cadence, interpolation, geometry and collision.

Validation:204baseline tests;206candidate/check/format tests;206tests against the exact Stage-derived JAR, all without skips. The real renderer's old12-viewer/two-display transition resolves72player IDs and fails a48-resolution budget; the candidate passes. A separately missing body part receives exactly one explicit full pose during a rate transition. Fresh independent strongest-model review found no issues and independently checked the exact Minestom bytecode. Recheck eager snapshot behavior on dependency upgrades.

Candidate SHA256 `4f9e21da852a2924f69a04b28c5cdf9fe2bbc2856f407be6d3ed0169d5649339` changes only AnimatedNpcHandle.class in accepted dba22bf8 Stage JAR.40422physical entries and40380JDK-resolved names checked; every other entry, ordering and duplicate resolution preserved.

## Connected comparison

Four complete runs, baseline/candidate/candidate/baseline, each with20/60actual offline protocol777clients, fixed prelitMap9,451settledScene displays,348platform displays, motion, horizontal visibility boundaries and two full reentries. One active processor pinnedCPU23/512MiBheap, own clients/controller/proxy exclude22/23; topology confirms sibling22. No profiling or unrelated service changes. Shared hardware is not reserved. All clients/counts/Scene/map checks pass, no disconnects, clean JVM exit and own resource cleanup in every run.

Percentages below compare arithmetic means of two runs per variant; two pairs do not establish statistical significance. JVM allocationMB/s is temporary memory, TCP payloadMB/s is separate.

- steady-20: CPU-2.57%, tick mean+8.27%, p99+37.15%, JVM allocation-1.48%, TCP payload-1.85%.
- moving-20: CPU-6.16%, tick mean-4.52%, p99-6.28%, JVM allocation-1.75%, TCP payload-1.72%.
- boundary-20: CPU-11.30%, tick mean-1.96%, p99-7.95%, JVM allocation+1.03%, TCP payload-0.66%.
- reentry-20-0: CPU-11.26%, tick mean-7.01%, p99-37.56%, JVM allocation-1.22%, TCP payload-1.37%.
- reentry-20-1: CPU-5.54%, tick mean-0.01%, p99+2.45%, JVM allocation-1.25%, TCP payload-2.34%.
- steady-60: CPU-1.59%, tick mean+4.90%, p99+10.47%, JVM allocation-1.12%, TCP payload-1.86%.
- moving-60: CPU-2.61%, tick mean+2.60%, p99+15.83%, JVM allocation-1.20%, TCP payload-0.62%.
- boundary-60: CPU-1.08%, tick mean+3.48%, p99+19.86%, JVM allocation+3.62%, TCP payload+2.05%.
- reentry-60-0: CPU+1.08%, tick mean+4.15%, p99+4.81%, JVM allocation-0.19%, TCP payload-1.27%.
- reentry-60-1: CPU-0.74%, tick mean+1.40%, p99+1.45%, JVM allocation-0.51%, TCP payload-1.92%.

## Adverse windows and decision

60moving: CPU−2.61% but tick mean1.515→1.554ms and p995.751→6.661ms; both individual p99 pairs are worse(+25.5/+7.6%).20standing tick mean+8.27%/p99+37.15%;60standing tick mean+4.90%.60boundary allocation+3.62%/TCP+2.05%, with one pair+8.98/+6.07%; CPU only−1.08% and p99+19.86%. The first60reentry CPU is+1.08%. No favorable window replaces any adverse one. Allocation/TCP aggregate reductions in other phases do not recur in both pairs, so no demonstrated general memory/network improvement is claimed.

CPU is lower in9of10 aggregate windows; the20boundary and first reentry reductions recur in both pairs, as does60movement's modest CPU reduction. This supports only a bounded reversible Stage trial of the deterministic resolution/copy reduction. It does not establish generally faster ticks/TPS, lower tail latency, cold-start gains, rendered FPS, Bedrock or cross-proxy performance. Native Java viewer-entry acceptance remains required before Source integration.

Initial20-client join/activation maxima: baseline42.759/41.262ms, candidate43.761/36.017ms. Before first client admission: baseline118.788/138.903ms, candidate127.637/132.601ms. No cold-start improvement inferred. Boundary Scene counts intentionally change; all complete adverse windows retained. Raw counters, exact tick ordinals, map hashes and comparisons are retained under artifacts/npc-viewer-snapshot-2026-11-05.
