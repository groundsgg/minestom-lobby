# NPC cold terrain: verification and Stage rollout

Verified2026-11-01. User confirmed the two Stage lobbies became visible and that first travel to the NPC platform showed no noticeable lag.

## Root cause and existing architecture

service-maps#74 already introduced saved map lighting on2026-10-19. The live Map9 pin advertises a format1 lit copy, but the lobby's custom MapDistribution read only the original bundle. That discarded the existing optimization on every new lobby. The old spawn preload also ran after ticking/admission and covered only part of the platform surroundings.

The lobby now selects supported prelit bytes, verifies/caches their digest and checks the format marker. Map identity, Stage allowlisting and repin/NATS comparison retain the original source digest. Missing or unsupported light formats use the original bundle, with bounded spawn/NPC terrain prepared before ticking. All selected chunks load before any are lit; owned work finishes before cleanup, including interruption/failure.

## Frozen artifacts

| Item | Value |
| --- | --- |
| Map version/source digest |9 / `a1bec43f75a701fc9011a07093a47573d5cc1036b9f876e68997786dcf3f50f9` |
| Existing format1 lit digest | `0834f7953b00e843d35d39a934fff8a551b55c962767bf4de5456a64c2e04467` |
| Final prelit Host jar SHA256 | `22cab857d153db264226114824cb9b3789a76139cab22e1243350a55ec46f4d1` |
| Stage image | `ghcr.io/groundsgg/npc-stage-poc:lobby-2.15.0-prelit-22cab857d153@sha256:49388fb8a84d0d78dafb5f41047aa477c4b9646f01b6536f1be036da0710fc54` |
| NPC addon SHA256 | `a00653443dbd27030317f21ad74f1d6c32e3a5b43a4703ebc75dc8747a5e0427` |
| Catalogue | `0.0.0-edge.1793492850.ge7a80f9731a7` |

Original and prelit CDN archives are checksum-verified; all29 nonterrain files match byte-for-byte, including scene.json. The historical local raw fixture has different JSON whitespace but equal scene semantics. All15models/179bones/471displays/765cubes remain. Native SOLID bytecode, mounted initialization and corner collision handling remain unchanged; startup reports6nativeSOLIDcubes.

## Connected cold tests

All runs use a fresh isolated JVM,1CPU/512MiBheap, exact Minestom2026.10.25b, protocol777 client, actual world copies and strict safe45s/2ms startupJFR. No live attach, default recordings, account credentials or GUI/FPS measurements. Each completed with one real spawned client, actual first-platform zone entry,0disconnects,0JFRDataLoss and an unchanged source fixture.

| Fixture / preparation | Startup terrain A/B | Spawn-join max tick A/B | First-platform max tick A/B | Peak processRSS A/B |
| --- | --- | --- | --- | --- |
| Raw, spawn-only prewarm control |2771/2451ms |185.34/49.83ms |361.51/343.30ms |not recorded/278.38MiB |
| Raw, new union prewarm |5137/2962ms |93.95/43.57ms |71.40/48.31ms |291.71/287.80MiB |
| Existing CDN prelit copy, new union prewarm |611/536ms |97.53/79.19ms |186.55/171.47ms |267.94/262.54MiB |

The raw control is deliberately favored: spawn lighting finishes before client admission, unlike the old production ordering. Its platform peaks still contain349.1/342.4ms of tick-thread waiting on the instance monitor. All four fixed platform peak intervals have0ms monitor waiting. The prelit runs expose separate cold entity creation/class initialization/GC work (10.4ms GC pause in the first selected peak); they do not establish that all first-render work is gone. Reduced lighting work also changes JIT warmup, so the raw and prelit peak differences are not steady-state NPC CPU comparisons. Historical1.65–1.92s lighting stalls came from a different raw harness and are not used as a direct gain percentage.

## Stage and checks

- 170licensedHosttests pass,0failures/skips; formatting passes.
- RealHTTP tests verify prelit selection/cache/source identity, unsupported format fallback, wrong bytes and missing marker rejection.
- Terrain tests verify cross-chunk propagation, later edits, delayed/interrupted load ownership and failed-start cleanup.
- All983addon asset files pass bundle acceptance on actual prelitMap9.
- Independent reviews found one initial task-ownership issue, fixed with join and a red/green delayed-loader regression; final reviews have no blockers.
- 128deployment tests and both GitOps PR checks pass.
- Stage activation: deploy#536 added safe fallback, deploy#542 (`78ad6fd7be2e842f07905ffc4222a5bcd0b1ae62`) activated prelit consumption.
- Both Stage lobbies report the expected prelit digest, original Map9/catalogue and6SOLIDcubes. Terrain preparation took3576/3186ms on the shared Stage node, down from16329ms on the first fallback-only pod. ArgoCD reportsSynced/Healthy; no restart was required from the user.
- User feedback after the replacement lobby appeared: “Sieht viel besser aus” and “Konnte jetzt grade keine Lags feststellen”.

Raw numerical evidence and audited recordings stay in the private local artifact directories `artifacts/npc-load-profile-2026-11-01/terrain-*` and `artifacts/npc-cold-terrain-2026-11-01`.
