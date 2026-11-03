# NPC viewer-scan qualification, 2026-11-03

## Decision and implementation

Qualified for a bounded Stage trial, with mixed end-to-end evidence. This is not a claim of a universal or repeatable CPU/tick improvement. The component allocation reduction is direct; the isolated whole-server comparison is encouraging. The adverse original comparison and unresolved transition outliers below remain part of acceptance.

Base main `751edf6545951b4be360d59e7ea00145a78a539c` includes accepted #239/#240. Code commit `79fba854c01274ed8606279c71392525eda7dfb1` adds a read-only equality check before the existing full viewer reconciliation. It checks admission, UUID, Player object identity, per-viewer rate, admitted count and minimum render interval. Any mismatch executes the existing full path. No new mutable cache, scan throttling, range/rate/deadline change, model math or native SOLID change. Drafts #233/#236/#238/#241 are not imported. Release #231 remains separate.

## Diagnosis and correctness

A startup-only offline ASM diagnostic of the original jar counted about 525 scans/s: 420 animation and 105 scheduled, across 21 animated handles. Steady windows were 100% unchanged; moving 96.9926%. This diagnostic includes instrumentation overhead and is not production throughput evidence. The two distance calculations per player remain a separate future opportunity.

Two semantic regressions cover equal-count leaving/entering viewers and a weak-iteration same-UUID reconnect. Independent review found the latter could leave the old minimum interval while cached identity/rate already matched. The controlled real removal/replacement test failed with pose 1.3 instead of 1.4 before the minimum check; the original full path passes. Primitive minimum equality fixes it. Final independent read-only review has no remaining correctness finding.

Licensed suite: 195 tests, zero failures/errors/skips. Ordinary suite: 195 tests, zero failures/errors and five expected asset skips. Formatting passed. Fixed-time golden: 723456 serialized states across 15 models, SHA256 `27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e`. Addon acceptance: 983 files, 15 complete platform models and 471 displays. Jar comparison changes only AnimatedNpcHandle implementation and its file initializer debug metadata; Scene, Resourcepacks and NpcItemDisplay are byte-identical. No probe/ASM classes are shipped.

Frozen baseline SHA256 `92154af1cc775098008a2da4d0e46092526ab19b65261ac9af260b88c8c7ea44`; candidate SHA256 `13f8ca505cead847019573ccd8843892bb9540b3f6b7b84b0835dd4f4899d3d6`.

## Direct component evidence

Five batches of 10000 calls after 5000 warmup calls per population, exact jars, thread allocation counter, stable synthetic two-display fixture, no sockets. Median bytes/call and ns/call:

| Players | Baseline bytes | Candidate bytes | Baseline ns | Candidate ns |
| --- | ---: | ---: | ---: | ---: |
| 1 | 424.01 | 80 | 1110.68 | 130.32 |
| 20 | 1624 | 80 | 2951.15 | 732.10 |
| 60 | 3448 | 56 | 5195.60 | 1115.59 |

These are component medians, not whole-server throughput or an allocation-free claim. Iterator overhead remains.

## Original full pair D: adverse, retained

Fresh original workload: 1→20→60 real headless protocol clients, 35s steady windows, separate 90s 2ms JFR first/second motion, 20s settle, 30s unprofiled motion, three exits/reentries. Backend CPU0, 512MiB. Both runs had 619 displays, no disconnect/error/JFR loss and unchanged saved-light map source.

| Phase | Mean tick Δ | p99 Δ | CPU Δ | Allocation Δ |
| --- | ---: | ---: | ---: | ---: |
| steady-1 | -4.34% | -15.91% | -0.02% | +1.98% |
| steady-20 | +22.67% | +74.31% | +23.60% | -1.63% |
| steady-60 | +1.18% | -13.45% | +8.78% | -2.35% |
| moving-60 | -8.42% | -6.73% | +4.78% | -0.28% |
| reentry-1 | +34.70% | +119.31% | +16.66% | -1.85% |
| reentry-2 | +17.18% | +23.71% | +13.05% | -2.41% |

Reentry-0 CPU/rate comparison is excluded because the first metric completion skew was 1773ms; the native tick-count window contains 470 ticks while epoch selection contained 436. Exact native ordinal mean/p99 values are retained and cross-checked against cumulative sums: baseline 2.384/6.866ms versus candidate **11.911/115.083ms**. Whole-phase maxima (a broader scope) are 28.934ms versus **156.379ms**; the baseline ordinal-window maximum is 10.899ms. These are real adverse ticks, not discarded as measurement error.

Candidate core0 busy was 84.45% vs 49.90%, tick runnable delay 11.348 vs 1.628ms/tick. This supports possible contention; it does not prove an external cause, since JVM threads can also contend. Original JFR first-motion peak worsened 52.712→110.334ms; second 8.985→9.184ms. The prior 56ms outlier is not fixed by this change.

Earlier a/b/c partial attempts are invalid comparisons: an exit143 controller left the original JVM alive, retry hit its bound port, and the next retry lost client containers during the user-confirmed Docker update (exit255, not OOM). They are retained, not pooled. The extracted native client binary was hashed but never executed; no alternate client shim was used.

