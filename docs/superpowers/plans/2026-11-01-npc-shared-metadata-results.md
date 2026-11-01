# NPC shared metadata serialization: measured acceptance

The accepted implementation uses Minestom native grouped sending for at least two unchanged common recipients. Personalized scale/flags retain independent packets; one common recipient keeps the ordinary path. Geometry, animation cadence, interpolation, 64/72 visibility, native SOLID and prelit Map9 are unchanged. No mathematics or delivery-cohort code from drafts #236/#233 is included.

## Correctness and frozen inputs

- 175 licensed Host tests pass with 0 failures/skips; the ordinary published-dependency suite passes 175 tests with 5 expected licensed-asset skips.
- The 5 focused tests also pass in a fresh worker with automatic translation enabled. The default is disabled in this pinned Minestom; both policies retain their native behavior.
- Shared encoded body, hand-derived personal scale/glow, delayed old-frame ownership, translation policy and single-common/personal-only audiences are covered. The new single-common test was observed RED before the cardinality fix.
- 983 addon files pass acceptance. All 15 models produce 723,456 bit-identical pose/dirty-state records against acceptedStagec3e662a9, SHA256`27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e`.
- Fresh independent review found no correctness blockers in common grouping, personalized fields, frame/array ownership, native translation/caching, outgoing per-player events/cancellation, encryption and login compression lifecycle. The cardinality refinement is additionally covered by the new regression and full suite.
- Final private jar SHA256`e447d40b57476606dd9df3fc7223b71997c2c03ec4f4d878252f45e84b44743b`. Baseline `c3e662a91ac383f0f8698b937b08d7fe3178d8f6cfea18e73064f6c1ac67ee08`.

## Method and limits

A fresh isolated 1 CPU-affinity / 512 MiB JVM uses the actual copied prelit Map9 and production scene/runtime, local stubbed actions and loopback-only offline clients. The platform has 15 models / 471 displays / 765 cubes; the complete scene has 619 item displays. Load rises 1→20→60 (actual spawned protocol 777 clients, at most 20 per worker), with stable near/mid/far zone/count checks.1 client is near; 20/60 are mixed. Reentry brings all 60 near. Movement uses bounded server-owned trajectories with client confirmations, not GUI physics walking or FPS.

45 s initial and 20 s later warmup precede 30 s measured deltas; movement has 25 s measured delta and each reentry 20 s. Whole-phase maxima include transition commands and viewer work. A separate 60 s / 2 ms strict JFR follows throughput. Absolute allocation rates use ThreadMXBean counters, not raw JFR weights. Original map immutability is checked. Tests are local workload observations, not measured live Stage gains.

Initial candidate b3a9e34e completed a full pair. It saved 23.5% allocation / 12.5% CPU at 60 mixed clients, but increased 1 client allocation 15.87→17.09 MB/s. The final cardinality refinement removes that unnecessary single-recipient cache. A new full candidate schedule was compared with the same frozen original control; this reuses the baseline and is not a second independent A/B pair. JVM warmup, animation phase and shared-host scheduling limit small CPU/tail percentage claims. Raw artifacts preserve both candidates and every unfavorable row.

## Final candidate measurements

MB/s below is newly allocated JVM memory, **not network throughput**. CPU is process cores, where1.0means one fully used core.

| Load | Mean tick old→new(ms) | CPU old→new(core) | Allocation old→new(MB/s) | p99 old→new(ms) | Whole-phase maximum old→new(ms) |
| --- | --- | --- | --- | --- | --- |
|1near|0.882→0.889|0.0599→0.0603|15.87→16.11|2.883→2.813|6.92→6.02|
|20mixed|1.047→1.021|0.0822→0.0812|22.13→19.80|3.342→3.855|6.87→7.53|
|60mixed|1.550→1.629|0.1471→0.1408|35.63→27.02|3.663→4.762|9.34→8.14|
|60moving|1.977→2.144|0.1801→0.1773|43.30→34.70|6.763→7.912|29.17→31.89|
|60reentry1|1.707→1.594|0.1517→0.1377|46.46→37.59|5.672→4.070|12.27→12.68|
|60reentry2|1.514→1.690|0.1443→0.1408|46.55→37.48|3.560→4.430|18.29→13.15|
|60reentry3|1.537→2.806|0.1507→0.1472|46.88→37.16|4.158→9.442|11.30→12.90|

