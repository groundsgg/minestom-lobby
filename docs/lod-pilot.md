# Private Lobby LOD pilot

This optional integration is disabled unless `GROUNDS_LOD_PILOT_ENABLED=true`. It accepts only
the ACTUALLY loaded prelit Stage `lobby/mainlobby` version13. The canonical SHA, selected
transport SHA and environment come from `MapDistribution`, including disk-cache hits.
Baked-in fallback maps, other versions/environments and unlit transports remain ineligible.

The module serves LSS20/Zstd to compatible Voxy+LSS clients. It does not serve native Distant
Horizons. Client-side Voxy still builds its rendering structures from the prepared terrain.
Real-client compatibility, two-proxy behavior and actual network cost remain acceptance gates.

## Build dependency

The typed dependency is `gg.grounds:service-maps-lod-runtime:1.12.0`, with the shared format
dependency of the same Maps version. This is the planned next Maps feature release after
1.11.1. Both artifacts must exist before merging/building the host normally. If release
ordering changes, pin the actual reviewed published version before host integration.

Until that release, qualify the exact reviewed local source without MavenLocal or a package
upload:

```sh
./gradlew -PlodSourceDirectory=/absolute/path/to/service-maps test shadowJar
```

The property substitutes only the two LOD library modules; existing NPC/Scene source-build
properties and private distribution rules retain their existing behavior.

## Explicit configuration

| Variable | Purpose |
| --- | --- |
| `GROUNDS_LOD_PILOT_ENABLED` | Defaults off; only literal `true` enables selection |
| `GROUNDS_LOD_PILOT_RUN_ID` | Already registered finite serving-run UUID |
| `GROUNDS_LOD_PILOT_PLAYERS` | Comma-separated1..5 distinct canonical Minecraft UUIDs |
| `GROUNDS_LOD_PILOT_GENERATOR_SHA256` | Exact generator identity from the selected worker |
| `GROUNDS_LOD_PILOT_CAPABILITY_FILE` | Read-only mounted file with the per-run capability |
| `GROUNDS_LOD_PILOT_SERVICE_ORIGIN` | HTTPS origin, defaults `https://api.grounds.gg` |
| `GROUNDS_LOD_PILOT_CACHE_DIR` | Absolute writable directory; defaults under JVM tmp |
| `MAPS_ENVIRONMENT` | Explicit override must be `stage`; actual loaded environment is also checked |

Use a new random capability for each manually admitted run. Store only its SHA256 in the Maps
serving-run row. Do not put it in URLs, pin files, Minecraft packets or logs. Registration does
not activate the API/host flags, and an API restart/rejoin does not reset counters. The API must
have its independent `MAPS_LOD_PILOT_ENABLED` flag enabled for the same finite test window.

Initialization does no HTTP request during Lobby installation. Only an allowlisted compatible
client handshake starts one shared asynchronous cache load. Optional configuration, source,
quota or cache failures disable LOD without blocking the normal map/Scene/Player ready path.
No client request generates missing chunks. No R2 keys are present on this host.

UUIDs come from the actual authenticated Minestom player connection and use the network-wide
DB budget. They are not resolved through the current proxy's local player list. One local
outgoing frame per UUID bounds backpressure across overlapping reconnects. A global packet
guardian outlives the Lobby event subtree until queued owned frames have been dropped/consumed.

## Cost measurement and stopping

The API limits a run to30min, five UUIDs and1GiB reserved application payload; shared remote
read/byte quotas apply before the private one-use source GET. The host has bounded cache,
request queues and request-start grant deadlines. The runtime's counters count authorized
payload bodies, not confirmed socket/proxy bytes; measure the actual network path separately.
R2 account allowances and the actual server traffic tariff must be included in the final report.

Stop by setting `map_lod_serving_run.enabled=false`, then disabling host/API pilot flags. New
payload credits stop immediately; existing credits expire within1s. Already reserved source
transfers remain subject to their finite deadlines. Ordinary joins/maps remain independent.

Do not broaden to public LOD objects, other maps or more UUIDs before the measured private
pilot has passed. No monthly monetary budget is assumed by this code.
