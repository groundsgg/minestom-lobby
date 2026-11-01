# NPC renderer math and 20/40/60 client validation

The renderer now prepares one immutable Scene root/local affine per committed NPC transform. Model matrices and decomposition output remain independently owned. Primitive quaternion and inverse-scale temporaries eliminate boxed Double collections; the decomposition algorithm and multiplication order remain unchanged.

## Correctness

- 172 licensed Host tests pass with 0 failures/skips, including independent prepared roots, sheared/signed/collapsed model geometry and root/local/rotation/scale/anchor changes.
- 723,456 pose/dirty-state records across all 15 actual models have identical serialized float bits against the frozen previous Stage jar: SHA256 `27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e`.
- The golden fixture applies signed/zero animation/model matrices; Scene root/local scales retain their required positive format contract.
- Formatting, all 983 addon-file checks and fresh independent correctness review pass.
- Model geometry, native SOLID, 64/72 block view ranges, interpolation and saved lighting are preserved. No delivery/cohort/instrumentation code from draft Host#233 is activated.
- The normal published-dependency CI path also passes: 172 tests, 0 failures, 5 expected licensed-asset skips. An initially stalled remote test job was cancelled once after the full local CI path passed in 18 seconds; the remote retry passed. The initial runner stall has no established root cause.

## Frozen inputs and method

Baseline jar `c3e662a91ac383f0f8698b937b08d7fe3178d8f6cfea18e73064f6c1ac67ee08` is the previous accepted Stage image. Candidate jar `8dfbb05b83632ad31747cf70de2c7bfceed849f71ec66fca7857d2cd97a9b47f` contains only the renderer math changes.

Both variants run in a fresh isolated 1 CPU/512 MiB JVM using exactly the same copied prelit Map9 (`0834f795...`, source identity `a1bec43f...`) and protocol 777. The test platform has 15 catalogued models/471 displays/765 cubes; the complete loaded scene also contains the lobby's other elements (619 item displays in both runs). Load rises 20→40→60 within each JVM, with at most 20 clients per bounded headless worker. Actual spawned client counts and near/mid/far zones are checked every sample. The first 20-client case has 40 s near + 60 s mixed warmup and 55 s measured delta; 40/60 have 20 s mixed warmup and 40 s measured delta. Later stages benefit from more JVM warmup, so this is a controlled A/B scaling test, not a linear cost model.

An additional 60-client phase uses bounded server-owned trajectories and protocol position confirmations. It is a real connected traffic/renderer workload, not a GUI/physics-walking or FPS measurement. A separate strict 60 s/2 ms JFR at 60 clients follows throughput measurement. Exit/reentry raw peaks include the transition commands and visibility work. No live attach or public auth bypass; accounts and external action backends are unused.

An initial baseline-only run was discarded completely: the client controller overwrote a pending stop command with the next command. The corrected controller waits for every client's command dispatch and verifies that the movement counter stops before the static profile. Both accepted variants use this same corrected controller (`renderer-math-*-b`).

## Results and rollout

Steady-state measurements are favorable, but reentry measurements do not yet clear this candidate for rollout. This is one matched A/B pair, not a universal gain estimate. Do not add these percentages to the earlier independent sensor improvements.

| Load | Mean tick, old → new (ms) | Process CPU, old → new (core) | Allocation, old → new (MB/s) | p99 tick, old → new (ms) |
| --- | --- | --- | --- | --- |
| 20 mixed | 1.554 → 1.280 | 0.1026 → 0.0941 | 21.84 → 20.15 | 5.294 → 3.661 |
| 40 mixed | 2.982 → 1.640 | 0.1848 → 0.1405 | 27.72 → 26.54 | 12.067 → 5.625 |
| 60 mixed | 2.303 → 2.017 | 0.2011 → 0.1921 | 35.08 → 33.15 | 8.180 → 5.995 |
| 60 moving | 2.617 → 2.508 | 0.2206 → 0.2183 | 42.61 → 40.46 | 8.089 → 8.612 |
| 60 after exit | 1.368 → 1.429 | 0.0978 → 0.1108 | 20.95 → 20.63 | 6.409 → 4.793 |
| 60 after reentry | 2.020 → 2.499 | 0.1792 → 0.2181 | 46.20 → 43.78 | 5.422 → 13.065 |

