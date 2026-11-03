# NPC activation preparation — qualification, 3 November2026

## Implementation and artifact identity

Prepare immutable per-part ItemStacks once when model renderers load; read packed flags directly instead of constructing full metadata snapshots. Attach each model with viewers denied, prepare every initial part transform, then admit viewers through the existing handle refresh. Use Scene0.4.4 transitionBudgetPerTick=1 for Lobby automatic transitions. This bounds complete transition starts, not hard elapsed milliseconds or asynchronous completion concurrency. A single large model remains indivisible. No geometry, collision, viewer range, rate or interpolation policy change.

Source PR243 builds on accepted main4b2b664. Source code commits a3067b6 and1719b93; later commits are documentation. Current Stage independently uses Announcements revisionab5c739 on feat/portal-announcements, not deploy/main. The Stage candidate preserves that exact archive and changes only LobbyNpcModelRenderers, LobbySceneActionsKt and NpcItemDisplay.

- Baseline Stage jar: `46da882ed93d28d2d982683c27765fd4786f6591ddb208498363cfdbdef80c29`.
- Candidate Stage jar: `f74f3e927c2d6f8b0544e09b851d60b88aa0b99e95e775798ef4750b80eab9bd` (`stage-candidate-verified.jar`).
- Source candidate jar: `df202ca41c90ccc6c0621e51df3e0db8e7559f585485a68e1f293fe6073925f9`.
- All40415 physical ZIP entries retain order/duplicates;39069 unique names. Only the three reviewed payloads differ and are identical to the source build. The rejected earlier `stage-candidate.jar` is partial and must never be used.

## Correctness and review

201 ordinary tests pass with five expected licensed-asset skips;201 licensed tests pass without skips. The exact final Stage jar also passes201 tests without skips; loaded-class origin evidence confirms all three changed classes came from that jar. Formatting passes. Golden723456 states/15models remains27daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e. Addon acceptance983files,471platformdisplays,765authoredcubes unchanged.

Two new publication tests fail against the old implementation (partial/pre-pose visibility and a rejected sibling exposing the first display) and pass against the candidate. Real automatic Scene tests fail with all three starts in tick1 and pass with at most one transition start per tick and eventual completion. Cleanup is verified after the asynchronous close future completes. Immutable-stack mutation isolation and all packed flag bits/personal highlighting are characterized.

Both sendVisualFlags/sendPoseState previously called getMetadataPacket once; candidate bytecode calls it zero times and reads MetadataHolder.get directly. This proves removal of those full-map snapshots, not a whole-JVM allocation percentage.

Independent read-only source/artifact review found no new blocker. Minor pre-existing deferred issue: a direct renderer-future cancellation during attachment may briefly publish then remove a complete model; normal Scene0.4.4 does not cancel that future. Closure may allow an already in-flight transition before its returned future settles. Prepared native stacks need Minestom registries initialized: production already does this; standalone addon acceptance now initializes MinecraftServer first without opening a listener.

## Cold connected ABBA

Four fresh unprofiled offline JVMs, order baseline/candidate/candidate/baseline, one actual protocol777 headless client and two admissions per JVM (second admission has a new offline identity). Same native Scene, Map9 saved lighting,557prepared chunks,21handles and619displays. First platform entries reach all619displays; no disconnect/proxy errors and original map unchanged. Initial renderer preparation remains roughly47–54ms with no demonstrated general improvement.

| Run | First619 attached ms | Warm new-identity619 ms | Renderer prepare ms |
| --- | ---: | ---: | ---: |
| activation-prep-baseline-a | 437 | 428 | 53.786 |
| activation-prep-candidate-a | 743 | 852 | 53.543 |
| activation-prep-candidate-b | 1152 | 1051 | 51.959 |
| activation-prep-baseline-b | 335 | 129 | 46.871 |

Attachment latency is native command to server monitor end with619attached displays; not exact client publication or rendered frames. Existing-entity visibility reentry takes0–3ms in these cold fixtures.

