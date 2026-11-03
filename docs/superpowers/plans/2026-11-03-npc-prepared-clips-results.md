# Prepared NPC clips — qualification, 3 November2026

## Change and scope

Prepare immutable per-clip bone/channel bindings and local transforms for only0/1-key position/rotation/scale tracks. Bound constant caches to1024bone/clip entries per model; larger models fall back to the original evaluator. Keep the existing dynamic sampler and exact Translation*Rotation*Look*Scale order. Head look remains live, every output matrix remains caller-owned. No cursor state, quantized shared pose samples, changed view/rate/interpolation policy, model geometry or collision change.

Current15models contain45clips,391tracked bone/clip combinations,110constant combinations and578nonemptytracks (median5keys,max23). This supports prepared constant work before adding stateful cursor lifecycle complexity. N09cursors/full dynamic pose caches remain open. Compiled model data remains immutable, consistent with pre-existing bind caches.

Resourcepacks branch perf/npc-prepared-clips-20261103, baseeab25db; functional headc3c260fb76efbf11ef92650481657608f9539a21. SourcePR62 final formatted head1e06cbb27df7f174ad84dacae5a0ee4dbcd5756d. Public API/version and Maven coordinates unchanged. Private Stage variant only; published-runtime adoption requires the normal future package release, which is not triggered here. Accepted#243/#560construction/range/SOLID/lighting/Announcements remain intact.

## Correctness and independent review

Original8runtime tests pass. New resource-allocation contract RED30544bytes per12bone/50part constant animated pose, then GREEN<20000. Literal head/descendant look/seek fixture includes nonuniform(2,1,1)scale and result mutation isolation. Equal-valued multi-key tracks retain interpolation rounding.5clips×256bones verify late-clip outputs plus exact1024cache budget.

Independent review found a real new eager-overflow bug: finite source values can overflow only when one unused clip is played; preparation initially rejected the whole model. Two parsed-model rotation/position tests reproduce RED. Finite-component-sum eligibility leaves unsafe constant bones dynamic, preserving model load/other clips and the original selected-clip error. All14runtime tests pass with0failures/errors/skips; final delta review has no outstanding finding.

Licensed Host201tests and exactStageJar201tests pass with0skips. Loaded-class origins prove NpcModel and all three prepared helper classes came from the exactStageJar.723456serialized states across15models retain SHA25627daa3691026f1a7a958113dcda036b9365f56708ded853bbc60e1429295083e.983-file addon acceptance passes, unchanged471platformdisplays/765authoredcubes.

Initial full sourceCI failed the inherited Spotless gate because local runtime:test did not invoke it. Mandatory formatting was applied and checked; complete exact-headCI now passes. It changes layout only. Connected/component experiments use the frozen pre-format semantic artifact below; final formatted artifact and instruction equivalence are verified separately before rollout. No semantic change may be carried across without new qualification.

## Frozen artifact preservation

CurrentStage base f74f3e927c2d6f8b0544e09b851d60b88aa0b99e95e775798ef4750b80eab9bd maps to the accepted immutable86c6c07921392113460238cae25e1e3b4c8c8ea4d279627aa7ff02b2bb702455image. Measured candidate d7336959e54e6f86e8591cf85294a2567318053ce874c0324114c0c7c4fb0865. All40415original physical ZIP entries retain order/duplicates, six reviewed entries are replaced and three scoped helper classes added. The replaced entries are NpcModel, four source-debug-only runtime classes and the modulemetadata. Four javap instruction dumps for NpcMatrix/NpcMatrix$Companion/NpcModel$Companion/NpcModelKt are identical to baseline. All other payloads, including Announcements, nativeSOLID and saved lighting inputs remain unchanged.

## Direct component ABBA

Four fresh owned JVMs in baseline/candidate/candidate/baseline order; CPU23 affinity,512MiBheap,ActiveProcessorCount1,emptyenvironmentexceptPATH, no sockets/listener/profiler. Load15frozenmodels;10000warmup poses then5batches×6000poses across all60model/clip states including bind/null, changing times/look. Checksums match exactly. These are component results, not actual live clip proportions, throughput or retained-heap measurements.

