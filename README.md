# Minestom Lobby

This is a simple lobby built on Minestom.

Following features are implemented:

- Official skins are loaded
- Works with velocity and as standalone
- Configurable address and port through `grounds-minestom-runtime`
- Server startup is delegated to `grounds-minestom-runtime`
- Auth and profile forwarding are configured through `grounds-minestom-runtime`
- `MapBlockRenderingModule` is installed before the world module so authored head
  profiles, sign text and banner patterns reach clients. This enables rendering
  without adding Vanilla gameplay to the lobby.

## Running

Use `GROUNDS_BIND_HOST`, `GROUNDS_BIND_PORT`, and `GROUNDS_SERVER_BRAND` to configure the server.

### Behind Velocity

1. In Velocity, got to the `velocity.toml` and change `player-info-forwarding-mode` to `modern`.
   (Example: `player-info-forwarding-mode = "modern"`).
2. Add the server to the `servers` section.
3. Configure runtime proxy auth with `GROUNDS_PROXY_MODE=velocity`.
4. Set `GROUNDS_VELOCITY_FORWARDING_SECRET` to the content of Velocity's `forwarding.secret`.

Launch the lobby group with shared chat enabled:

```shell
# Lobby group behind Velocity
CHAT_GLOBAL_ENABLED=true CHAT_GROUP=lobby ./gradlew run
```

### Chat, reporting, and mute enforcement

The chat provider is always selected. `CHAT_GLOBAL_ENABLED=true` forwards chat to Velocity;
`CHAT_GROUP` optionally scopes that global chat to a named group and requires global chat to be
enabled. The Minestom report menu and local-delivery observations use the player's existing proxy
connection, so the lobby and Velocity must run compatible `plugin-chat` releases for reporting.

Global and grouped chat already cross the proxy's mute gate. If a Velocity-backed lobby deliberately
uses local chat, also set `MODERATION_ENFORCEMENT_ENABLED=true`; this forwards server-scoped chat to
Velocity so active mutes cannot be bypassed. Leave that setting disabled for a standalone lobby,
which has no proxy to receive forwarded messages.

Native player heads require the trusted forwarded Java-edition marker and a compatible protocol.
Font graphics additionally require the proxy to confirm an approved chat-font pack and send that
capability to the current backend connection. Missing, stale, or invalid state stays text-only, so
the lobby does not require graphics to remain readable.

### Permissions runtime

Set both `PERMISSIONS_SERVICE_URL` and `PERMISSIONS_TOKEN_FILE` to enable the
REST permissions provider. In Kubernetes, Forge and the `grounds-gamemode`
chart supply these values together with the projected workload token. Leaving
both unset disables the provider; partial configuration fails startup.

### LOD entitlement

The optional LSS terrain delivery requires `grounds.lod.receive` from the current
Permissions snapshot. Assign this node to the desired Premium role; its name alone
never grants access. Missing, expired or unavailable permissions deny new LOD delivery.
The server checks again before queued terrain is serialized, so revocation stops further
delivery and a later grant works in the same session. Previously downloaded or explored
Voxy terrain remains in the client cache. Ordinary Lobby access is unaffected.
The node is declared in the existing permission catalog with global, server type and
server scopes. The finite Stage run and player allowlist remain additional requirements.

For a current map, configure `GROUNDS_LOD_PILOT_MAP_ADDRESS` and
`GROUNDS_LOD_PILOT_MAP_VERSION` together. The loaded map and exact source hashes must match;
without either key, the historical `lobby/mainlobby` version 13 selection is retained.

### Notifications runtime

Notifications require the permissions provider above. Set `NOTIFICATIONS_SERVICE_URL`,
`NOTIFICATIONS_CHANNEL_TOKEN`, `NOTIFICATIONS_SERVER_ID`, and an HTTPS `PORTAL_BASE_URL` together to
enable them. The URL/server configuration must be complete; a missing channel token keeps the
provider disabled. `NOTIFICATIONS_MODERATION_PERMISSION` defaults to
`grounds.moderation.notifications.receive`, and `NOTIFICATIONS_MODERATION_ENVIRONMENT` may restrict
moderation notifications to `stage` or `prod`. The host uses Notifications 0.3.0 and the shared
recipient-aware system renderer.

### Analytics

The lobby publishes what players do on it (arrivals, deaths, chat volume, commands) with
`plugin-analytics-minestom`, stamped `minigame_id=lobby`. It is on by default and needs no
backend: it publishes to `NATS_URL` (default `nats://nats.nats.svc.cluster.local:4222`) and
becomes a no-op if NATS is unreachable. The event's environment comes from
`GROUNDS_ENVIRONMENT` or `GROUNDS_PERMISSION_ENVIRONMENT`, the region from `REGION`, the server
from `HOSTNAME`. `ANALYTICS_ENABLED=false` keeps the module out entirely.