| Run / phase | Mean ms | p99 ms | Maximum ms | Ticks>50ms |
| --- | ---: | ---: | ---: | ---: |
| activation-prep-baseline-a / startup | 8.931 | 118.597 | 118.597 | 1 |
| activation-prep-baseline-a / first-admission | 3.094 | 37.759 | 48.026 | 0 |
| activation-prep-baseline-a / first-platform-entry | 2.189 | 15.014 | 45.575 | 0 |
| activation-prep-baseline-a / platform-reentry | 1.322 | 8.355 | 19.260 | 0 |
| activation-prep-baseline-a / warm-new-identity-admission | 0.759 | 6.364 | 13.321 | 0 |
| activation-prep-baseline-a / warm-new-identity-platform | 1.147 | 8.306 | 28.428 | 0 |
| activation-prep-candidate-a / startup | 9.671 | 124.307 | 124.307 | 1 |
| activation-prep-candidate-a / first-admission | 2.893 | 18.121 | 34.197 | 0 |
| activation-prep-candidate-a / first-platform-entry | 2.263 | 12.061 | 28.655 | 0 |
| activation-prep-candidate-a / platform-reentry | 1.204 | 6.621 | 17.950 | 0 |
| activation-prep-candidate-a / warm-new-identity-admission | 0.801 | 6.332 | 10.063 | 0 |
| activation-prep-candidate-a / warm-new-identity-platform | 1.107 | 6.283 | 9.940 | 0 |
| activation-prep-candidate-b / startup | 9.035 | 134.638 | 134.638 | 1 |
| activation-prep-candidate-b / first-admission | 3.150 | 24.161 | 29.561 | 0 |
| activation-prep-candidate-b / first-platform-entry | 2.313 | 12.542 | 14.498 | 0 |
| activation-prep-candidate-b / platform-reentry | 1.246 | 6.894 | 13.919 | 0 |
| activation-prep-candidate-b / warm-new-identity-admission | 0.766 | 6.227 | 7.618 | 0 |
| activation-prep-candidate-b / warm-new-identity-platform | 1.076 | 8.155 | 10.861 | 0 |
| activation-prep-baseline-b / startup | 12.844 | 148.774 | 148.774 | 1 |
| activation-prep-baseline-b / first-admission | 3.161 | 28.386 | 73.450 | 1 |
| activation-prep-baseline-b / first-platform-entry | 2.138 | 9.596 | 46.521 | 0 |
| activation-prep-baseline-b / platform-reentry | 1.261 | 6.329 | 14.032 | 0 |
| activation-prep-baseline-b / warm-new-identity-admission | 0.719 | 6.707 | 9.972 | 0 |
| activation-prep-baseline-b / warm-new-identity-platform | 1.132 | 6.544 | 29.886 | 0 |

The first-platform maxima fall45.575/46.521→28.655/14.498ms; the corresponding means rise2.189/2.138→2.263/2.313ms and p99 is mixed. Complete first-platform attachment grows335–437→743–1152ms. Startup maxima remain118–149ms in baseline and124–135ms in candidate. Cold improvements trade peak work for gradual scene availability; they do not establish a general CPU/tick win or fix the historical441.802ms Stage spike. CPU/allocation samples in load-summary are settled tail intervals, not complete transition cost.

## Sustained connected comparison

One fresh baseline-first pair:20clients (30s warmup,35s steady),60clients (40s warmup,35s steady),30s movement and two25s visibility reentries. No JFR recording. Every selected sample has619displays, matching native zone/client counts,60real protocol connections at load, no disconnect/proxy errors. Both final manifests show clean exit, original map unchanged, exact pinned jar hashes, and cleanup success.

| Phase | Mean ms old→new | p99 ms old→new | Whole max ms old→new | CPU delta | Alloc delta | TCP MB/s old→new |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| steady-20 | 0.973→1.070 | 4.503→4.527 | 7.371→9.513 | -0.90% | -1.84% | 1.072→1.065 |
| steady-60 | 1.279→1.365 | 3.021→3.889 | 5.036→6.153 | +2.53% | -3.13% | 3.371→3.327 |
| moving-60 | 1.940→1.983 | 8.931→8.858 | 32.588→32.080 | -4.32% | -2.56% | 4.433→4.454 |
| reentry-0 | 1.528→1.560 | 5.733→4.435 | 12.247→20.906 | -0.96% | -2.12% | 4.851→4.722 |
| reentry-1 | 1.512→1.499 | 4.869→4.339 | 17.255→11.509 | +2.01% | -1.70% | 4.827→4.761 |