## Targeted isolated follow-up: encouraging, separate

A fresh baseline-first pair uses the exact same frozen jars, Scene, map and protocol workload, but a distinct experimental setup. Backend CPU23; sysfs sibling22 excluded with23 from all owned controllers/proxies/clients. Client affinity 2–21,24–31, each quota two cores/1GiB. Affinity is not an exclusive reservation against unrelated host work. Direct20-client warmup60s,60-client warmup45s,35s steady windows; separate45s first-motion JFR,20s settle,30s unprofiled motion and three reentries. Native timestamps follow individual counter reads. CPU/allocation/metadata/TPS use matching native clock intervals; network uses its independent controller interval. Snapshot read spread ≤6.259ms. Tick quantiles use exact native ordinal slices and sum checks. Approximate /proc endpoints are not an additive CPU decomposition.

| Phase | Mean tick ms | p99 ms | CPU Δ | Allocation Δ | Whole-phase max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| steady-20 | 1.1480 → 1.0581 | 4.2361 → 2.4166 | -6.70% | -7.13% | 7.885 → 5.235 |
| steady-60 | 1.4784 → 1.4325 | 5.1308 → 3.6901 | -6.71% | -7.03% | 11.050 → 6.574 |
| moving-60 | 1.8453 → 1.8298 | 6.9499 → 6.9139 | -8.12% | -6.48% | 13.938 → 8.773 |
| reentry-0 | 1.5649 → 1.6224 | 4.9565 → 4.5136 | -5.81% | -4.12% | 12.191 → 17.081 |
| reentry-1 | 1.5251 → 1.5010 | 3.3187 → 3.6185 | -8.29% | -4.85% | 10.599 → 13.538 |
| reentry-2 | 1.5334 → 1.5440 | 3.4418 → 4.5419 | -1.89% | -4.15% | 11.454 → 10.916 |

Both runs: 619 displays, zero errors/disconnects/JFR data loss, unchanged maps, approximately20TPS. Integrity guards all pass. CPU and allocation are lower in all six windows, but reentry means/tails/maxima are mixed, including +31.96% p99 in reentry2 and +40.11% maximum in reentry0. Unprofiled phase maxima are below18ms. Observed TCP/metadata reductions of roughly3–5% are not intended or guaranteed traffic savings; no dispatch policy changes. Do not add these percentages to earlier optimizations.

Separate first-motion JFR peaks are 44.975→49.925ms, neither overlapping a recorded GC pause. Maximum recorded GC pauses are3.237→4.605ms. Few samples cannot establish sole cause. Weighted map-builder allocation samples within refresh decrease45,585,208→1,851,776bytes (36→2events); complete refresh-path weights197,675,464→188,071,376bytes (177→155events). Overlapping sampled weights are not exact rates. Snapshot/renderer work remains a future investigation; #241 is still unqualified and separate.

This single pair has different warmup/core placement/instrumentation from D, cannot invalidate D, and establishes neither repeatability nor statistical significance. Direct component evidence plus the isolated whole-server evidence justify only a bounded Stage trial, retaining the above regressions for monitoring.

## Inputs, artifacts and rollout boundary

Artifacts: `/home/lukas/grounds/artifacts/npc-viewer-scan-2026-11-03`; raw runs: `/home/lukas/grounds/artifacts/npc-load-profile-2026-11-01/viewer-scan-{baseline,candidate}-d` and `viewer-scan-isolated-{baseline,candidate}-a`. Important files: `frozen-proof.json`, `golden.json`, `diagnostic-summary.json`, `component-summary.json`, `summary.json`, `reentry-zero-adverse.json`, `host-context.json`, `isolated-summary.json`, `isolation-proof.json`, `*-isolated-motion-summary.json`, `*-isolated-allocation-sites.txt`, supervisor/cleanup proofs and retained logs/scripts. Both complete supervisors exit0. All own client containers removed after completion.

Map9 raw identity `a1bec43f75a701fc9011a07093a47573d5cc1036b9f876e68997786dcf3f50f9`; saved-light digest `0834f7953b00e843d35d39a934fff8a551b55c962767bf4de5456a64c2e04467`; Scene SHA256 `662a203e9d56b64a51075728d885b8877ba9f1220a25cbbf0ab55b72ef65c23e`; catalogue `0.0.0-edge.1793492850.ge7a80f9731a7`; addon SHA256 `a00653443dbd27030317f21ad74f1d6c32e3a5b43a4703ebc75dc8747a5e0427`. Native SOLID six cubes and557prepared visitor chunks must remain. Actual Java-protocol headless clients do not establish GUI/FPS/Bedrock/cross-proxy acceptance.

Rollout follows exact-head source CI and a separate private image/deploy PR, preserving all non-image Stage values and all Production values. Tailscale was offline; user reconnected and both management APIs were actually read successfully. No route/auth/firewall changes. Prior human Stage acceptance applies to #239/#240 only; this candidate requires new rollout verification. Live proof/merge hashes are recorded separately after activation, not inferred from a successful merge.