| Run | Median bytes/pose | Median wall ns/pose | First model load ms | Model load allocated bytes |
| --- | ---: | ---: | ---: | ---: |
| baseline-a | 17562.28 | 6813.90 | 70.585 | 13949640 |
| candidate-a | 14697.93 | 6320.79 | 80.922 | 14782840 |
| candidate-b | 14775.49 | 6175.60 | 71.546 | 14782840 |
| baseline-b | 17594.28 | 7046.48 | 76.912 | 13949544 |

Component allocation drops approximately16%; measured wall time improves in both candidate runs. First-load temporary allocation rises about833KB; load time is mixed. No whole-server or retained-RAM gain is inferred from this.

## Connected20/60client comparison

One fresh baseline-first unprofiled pair,30s/40swarmups,35ssteady windows,30smovement,2visibility reentries. All selected samples retain619displays and matching20/60native clients/zones, no disconnect/proxy errors. Final manifests, map integrity, exact hashes and cleanup verified.

| Phase | Mean ms old→new | p99 ms old→new | Whole max ms old→new | CPU Δ | Alloc Δ | TCP MB/s old→new |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| steady-20 | 1.088→1.089 | 4.573→4.301 | 7.651→7.371 | -3.11% | +0.29% | 1.068→1.070 |
| steady-60 | 1.380→2.049 | 3.542→7.974 | 9.955→15.337 | +21.15% | -0.31% | 3.303→3.281 |
| moving-60 | 2.019→2.564 | 9.108→13.300 | 41.341→87.453 | +25.56% | +0.43% | 4.468→4.440 |
| reentry-0 | 2.170→1.612 | 9.942→3.590 | 20.332→13.299 | -12.77% | +0.38% | 4.667→4.704 |
| reentry-1 | 1.602→1.683 | 6.141→5.680 | 16.872→14.199 | +3.30% | +0.07% | 4.719→4.733 |

All adverse phases remain retained. Native timestamp intervals match each cumulative counter; tick quantiles use exact ordinal slices and sum cross-checks. Whole maxima cover broader phase transitions. Exit/reentry is Y-only viewer readmission while all619entities remain attached, not cold reconstruction. Renderer-preparation/startup metrics remain available in raw runs and are reported separately.

## Limits and integration boundary

BackendCPU23 and sibling22 excluded from all owned clients/controller/proxy; not reserved against unrelated host work. Required local formatting used a separate single-use Gradle process explicitly outside22/23during baseline warmup. Retain host scheduler/busy counters; do not assert zero external contention. One hot pair and two component runs/variant do not establish repeatability/statistical significance. No live proxy/services/resourcepack offers/GUI/FPS/Bedrock/cross-proxy acceptance is inferred from headless fixtures.

Preserve exact Scene662a203e9d56b64a51075728d885b8877ba9f1220a25cbbf0ab55b72ef65c23e, saved-lightMap9digest0834f7953b00e843d35d39a934fff8a551b55c962767bf4de5456a64c2e04467, rawmapidentitya1bec43f75a701fc9011a07093a47573d5cc1036b9f876e68997786dcf3f50f9, catalogue0.0.0-edge.1793492850.ge7a80f9731a7, addonSHA256a00653443dbd27030317f21ad74f1d6c32e3a5b43a4703ebc75dc8747a5e0427andSHA1deb3e36dd40a3ed0b4df237ac35d77e110bdaa34, SOLID6cubes/position confirmation and557preparedchunks. Historical441.802msand retained306/341msStagepeaks remain unresolved.

Between tasks Announcements merged to deploy/main as#559; former feature branch was removed remotely. Main d51eca4 has exactly the currently active Lobby values, including#560. New deployment may target main; existing lobby-specific Stage override and four other-component pinned revisions must be preserved/updated narrowly, with exact-merge scope proof and atomic full-spec precondition. No manual deletion of occupied lobbies or shared service restart, Production/release/Maven publication action. Existing experimental drafts233/236/238/241 remain separate.