### As standalone

Use `GROUNDS_PROXY_MODE=auto` with `GROUNDS_ONLINE_MODE=true` to run a standalone online-mode lobby.

```shell
# Standalone local chat
CHAT_GLOBAL_ENABLED=false CHAT_GROUP= ./gradlew run
```

## License

Licensed under the GNU Affero General Public License v3.0

## Where the world comes from

By default the lobby loads the world baked into its image (`GROUNDS_LOBBY_MAP_PATH`, or `lobby/`
next to the working directory).

Set **`GROUNDS_LOBBY_MAP`** to a map address — `lobby/mainlobby` — and it instead loads the version
pinned for its environment:

| Variable            | Meaning                                                            |
| ------------------- | ------------------------------------------------------------------ |
| `GROUNDS_LOBBY_MAP` | Map address to load. Unset keeps the baked-in world                |
| `MAPS_ENVIRONMENT`  | Which pin file to read. Defaults to `stage`                        |
| `MAPS_CDN_BASE`     | CDN origin for the pin file. Defaults to `https://maps.grounds.gg` |
| `MAPS_CACHE_DIR`    | Where unpacked worlds are cached, keyed by digest                  |

**The map service is never called.** It publishes `pins/<env>.json` to the CDN and that file names
the content-addressed bundle, so a lobby boots and loads its world with the registry down. Bundles
are immutable and cached under their own digest, so a restart that changes nothing downloads
nothing.

If anything fails — no pin, no network, a broken bundle — the lobby **starts on the world it
shipped with** and says so in the log. An empty lobby is worse than a slightly old one.

The spawn comes from `grounds/pois.json` inside the world, which is what a builder marked with
`/ms spawn` on the build server. A world published before points existed falls back to the map
template's first spawn.

## Versioned lobby scene

The selected map may include a root `scene.json` alongside its world data. Only this root sidecar
is considered, never a nested `scene.json`. No sidecar preserves immediate lobby spawning.
A selected sidecar that is malformed, larger than 16 MiB, a symlink/nonregular file, or unsupported
is fatal to startup; it is not silently ignored and does not trigger another map fallback. Reading
is bounded to 16 MiB plus one overflow byte and does not follow the sidecar symlink.

Scene identity uses the same loaded map as the world: published map address/version are preserved,
including when two pins share cached bytes. Local maps use `local:<absolute-normalized-map-root>`
and map version `0`.

The host currently supports these exact catalogs/capabilities:

- `gg.grounds:resourcepacks-catalog:0.6.0`: `grounds:assets` version `0.6.0`, with
  `grounds:editor/guide` (NPC body) and `grounds:editor/marker` (prop).
- `gg.grounds:plugin-lobby-scene-catalog:1.14.0`: `grounds:actions` version `1` (empty legacy
  catalog) or version `2`, which adds the parameterless `grounds:lobby/open_navigator` action.
- `gg.grounds:plugin-lobby-minestom:1.14.0` supplies the installed navigator service. It is required
  by a scene only when that scene references the navigator action. The action rejects arguments,
  disconnected players, and players outside the owning instance. Permission conditions consult
  the installed permissions service; an absent service denies permissions.
- `gg.grounds:scene-minestom:0.2.1` provides the scene runtime. Placeholder rendering supports
  authored transforms, including root/local scale, and private viewer highlights. Viewer-scale
  actions, animations, sound, and particle effects are rejected in preflight. This is not a
  general resource-pack/model renderer.

Catalog and capability preflight happens before lobby commands/listeners are registered and does
not create renderer entities. Startup logs include scene ID, map identity/version, and catalog
versions; invalid sidecars report their path and validation diagnostics. Runtime creation begins
from module start, after ticks begin. A runtime creation failure denies admission and requests
server stop from a separate lifecycle thread.

For a selected scene, player configuration waits at most 30 seconds on its configuration virtual
thread, then checks both connection state and whether the host has closed. Failure, disconnect,
or shutdown assigns no spawning instance. This application admission gate is separate from
Agones readiness. Shutdown closes the gate immediately and waits for pending renderer/NPC
attachment ownership to drain before claiming scene cleanup and beginning reverse-order provider
teardown. A 30-second
cleanup timeout or failure is logged and thrown, not treated as successful cleanup.

The lobby bootstrap disables Minestom's independent JVM signal hook before configuration/provider
discovery can initialize `ServerFlag`. Grounds owns signal-driven shutdown so its module cleanup
runs while instance ticks remain alive, before it stops Minestom. Custom launchers must enter the
lobby bootstrap first; startup fails if another launcher already cached the competing hook flag.

### Private Stage NPC distribution