All phase results, including adverse windows, remain in steady-summary.json. Exact native ordinal tick windows are cross-checked against cumulative sums; CPU/allocation/metadata counters use their individual native timestamps. Whole-phase maxima include transitions and have broader scope. Readmission here uses Y-only travel; Scene activation uses X/Z, so all619entities remain attached. It is visibility re-admission, not cold reconstruction.

## Experimental limits and rollout boundary

Backend CPU23 affinity, sibling22 excluded from all owned clients/controllers/proxy;512MiB heap, ActiveProcessorCount1, empty application environment except PATH. This is not exclusive against unrelated host work or a Stage CPU-quota reproduction. Local external actions are stubs, no real proxy/service routing or forced resource-pack offer. Headless protocol clients do not establish GUI/FPS, Bedrock, native gameplay or cross-proxy acceptance. Cold two-runs-per-variant and hot single pair are not statistical significance/reproducibility. Phase assignment uses tick end time; long ticks may straddle boundaries.

Preserve saved-light Map9 digest0834f7953b00e843d35d39a934fff8a551b55c962767bf4de5456a64c2e04467, raw identitya1bec43f75a701fc9011a07093a47573d5cc1036b9f876e68997786dcf3f50f9, Scene662a203e9d56b64a51075728d885b8877ba9f1220a25cbbf0ab55b72ef65c23e, catalogue0.0.0-edge.1793492850.ge7a80f9731a7, addonSHA256a00653443dbd27030317f21ad74f1d6c32e3a5b43a4703ebc75dc8747a5e0427, native SOLID six cubes/position confirmation and accepted visibility. No Production/release action; older drafts233/236/238/241 and release231 remain separate.

Qualified rollout requires exact-head source CI, private image hash/config verification, an isolated deployment pin on the currently active Announcements base, full deploy tests/CI and actual Argo/Agones image/readiness proof. Only the lobby-specific existing Stage revision override may advance; all other component revisions and settings must remain unchanged. Normal Fleet reconciliation, no manual deletion of occupied lobbies. Fresh gameplay acceptance applies only after actual deployment.

## Remaining plugin-derived candidates

Updated35-item source backlog records current primary sources and distinguishes plugin features from our proposals. Next CPU experiment: prepared clip data/keyframe cursors and bounded shared samples (BetterModel), preserving loops/Step frames/large rotations/head layers. Then profile-guided spatial viewer candidates (MythicMobs AABB) and conservative view-cone/occlusion culling with near-radius exceptions (ModelEngine), without lowering accepted view distance. Small packet bundles need real byte/inner-packet/client-latency evidence; static geometry consolidation/internal-face removal needs full asset/animation review. ItemsAdder delay_packets and Oraxen static furniture are not proof that animated parts or authoritative hitboxes can be removed.

Artifacts: flags-bytecode-proof.json, task1/2 RED/GREEN logs, licensed-test-results, stage-jar-test-proof.json, frozen-proof.json, stage-overlay-proof.json, golden.json, acceptance-initialized.log, load-summary.json, steady-summary.json once complete, final manifests/cleanup and retained raw phase CSVs under /home/lukas/grounds/artifacts/npc-load-profile-2026-11-01/activation-{prep,steady}-*.

## Qualification decision

Qualified only for a bounded Stage trial. Both cold ABBA repetitions reduce the first-platform maximum, with the explicit full-scene latency tradeoff. Sustained60-client and movement integrity passes and all measured hot phase maxima are below33ms. Hot mean CPU/tails are mixed: steady20mean+10.05%;steady60mean+6.72%,p99+28.72%,CPU+2.53%;moving60mean+2.23%,CPU-4.32%,allocation-2.56%. First readmission maximum12.247→20.906ms is retained. This is not a general mean-tick improvement. No new gameplay acceptance yet.
