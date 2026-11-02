# Viewer-rate allocation follow-up and motion-start diagnosis

Date: 2 November 2026. Source [PR #240](https://github.com/groundsgg/minestom-lobby/pull/240), base `ac9b33a` (#239).

## Decision and scope

Accept the small behavior-preserving refactor: compute the minimum interval with primitive arithmetic in the existing admitted-viewer scan. This removes a verified allocation site and a redundant traversal without changing rate values, visibility, hysteresis, identity invalidation, deadlines, pose generation, metadata or native SOLID. It is not a new traffic optimization or a demonstrated large whole-JVM memory reduction.

The old compiled minimum calls `Long.valueOf` for each admitted viewer then `Comparable.compareTo`; the new one calls `Math.min(JJ)` and creates no `LongRef`. In this fresh paired diagnostic recording, 40 sampled Long events (about 40.18 MB of approximate sample weight after the first-second sensitivity cutoff) point to the old refreshViewers selector; the candidate has no samples at that selector. Its only sampled Long site is unrelated network decoding. Weights are not exact allocation volumes/rates. Other deadline/variation Long boxes remain.

Whole-JVM allocation is almost unchanged:60 mixed27.103→27.063 MB/s(-0.15%), warmed motion 37.949→37.784(-0.43%);20 mixed rises0.62%. Do not advertise the sampled site reduction as an equivalent heap/RAM saving. Observed process CPU is essentially flat at 60 mixed and 5.0% lower in warmed motion; mean tick work falls 3.9% and 11.5% respectively. A single matched pair does not guarantee these timing gains. Network volume varies +0.1–4.8% across windows; no packet/rate reduction is claimed or added to #239's separately measured savings.

## Complete unprofiled comparison

Old #239 → new primitive selector. CPU is process cores; allocation and forwarded TCP payload are decimal MB/s. Moving/reentry windows occur after a separate motion recording and20s settle in both runs, so they have warmer movement/JFR classes than earlier #239 tests. Compare this pair only, not against older windows as if setup were identical.

| Phase | Mean ms | p99 ms | CPU cores | Allocation MB/s | TCP server→client MB/s | Whole-phase max ms |
| --- | --- | --- | --- | --- | --- | --- |
| steady-1 | 0.806 → 0.782 | 3.334 → 3.238 | 0.0583 → 0.0556 | 16.008 → 16.026 | 0.0801 → 0.0802 | 6.403 → 7.461 |
| steady-20 | 1.167 → 0.992 | 3.555 → 3.900 | 0.0683 → 0.0656 | 20.631 → 20.758 | 1.0821 → 1.1072 | 7.259 → 6.884 |
| steady-60 | 1.535 → 1.475 | 4.198 → 3.840 | 0.0935 → 0.0935 | 27.103 → 27.063 | 3.3376 → 3.4112 | 6.243 → 9.564 |
| moving-60 | 1.999 → 1.768 | 6.299 → 5.230 | 0.1198 → 0.1138 | 37.949 → 37.784 | 4.5255 → 4.5957 | 7.629 → 8.657 |
| reentry-0 | 1.637 → 1.597 | 4.294 → 4.340 | 0.0963 → 0.0948 | 37.780 → 37.717 | 4.8325 → 4.9819 | 25.444 → 14.470 |
| reentry-1 | 1.509 → 1.442 | 3.503 → 3.680 | 0.0944 → 0.0918 | 37.493 → 37.594 | 4.6919 → 4.9179 | 16.244 → 13.482 |
| reentry-2 | 1.628 → 1.519 | 4.178 → 3.480 | 0.0958 → 0.0913 | 37.792 → 37.491 | 4.7865 → 4.8968 | 10.992 → 11.846 |

Adverse values remain visible:20client p99+9.7%,60 mixed maximum6.243→9.564 ms, warmed motion maximum7.629→8.657 ms, and some reentry p99/maxima worsen despite improved mean. Approximately20TPS and zero disconnects throughout. These are finite observations, not a memory-leak test or universal live CPU/TPS claim.

## Motion-start outlier: narrowed down, not declared fixed

The earlier unprofiled 56.210 ms tick ended 281 ms after the move marker, before the first counter snapshot at 1011 ms. Its later JFR could not explain it. The new harness waits for recording-active readiness, captures 90 s with the same strict ten-event whitelist, then starts the first motion with 60 actual clients; a second motion follows after a pause. Unprofiled throughput is recorded separately afterwards.

| Diagnostic phase | Old maximum ms | New maximum ms | Old full-pose-shaped packets | New full-pose-shaped packets |
| --- | --- | --- | --- | --- |
| diagnostic-first-motion-60 | 51.431 | 37.543 | 17704 | 17740 |
| diagnostic-second-motion-60 | 7.456 | 9.101 | 16508 | 16508 |

The new first-start peaks occur at +164 ms (old 51.431 ms) and +204 ms (new 37.543 ms). Neither estimated tick-work interval overlaps a recorded GCPhasePause. Maximum recorded GC pauses across the whole diagnostic are 4.995 ms and 3.778 ms. Thus the captured large tick is not explained by a recorded GC pause alone; this does not retrospectively prove the cause of the earlier 56 ms tick.

Old peak: 3 Java tick-thread samples, 2 in refreshViewers and 1 in TriggerEngine.accept. The server scheduler also records 45.940 ms parked in CountDownLatch.await → ThreadDispatcherImpl.updateAndAwait. New peak: 37.189 ms parked in the same scheduler wait, but no Java tick-worker execution sample inside the estimated interval. That barrier is normal waiting for instance work, not itself a diagnosed bug; absence of samples is not proof of idle CPU. Sampling gaps, scheduling, safepoints/JIT and other unrecorded work remain possible. The new maximum is lower in this pair, but the second-start maximum worsens 7.456→9.101ms, and the original outlier is not declared resolved.

Per-monitor packet deltas include asynchronous network writes between monitors, not only work inside the measured tick. The large peak's full-pose counter is 0; substantial full-pose-shaped counts occur in later monitor intervals. This supports examining the viewer transition path, but does not establish that network serialization alone caused the peak. A shape is recognized by exactly the seven known pose metadata fields, not payload contents.

## Concrete next candidate found during inspection

`NpcItemDisplay.sendPoseState` and sendVisualFlags read field 0 via `metadataPacket.entries()[0]`. Pinned Minestom bytecode shows getMetadataPacket creates a new packet and getEntries allocates arrays and copies the full metadata map, even though only flags are needed. Transition snapshots are also currently built/sent individually. A direct typed flags lookup and grouped transition snapshot fanout are separate, well-scoped follow-ups; neither is included in this refactor, and their gains/correctness require their own tests. This is a code-level cost finding, not a proven sole cause of the captured outlier.

## Validation and measurement limits

- 193 licensed tests pass without skips; ordinary 193 with 5 expected licensed-asset skips. Formatting passes; 723456 fixed-time pose records retain SHA256`27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e` ; 983 addon files pass production acceptance. No source-mirror unit test was added for the mechanical aggregation refactor. Existing real renderer tests cover all fixed rates, empty audiences, transitions, same-UUID reconnect and deadline behavior.
- Independent review found no actionable correctness/safety issue. Pinned Minestom registration rejects duplicate UUIDs before instance membership; admission, minimum selection, cache identity revision and downstream ordering agree under that invariant. Only AnimatedNpcHandle.class differs in the frozen jar; Scene, Resourcepacks and other Lobby classes are byte-identical. All 765 authored platform cubes, saved Map9 lighting and nativeSOLID paths remain intact.
- One fresh old/new pair with actual protocol 777 Java clients1→20→60, same copied prelit Map9/catalogue/addon and startup-owned harness; all sampled counts/zones / 619 displays/proxy counters checked, no disconnects or JFR loss, original fixture hashes unchanged. Backend CPU0 / 1 active processor / 512 MiB, loopback byte counter on other cores. No user accounts or external queue/navigation actions, no GUI/FPS/Bedrock or cross-proxy integration claim.
- Both variants use the same bounded numeric per-tick counters, same recording-active callback and existing recorder whitelist. No live JVM attach, payload/environment/argument/property capture or default JFC.90s diagnostic recording covers both motion starts;20s settle precedes unprofiled warmed motion/reentries. First load 45 s / later 20 s warmups ; 30 s steady / 25 s motion / 20 s reentry counter windows. Packet deltas are across monitor intervals; JFR samples/weights are not exact CPU/allocation rates, millisecond estimated work bounds have timestamp uncertainty. The profile-active flag includes dump/audit time; precise boundaries use capture-window.json.
- TCP counters measure forwarded Minecraft payload after framing/compression, excluding TCP/IP headers/retransmits/dashboard and production TLS/proxy/pack-download effects. Host scheduling/JIT/GC differ; do not add pacing-inclusive tick-thread counters as a decomposition of TickMonitor wall time. No live performance guarantee.

## Evidence and Stage handoff

Baseline #239 jar SHA256`2a058aeb42f56543a6c39f0386569d3966b2e2d4a23f524a5369052e63eb30ab`. Candidate SHA256`92154af1cc775098008a2da4d0e46092526ab19b65261ac9af260b88c8c7ea44` at `/home/lukas/grounds/artifacts/npc-viewer-rate-allocation-2026-11-02/candidate.jar`.

All sources, bytecode, test XML/logs, counters and bounded JFR evidence: `/home/lukas/grounds/artifacts/npc-viewer-rate-allocation-2026-11-02/`. Raw runs: `/home/lukas/grounds/artifacts/npc-load-profile-2026-11-01/rate-allocation-{baseline,candidate}-a/`. Original Scene SHA256`662a203e9d56b64a51075728d885b8877ba9f1220a25cbbf0ab55b72ef65c23e`, matched new harness SHA256`d0f8eac84566c5157982d1f03806550c775ba0c1543d42b85ad71e61f723f62f`.

Update the existing private Stage draft [deploy#555](https://github.com/groundsgg/deploy/pull/555) to the validated 92154af1 jar/image, preserving every non-image value. API probes still time out; infrastructure README documents Tailscale-only management APIs, and the installed Windows client reports NoState/offline. A request to activate the existing connection is pending; do not change device network/firewall/auth settings or claim Stage activation without a verified path. No Production configuration change.