Public Maven NPC artifacts deliberately contain no purchased model data. An ordinary lobby build
therefore cannot run the Stage NPC demo. Build the private variant from a clean Resourcepacks
checkout and the licensed source archives, using the exact accepted composite policy and addon.
The host checkout must also be clean:

```shell
python3 tools/build_npc_distribution.py \
  --resourcepacks /private/resourcepacks --resourcepacks-commit FULL_COMMIT_SHA \
  --catalog-build-number UNIQUE_POSITIVE_BUILD_NUMBER \
  --lobby-source /private/plugin-lobby --lobby-source-commit EXACT_ACCEPTED_LOBBY_COMMIT \
  --sources /private/licensed-npcs --composite-policy /private/qualified-policy.json \
  --addon /private/npc-addon.zip --addon-sha256 EXACT_SHA256 --addon-sha1 EXACT_SHA1 \
  --map /private/selected-stage-map --output /private/new-distribution
```

Requires Python 3.12+, Git, Java 25 and the usual Gradle Maven credentials. `--offline` uses
existing dependency caches; `--gradle` and `--java` accept explicit executable paths. The map's
scene must select `0.0.0-edge.<build-number>.g<first-12-source-commit-characters>`; the tool never
rewrites a map. Allocate a fresh build number for the private catalog (for example, a recorded
UTC timestamp) and keep it in the receipt. The generated definitions must not reuse a public
neutral catalog's version or overwrite a canonical public PackSet build path.
The acceptance probe targets the current 15-model, 22-element Stage scene and preserves its
announcement ownership contract. Until that already accepted implementation is released,
pin its clean Lobby source checkout with both `--lobby-source` and `--lobby-source-commit`.

The tool exports committed host and Resourcepacks sources into fresh, private temporary
directories and snapshots the licensed archives, policy, addon and scene. This avoids shared
composite-build outputs and records all archive hashes. It runs a clean host build and the
entire host test suite. Only `resourcepacks-catalog` and
`resourcepacks-npc-runtime` use source substitution by default; an explicit pinned Lobby source
also substitutes its two modules. Scene and Chat use their published pinned versions.
Private host outputs live under the snapshot's `build/private-npc`, separate from ordinary builds.
After checking ZIP completeness and hashes, the final executable JAR loads the supplied scene
through the production loader and verifies the addon file contract. Only then is the new output
directory created, with `minestom-lobby.jar`, `npc-addon.zip`, the standalone neutral `catalog.jar`,
logs and `receipt.json`. The receipt pins the catalog coordinate, version, size and SHA-256. Failed
attempts keep their temporary logs and produce no qualification receipt.

The JAR contains the addon SHA-1 binding. When the Stage demo is enabled, startup rejects a
missing binding, missing models or a different `GROUNDS_NPC_ADDON_SHA1` before creating the world.
The addon remains an additive required pack; proxy base-pack delivery is unchanged. No network
requests are added to login or scene loading.

Keep the complete distribution and all inputs private. This command does not publish Maven,
upload a client pack, deploy an image or activate a map. Its receipt covers server-side build and
asset contracts; native client acceptance, real multi-proxy routing and deployment remain
separate checks.

### Generate a review fixture (test-only)

Supply both positions explicitly; these example coordinates are not derived from any map or spawn:

```shell
./gradlew generateLobbySceneFixture \
  -PsceneFixtureName=lobby-scene-review.json \
  -PsceneNpc=12,64.5,-7 \
  -PsceneMarker=16,65,3
```

This writes only a **new** `build/fixtures/lobby-scene-review.json`, validates it against the real
pinned catalogs, and refuses existing destinations, path-like filenames, and symlinked output
directories. It does not load a map or modify a live sidecar. The typed fixture contains the
`Lobby-Navigator` guide, hover enter/leave highlights, a main-hand right-click navigator action
with a 500 ms cooldown, and a marker prop. Review placement before any separately authorized map
publication. The generator lives exclusively in test sources and is absent from production JARs;
no scene testkit is a production dependency.

### LOD delivery measurements

Optional LOD delivery uses the existing runtime metrics endpoint. The counters
`grounds.lod.delivery.serialized.body.bytes` (`kind=terrain|control`) and
`grounds.lod.delivery.serialized.frames` advance after the actual writer consumes
the frame and its final cancellation state is known. Buffer retries and cancelled
frames do not add completed bodies. These are application bytes, excluding channel
registration, Minecraft framing, compression, TLS, TCP and remote acknowledgement.
`grounds.lod.delivery.authorized.body.bytes` counts earlier authorization, while
`grounds.lod.delivery.discarded.frames` counts rejected writer attempts including
retries. Shared server reservations remain conservative and are never refunded.
The queue gauge is `grounds.lod.delivery.queued.frames`. No player, map hash or
version is a metric label. Retiring the adapter removes its meters.