Artifacts include runtime/test RED/GREEN/review evidence, full failed/passing sourceCI proofs, source/golden/addon/class-origin/ZIP-preservation/disassembly proofs, component-summary.json and raw20/60runs under /home/lukas/grounds/artifacts/npc-load-profile-2026-11-01/clip-steady-*. Fresh human acceptance applies only after actual newStageactivation.

## Interim qualification ruling

HOLD: the first connected pair does not qualify for activation. Static60mean+48.48%,CPU+21.15%;motionmean+27.04%,CPU+25.56%,maximum41.341→87.453ms. Whole-JVMallocation is nearly unchanged. CPU23busy/scheduler and compilation differences support investigation but do not prove an external cause or invalidate these adverse ticks. Keep sourcePR62draft, current acceptedStage untouched. A separate reverse-order60-only warmed diagnostic pair is pending.

## Completed reverse-order warmed follow-up

Candidate first, baseline second,60clients only. Each:60swarmup,45s explicit2ms whitelist first-motion profile,20ssettle,35s unprofiled steady,30sunprofiled second-motion,2visibility reentries. Both final manifests/maps/hashes/cleanup pass; audited profiles have0dataLoss. This is a different setup and cannot invalidate or be pooled with the original adverse pair.

| Phase | Mean ms old→new | p99 ms old→new | CPU delta | Allocation delta | Whole max ms old→new |
| --- | ---: | ---: | ---: | ---: | ---: |
| steady-60 | 1.470→2.151 | 3.306→7.588 | +17.23% | -1.27% | 4.867→13.802 |
| moving-60 | 1.791→2.269 | 5.595→9.925 | +10.40% | -0.57% | 8.731→16.424 |
| reentry-0 | 1.658→1.770 | 5.450→6.450 | +2.30% | +0.20% | 20.813→13.616 |
| reentry-1 | 1.484→2.323 | 3.590→8.140 | +19.13% | -0.89% | 13.548→52.777 |

The warmed follow-up remains adverse: static60mean+46.25%,CPU+17.23%; moving60mean+26.67%,CPU+10.40%. Allocation decreases only1.27%/0.57% in these windows. Reentry1mean+56.59%,maximum13.548→52.777ms is also retained. It does not reproduce the original87mspeak exactly or establish its cause.

Profiles are separate from the compared unprofiled windows.525baseline/548candidate execution samples are aggregated; inclusive allocation weights overlap and are not CPU shares or exact rates. The method instruction count actually falls388→359, so no claim of a larger sampling method causing the regression is made. Higher host busy/scheduler and compilation counters motivate further investigation but do not establish a sole external/JIT cause.

An invalid earlier diagnostic-b attempt armed startup recording via the reused cold fixture and failed its strict recorder guard before planned warmup. It is retained separately and not a valid comparison. Its precise guard failure was not logged beyond IllegalStateException. The corrected diagnostic fixture removes only startup auto-recording and retains the explicit endpoint/whitelist. Recorder self-test passes. Both corrected-c recordings passed audit,0dataLoss. No guard was disabled or widened.

Formatting bridge: all8affected/new runtime classes have identical javap instructions/resolved symbols before/after formatting (constant-pool numeric indices normalized only). Final formatted source has also passed201licensedHosttests and complete repositoryCI. MeasuredStageartifactd733 is retained, no image built or pushed, no deployment.

## Final decision

**Not qualified. Keep ResourcepacksPR62as draft.** The direct pose allocation improvement does not compensate for repeated adverse whole-server CPU/tick evidence and nearly unchanged total allocation. No source merge/Maven release/private image upload/Stage or Production change. Current accepted#243/#560remains active. Keyframe cursors/full shared dynamic pose samples remain deferred; the combined constant/binding candidate must not be treated as a shipped N07 improvement. This concludes this bounded candidate qualification, not all future NPC optimization work.