At 60 mixed clients the final candidate allocates 24.2% less memory and uses 4.3% less process CPU. At 20 clients allocation falls 10.5% and CPU 1.2%; at 60 moving clients allocation falls 19.9% and CPU 1.6%. The 1 client case is approximately neutral (CPU+0.6%, allocation+1.5%), improving the initial candidate's +7.7% allocation cost. All measured cases remain approximately 20 TPS with 0 disconnects. Both recordings have 0 DataLoss. RSS peaks 310.1→307.9 MB; this is not a retained-heap/leak test.

There is **no uniform tick-time/tail gain**: final 60 mixed mean rises 1.550→1.629 ms and moving mean 1.977→2.144 ms. Reentry 3 mean rises 1.537→2.806 ms. In that latter window CPU0 occupancy is 32.6%→56.7%, while owned-process CPU is 0.1507→0.1472 core and tick-task run-queue delay 1.72→2.47 ms/tick. The additional core activity is outside the measured JVM process; this is evidence of interference/scheduling variability, not proof of the exact cause of this or the earlier #236 outlier. Tick-thread CPU/run-queue counters include between-tick pacing and must not be added as a decomposition of TickMonitor wall time. Do not claim a reentry-tail fix.

Metadata dispatch counts differ only about −0.05% / −0.13% / −0.57% at 1/20/60 clients. Packet contents and recipients/cadence are preserved. Counts aggregate all viewers; they are not actual per-client wire-byte measurements. This change makes repeated serialization cheaper and does not demonstrate reduced RX or network MB/s.

The first separate profile supports the source-level mechanism: boundary-sensitive sampled KeyValueHolder pressure falls 429.7→7.9 MB and MapN-iterator pressure 140.5→3.8 MB over approximately 59 s. These are approximate sampled pressures, not exact allocation-rate counters. Recorded top-level GC pause totals 106.6→82.1 ms (max 2.77→2.35 ms) belong to that diagnostic pair, not to the unprofiled throughput windows. Native blocking samples are not CPU time, and inclusive stack counts overlap.

## Decision and rollout

Accept this narrow serialization change for its repeated allocation reduction, lower 60-client process CPU, exact geometry/data checks and intact operational behavior. Do not advertise it as a blanket mean-tick/TPS improvement. The 1 client cache cost is addressed before activation. Prioritize actual visibility/spawn bursts and further packet-policy work separately; keep #233/#236 drafts separate.

Stage activation is complete via [deploy#554](https://github.com/groundsgg/deploy/pull/554), merge `a4c54b3599c2b0bc1988a44b723b1431aed4b444`. Both actual lobby containers were ready with 0 restarts at 2026-11-02T00:12:57.028091+01:00 on `ghcr.io/groundsgg/npc-stage-poc:lobby-2.15.0-shared-meta-e447d40b5747@sha256:c6d3952478658bfbaa945757e2b50667a2778e78fd02ff6a81b46a968ac7bf30`. Runtime logs confirm prelit Map9 (`0834f795...`), catalogue `0.0.0-edge.1793492850.ge7a80f9731a7`, unchanged addon/source pins, 557 prepared chunks and 6 native SOLID cubes per lobby. Source PR #237 carries this implementation and report. No global pack or Production release was performed. Private raw evidence: `artifacts/npc-load-profile-2026-11-01/shared-metadata-*` and `artifacts/npc-shared-metadata-2026-11-01`.

The final refined candidate recording also has 0 DataLoss; top-level GC pauses total 90.4 ms, maximum 3.39 ms. This is a diagnostic window, not an unprofiled CPU-rate comparison.

All owned test client containers were removed, the owned JVM runs exited0, and ports25581/19081/18081–18083 are closed. A new optional human Stage animation/scale/SOLID smoke test is requested; it is not claimed as completed.