Steady mixed CPU falls 8.3% / 24.0% / 4.5% at 20 / 40 / 60 clients; mean ticks fall 17.6% / 45.0% / 12.4%; allocation falls 7.7% / 4.3% / 5.5%. The 40-client reference is slower than its own later 60-client window, illustrating warmup/noise rather than proving a monotonic capacity model. With 60 moving clients the mean improvement is smaller (CPU −1.1%, tick −4.2%, allocation −5.1%), while p99 rises 8.09 → 8.61 ms. Both variants remain approximately 20 TPS with 0 client disconnects and 0 JFR DataLoss.

Reentry is not a uniform win: its whole-phase peak is 51.99 → 71.65 ms and its subsequent short measured window is worse (see table). Exit also shows higher CPU with the candidate even though NPC metadata dispatch is zero there. A reversed-order follow-up with three 60-client exit/reentry cycles and no JFR investigates whether the tail disadvantage repeats. No rollout decision is based solely on the favorable steady rows.

First near-view phase (baseline): maximum 217.17 ms, including command delivery and first display/entity work.
First near-view phase (candidate): maximum 127.33 ms, including command delivery and first display/entity work.

This math change does not demonstrate a traffic reduction: measured metadata dispatch is roughly 3–8% higher in the mixed candidate windows. These independently started windows are not synchronized to an identical animation phase and are not a paired packet-stream equivalence test. No delivery policy changed, and fixed-pose metadata is bit-identical. At 60 mixed clients the aggregate is 96,883 → 100,018 metadata dispatches/s across all viewers; these are counted server dispatches, not per-client wire-byte measurements.

The separate 60-second diagnostic profiles contain 1,881 / 1,944 Java execution samples and 27,164 / 27,148 native samples. Flush/compression/packet writing is a major remaining path. Native blocking samples are not CPU time, and inclusive counts overlap. Top-level GC pause totals are 144.3 → 135.8 ms (maximum 9.60 → 6.87 ms) in those diagnostic windows. Absolute allocation rates above use ThreadMXBean counter deltas; raw JFR weights include pre-recording history and are not used as allocation throughput. Peak process RSS is 302.7 → 311.3 MB; this is not a retained-heap/leak test.

Raw manifests, tick series, command/count/zone checks, source-map immutability proofs, discarded-run marker and audited recordings remain in private local `artifacts/npc-load-profile-2026-11-01/renderer-math-*` and `artifacts/npc-renderer-math-2026-11-01`.

### Reversed-order reentry follow-up

The follow-up completed with 60 actual near clients, three exit/reentry cycles, no JFR, 0 disconnects, unchanged source map and a constant 619 item displays. It does **not** clear the candidate for rollout: cycles 2 and 3 have materially worse candidate ticks and CPU despite lower allocation.

| Window | Mean tick, old → new (ms) | Process CPU, old → new (core) | p99, old → new (ms) | Whole-phase maximum, old → new (ms) |
| --- | --- | --- | --- | --- |
| Before exit | 2.661 → 2.121 | 0.2237 → 0.1932 | 9.022 → 5.590 | 27.021 → 7.510 |
| Reentry 1 | 2.742 → 2.462 | 0.2152 → 0.2078 | 9.492 → 9.049 | 41.009 → 35.528 |
| Reentry 2 | 2.111 → 4.612 | 0.1931 → 0.2914 | 6.699 → 22.946 | 55.275 → 45.027 |
| Reentry 3 | 2.120 → 5.877 | 0.1876 → 0.2833 | 5.690 → 27.785 | 23.541 → 74.081 |

This establishes an unfavorable candidate run, not its root cause. Background JIT/GC work and renderer work are being separated in a new bounded owned diagnostic capture. No production code is changed speculatively, no Stage image is activated, and Host#236 remains draft until this is understood. Raw follow-up: private `renderer-reentry-*-a` manifests and `renderer-reentry-summary.json`.

## Remaining work

This completes the shared affine/primitive-temporary portion of N12. N07 clip prebaking, N09 keyframe cursors and broader scratch-matrix reuse remain separate changes. Draft Host#233's viewer-cohort delivery changes remain excluded because their earlier CPU regression has not been resolved.

N21 first-visibility and first-entity work remains the next measured target. Avoid partial bodies while spreading work: prepare an NPC's complete initial state before exposing it, preserve interaction ownership, and bound preparation per tick. The present headless movement test does not replace the user's earlier Java SOLID collision acceptance or a fresh GUI animation/scale smoke test.
