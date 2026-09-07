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

### Permissions runtime

Set both `PERMISSIONS_SERVICE_URL` and `PERMISSIONS_TOKEN_FILE` to enable the
REST permissions provider. In Kubernetes, Forge and the `grounds-gamemode`
chart supply these values together with the projected workload token. Leaving
both unset disables the provider; partial configuration fails startup.

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
