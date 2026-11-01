# NPC sensor performance after the persisted-light fix

N20 removes collection/sorting of unrelated nearby display entities before interaction-owner resolution. N32 independently prepares immutable inverse bounds once per ActiveElement/root/local transform instead of once per viewer ray. No geometry, animation cadence, metadata policy or collision behavior changes.

## Method

Two sequential independent pairs use the exact frozen prelitMap9/CDN bundle, protocol777 Java clients,20actual connected/spawned clients and a checked7/7/6near/mid/far layout. Each fresh isolated JVM has1CPU/512MiBheap. Warmup40snear then60smixed precedes a60ssteady phase (55sbetween12samples). A separate60s/2ms strict safe JFR follows. ProcessCPU time, total thread allocated bytes and raw tick series produce rates; native waiting samples are not CPU and sampled allocation weights are not bytes/sec. No live attach, GUI/FPS or online-account claims.

## N20: owned hover candidates

| Metric | Before | After | Change |
| --- | --- | --- | --- |
| Mean tick |1.802ms |1.107ms |-38.6% |
| CPU |0.09755cores |0.07975cores |-18.2% |
| Allocation |30.255MB/s |28.090MB/s |-7.2% |
| p95 / p99 tick | see raw summary | see raw summary |-34.9% /-35.3% |
| Disconnects / JFRDataLoss |0/0 |0/0 | preserved |

Single controlled pair, not a universal percentage guarantee. Metadata/PLAY rates differ about2.7–2.8% with sampling/animation timing; there is no packet-policy optimization here. The discarded UUID comparator has49Java samples in the control profile and disappears after filtering. Hover query inclusive samples fall below the top30afterward; inclusive counts overlap and are not CPU percentages.

Frozen jars: baseline22cab857d153db264226114824cb9b3789a76139cab22e1243350a55ec46f4d1, candidatec481fd7c655424dc04269b762d0be7b5a8c0f799aebd8ab0cfb3ce899b509a24. SourceScene#24 merged as19342662aa9af2ac4482097052c7e1e83243fab6.268Scene/170licensedHost tests and formatting pass; independent review has no blockers.

## N32: prepared bounds

A fresh repeat of the accepted N20control gives1.022ms/0.07956cores/27.526MB/s, consistent with the first N20run. The separate prepared-bounds comparison is complete; its benefit is measured independently of N20.

| Metric | N20 control | Prepared bounds | Change |
| --- | --- | --- | --- |
| Mean tick |1.0216ms |1.0103ms |-1.11% |
| CPU |0.07956cores |0.08156cores |+2.52% |
| Allocation |27.526MB/s |21.992MB/s |-20.10% |
| p95 /p99 tick |2.1107/3.1615ms |2.0980/3.1250ms |-0.60/-1.15% |
| TPS |19.999 |20.001 | preserved |
| Disconnects /JFRDataLoss |0/0 |0/0 | preserved |

Ruling: retain this small immutable cache for its measured allocation/GC-pressure reduction. There is no measured CPU speedup; the observed2.5%increase is reported, not declared statistically neutral. One pair cannot establish a universal gain or regression. The prior per-ray matrix allocation path falls out of the top30inclusive allocation sites; prepared ray/slab queries still allocate dynamic vectors/tuples. Sample weights are not allocation rates.

Frozen candidatec3e662a91ac383f0f8698b937b08d7fe3178d8f6cfea18e73064f6c1ac67ee08.272Scene/170licensedHost tests pass, including warm-cache root/local rotations, translation, scaling, shear, reach, invalid rays, authored fallback and replaced bounds. Independent review has no blockers. All983addon files pass actual prelitMap9 acceptance. Native SOLID and Host NPC renderer bytecode are unchanged.

## Rollout and remaining work

The user-confirmed Stage baseline consumes persisted lighting (Host#234/deploy#542). The final sensor image passes the separate N32 acceptance and is active on both Stage lobbies via deploy#546: `ghcr.io/groundsgg/npc-stage-poc:lobby-2.15.0-hover-bounds-c3e662a91ac3@sha256:29a6a6b2096f2c91ba8d13b58971a57ea92a0acfbb91d723b5034b1cadbefb1a`. Native SOLID, the Host NPC renderer and existing world collision source behavior are preserved. The original mixed-viewer cohort candidate Host#233 remains draft because it increased CPU despite reduced traffic. N01 geometry consolidation, N13recipient cohorts and N21spreading first-spawn work require their own correctness/resource tests; no blanket activation of the33-item backlog.

Raw evidence: local private artifacts/npc-load-profile-2026-11-01/{hover,bounds}-summary.json, their source manifests/tickseries/auditedJFR, and artifacts/npc-cold-terrain-2026-11-01/bounds-bytecode-proof.json. NPC addon/catalogue/Map9/original source identity and all765cubes remain pinned.

Final live acceptance2026-11-01T20:13:36Z: both `lobby-de-nbg1-rmkpf-7hvvw` and `lobby-de-nbg1-rmkpf-856vg` Ready, exact image digest29a6a6b2...,0restarts, prelitMap9digest0834f795..., original source allowlist/catalogue/addon preserved, nativeSOLID6cubes. Terrain preparation3515/4813ms on the shared Stage node. GitOps#546 merged as9bfe71c9a27ddb5bda74c1258a9c0317a3d5caa0. User confirmation of lag-free cold entry applies to the earlier prelit fix; final sensor behavior has automated geometry/lifecycle and connected-client acceptance, with no separate GUI/FPS claim.
