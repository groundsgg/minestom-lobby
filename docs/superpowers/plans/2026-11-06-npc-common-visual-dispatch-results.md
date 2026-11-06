# Common NPC visual metadata: qualification (6 November 2026)

**Decision: do not integrate or deploy candidate [Host #251](https://github.com/groundsgg/minestom-lobby/pull/251).** The small renderer cost improvement does not translate into a reliable overall performance win. The accepted baseline for this comparison is the complete source distribution from [Host #250](https://github.com/groundsgg/minestom-lobby/pull/250), Map 12 and the unchanged addon.

## Change and correctness

Candidate `16839bb4858cd5ebbf4ff8b8877b9ca5a2f826f1` installs one shared personal-visual provider only while overrides exist, and normalizes default metadata once per publication. Rates, recipient membership, geometry, collision and native grouped serialization stay unchanged.

The real renderer's 12-viewer/two-part test fails at 24 ordinary override-map reads before the change and passes at 0 afterwards, while all 24 packets arrive. Mixed scale overrides, clearing one/last override, default glow/fire flags, shared frames, queued ownership and custom callbacks are checked. 214 regular Host tests pass (6 licensed tests skipped in the regular neutral build). A separate complete private source build accepts the actual Map 12 scene, 15 models/348 platform parts and all 1089 unchanged addon files. Independent critical review found no important product-code issue.

Baseline JAR: `7f130a5d0b3ac81442b26be4326f61927925ea3a44370666cabb4ff93f967241`. Candidate: `afadbfb5bc35220487bfc0c1bda02c649407b315d31e4f62f111b6aff7bbe7aa`. Only AnimatedNpcHandle/NpcItemDisplay behavior classes differ; the facing initializer's extra class differs in debug metadata with identical disassembly. Every other class and asset is identical. No production classes are overlaid in the test harness.

## Controlled connected comparison

Four completed runs in baseline/candidate/candidate/baseline order, each with 20 then 60 actual animation clients **plus one stationary queue observer**: 21/61 total connections. The observer holds 103 queue displays active, so all measured near/motion/mixed/reentry windows contain 451 displays (348 platform + 103 queue) and 32 native collision cubes. Main clients move using real client physics/protocol packets; no repeated server-owned teleport trajectories. The mixed workers hold 7/7/6 then 20/20/20 clients at near/mid/far positions.

Exit uses spectator mode at (0, 80, 0), outside the platform's vertical range. All main clients are verified outside; zero metadata dispatches reach them during the exit interval. Reentry restores the complete scene before measurement. A real-child/HTTP-trap regression also verifies that startup-failure cleanup never contacts an endpoint before its own server is confirmed ready.

Backend: one active processor pinned to CPU 23 with a 512 MiB heap. Own controller/client processes exclude 23 and its verified SMT sibling 22. Hardware is shared, not exclusively reserved. No profiler runs; duration 0/sample parameter 20 ms. Measured phases last 20 seconds; rate/tick statistics use actual first/last snapshot intervals (about 15 seconds), while maxima include the whole phase. CPU, allocation and network use their corresponding recorded clocks. The isolated harness executes production NPC/Scene code from the complete Host JAR; external backend actions are local stubs. No graphics/FPS, Bedrock or real proxy-routing claim.

All four runs preserve the source map, have zero unexpected disconnects/network errors, and exit normally with no cleanup error. Temporary JVM allocation MB/s is separate from TCP payload MB/s. Two repeated pairs do not establish statistical significance.

## Complete results

Percentage changes compare arithmetic means of both runs per variant; negative is lower. Each count excludes the additional fixed observer.

| Phase | Process CPU | Tick-thread CPU/tick | Mean tick | p99 tick | Allocation | TCP payload |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| near-20 | +3.33% | -0.81% | -0.88% | +0.79% | +0.15% | +0.49% |
| motion-20 | +6.06% | +2.62% | +1.75% | -1.45% | +0.68% | +2.73% |
| mixed-20 | +9.42% | +3.41% | +5.13% | +18.96% | -0.38% | +0.42% |
| visibility-reentry-20 | -4.97% | -1.19% | -13.97% | -20.10% | +0.47% | +0.66% |
| near-60 | -1.32% | -0.96% | +0.08% | +23.40% | -0.08% | +0.40% |
| motion-60 | -6.14% | -6.46% | -10.91% | -20.81% | +0.74% | +1.02% |
| mixed-60 | -7.20% | -6.88% | -14.13% | -18.27% | +0.98% | +1.88% |
| visibility-reentry-60 | -6.73% | -5.82% | -14.48% | -25.36% | +0.72% | +1.96% |

## Repeat direction and adverse windows

| Phase | CPU change, pair A / pair B | Mean-tick change, pair A / pair B |
| --- | ---: | ---: |
| near-20 | +3.39% / +3.27% | -5.04% / +3.24% |
| motion-20 | +0.54% / +11.69% | +6.14% / -2.54% |
| mixed-20 | -0.01% / +18.51% | -7.11% / +17.28% |
| visibility-reentry-20 | +1.96% / -11.03% | -2.94% / -22.74% |
| near-60 | +5.97% / -8.15% | +8.59% / -8.25% |
| motion-60 | -14.08% / +3.31% | -25.06% / +8.26% |
| mixed-60 | -12.89% / -0.76% | -27.83% / +3.16% |
| visibility-reentry-60 | -10.40% / -2.81% | -23.13% / -4.50% |

At 20 clients, standing CPU rises in both pairs (+3.39/+3.27%). Moving process CPU rises (+0.54/+11.69%); tick-thread CPU also rises in both (+2.23/+2.99%). At 60-client movement, the apparent aggregate gain does not recur: CPU -14.08% in pair A versus +3.31% in pair B; mean tick -25.06% versus +8.26%. 60-client mixed distance CPU improves in both pairs (-12.89/-0.76%), but its mean tick reverses (-27.83/+3.16%).60-client reentry is the strongest repeated favorable window (CPU -10.40/-2.81%, mean tick -23.13/-4.50%). These favorable cells do not cancel the 20-client regressions or the unstable 60-movement result.

60-client standing p99 rises 23.40% in aggregate (4.619 to 5.700 ms), with paired +60.84/-6.57%. 20 mixed p99 rises 18.96%. Whole-phase maxima also have adverse windows; no rows are dropped. Allocation has no general reduction; TCP payload rises 0.40–2.73% across aggregate windows. The intended rates and packet contents are unchanged, and no network gain is claimed. Exact causes of all aggregate differences are not established.

| Complete run, including startup/transitions | Maximum tick (ms) | Ticks above 50 ms |
| --- | ---: | ---: |
| baseline-a | 155.801 | 1 |
| candidate-a | 170.373 | 1 |
| candidate-b | 474.398 | 8 |
| baseline-b | 143.088 | 1 |

Candidate-b's 474.398 ms maximum occurs with zero players, before animation-client admission. Seven of its eight ticks above 50 ms precede main-client admission; the fixed observer is present during some of those ticks. Its 68.469 ms transition-near-20 tick occurs with 21 players. These outliers are retained, not dismissed as caused solely by JIT or hardware. No cold-start improvement is inferred.

## Invalid preliminary fixtures and final disposition

Earlier attempts are retained but excluded from the performance decision: V1 did not wait for the Rust client's asynchronous pathfinder stop before teleporting to mixed positions; V2 allowed variable queue-entity activation and retained inherited nonzero recorder settings without starting a recording; the V3 pilot's far position did not actually leave the 72-block retention range. V4 fixes these before the entire four-run comparison: 2 seconds settling outside measured windows, a fixed observer, explicit unprofiled settings, genuine spectator/vertical exit with zero main-client metadata, and ownership-gated cleanup. No admission/display/exit assertions were relaxed to keep a run.

[Host #251](https://github.com/groundsgg/minestom-lobby/pull/251) is closed as unqualified. No private Stage image or NPC addon was published and no Stage/Production pin or runtime changed for this candidate. Reusable strict full-source benchmark artifacts and raw counters are under `/home/lukas/grounds/artifacts/npc-common-visual-dispatch-2026-11-06`; frozen measurement inputs are in `measurement-inputs-v4.json`, complete favorable/adverse cells in `connected-comparison-v4.json`, source/build proofs and invalid-attempt reasons alongside them. A future optimization must use the accepted Stage baseline and earn its own controlled result.

## Read-only Stage check after qualification

At 18:39 UTC, both Stage lobbies run a newer, separately deployed `stage-privacy-lobby` image, so the old exact-image verifier intentionally rejects that different image. A separate read-only check confirms both lobbies ready with zero restarts, prelit Map 12, the same catalog/addon/map pins, all 32 native collision cubes, prepared terrain and no errors in the inspected logs. All three current proxies register both lobbies; Argo is Synced/Healthy at deploy `f99d07e8e3f90eded78db5335211a52ed4366d89`. This does not qualify the newer source for performance or repeat native client acceptance. This task did not roll it out or restore the older image. Proof: `stage-current-readonly-proof.json` in the local artifact directory.
