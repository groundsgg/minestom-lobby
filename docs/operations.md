# Lobby operations and scene integration

Current host operations. Start with the [README](../README.md) for local setup and
[Contributing](../CONTRIBUTING.md) for dependency access and verification.

## Running

Use `GROUNDS_BIND_HOST`, `GROUNDS_BIND_PORT`, and `GROUNDS_SERVER_BRAND` to configure the server.

### Behind Velocity

1. In Velocity, go to the `velocity.toml` and change `player-info-forwarding-mode` to `modern`.
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
moderation notifications to `stage` or `prod`. The host uses Notifications 0.4.0 and the shared
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

The authoritative dependency pins are in [build.gradle.kts](../build.gradle.kts):

- `resourcepacks-catalog:1.3.0` supplies the neutral asset catalog; private distributions may select an explicitly verified immutable replacement.
- `plugin-lobby-scene-catalog:2.25.0` supplies lobby action capabilities. `plugin-lobby-minestom:2.24.0` installs the navigator and queue services used by host action adapters.
- Scene `1.0.1` and NPC `2.0.0` provide shared rendering. Preflight checks each selected asset, animation, viewer-scale operation, sound and particle against the installed catalogs/renderers. These features are supported when advertised by those providers; they are not universally rejected.
- Navigator actions reject invalid arguments, disconnected players and players outside the owning instance. Permission conditions consult the installed service; absence denies permissions.

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
  --npc-importer-classpath /private/npc-cli-VERSION-all.jar \
  --npc-version MATCHING_LIBRARY_VERSION \
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
entire host test suite. Only `resourcepacks-catalog` uses source substitution by default; an explicit pinned Lobby source
also substitutes its two modules. Scene and Chat use their published pinned versions.
Private host outputs live under the snapshot's `build/private-npc`, separate from ordinary builds.
After checking ZIP completeness and hashes, the final executable JAR loads the supplied scene
through the production loader and verifies the addon file contract. Only then is the new output
directory created, with `minestom-lobby.jar`, `server-content.zip`, `npc-addon.zip`, the standalone neutral `catalog.jar`,
logs and `receipt.json`. The receipt pins the catalog coordinate, version, size and SHA-256. Failed
attempts keep their temporary logs and produce no qualification receipt.

Add `--dialogue-profile-scene /private/selected-dialogue-scene` to the command above to
export a private Dialogue profile from an explicitly authored scene directory containing
`scene.json`. This input is snapshotted separately from `--map`: the existing Stage acceptance
scene remains required and unchanged. The selected dialogue scene must resolve to exactly one
scene-bound dialogue with role bodies backed by the loaded private models. Built-in Guide and
Stage IDs retain their existing defaults. For an authored entry ID, explicitly add
`--dialogue-host-contract grounds:lobby-guide`; the builder forwards this trusted choice as
exporter `--host-contract grounds:lobby-guide`. Story IDs never select providers. The Guide
contract requires one primary NPC start and the `guide` role; Stage remains built-in only.

After standalone and embedded catalog verification, the exact distribution JAR runs
`gg.grounds.minestom.lobby.scene.LobbyDialogueProfileExport` in a sanitized child environment.
It installs the real Lobby module and creates the same Scene composition and Dialogue owner
used at runtime in explicit metadata preparation mode, without starting a listener or invoking menus.
This mode holds admission closed, contains no validated graph and cannot open a session. The JAR must contain matching
addon SHA-1 and content-ID resources. The distribution additionally retains:

- `dialogue/profile.json`, with the E1 profile's eight fields.
- `dialogue/context/host-catalog.json`, byte-identical to the embedded host JSON.
- `dialogue/scene.json`, the exact selected dialogue scene snapshot.
- `dialogue/binding.json`, the manifest and artifact pins.
- `dialogue-export.log`, the isolated exporter process output.

Profile version and host generation are the same SHA-256 of the canonical manifest. The
manifest binds provider identity, service variant, dialogue/role/provider/catalog metadata,
validated content ID, and the exact executable, content, addon, catalog and scene hashes/sizes.
It excludes paths, timestamps, random session/world IDs and output digests. Authored graphs/texts,
`grounds/dialogue.json`, author host snapshots and generated profile outputs are not generation
inputs. Export the profile first, then author the binding from its exact identity and host bytes. Guide profiles retain
the two installed menu providers; Stage profiles have no action providers. Both keep empty
gesture, condition and capability claims and disable durable outcomes.

The builder independently checks bounded, duplicate-free JSON, canonical generation, every
artifact pin, actual bundle content ID and embedded host bytes. It repeats verification after
cleanup, immediately before writing `receipt.json` and atomically renaming the output directory.
Only successful opt-in builds add `dialogueProfile` to the receipt, with identity and
profile/host/binding pins. Export or verification failures retain attempt logs and produce no
qualified receipt or final distribution. Omitting the option preserves the existing build path.
These hashes provide artifact integrity, not receipt authentication or runtime Story admission;
native client acceptance and cross-proxy behavior remain separate checks.

### Optional Dialogue startup verification

Mount the complete qualified private distribution read-only at an absolute path, for example
`/private/lobby-distribution`, and configure both:

- `GROUNDS_DIALOGUE_DISTRIBUTION=/private/lobby-distribution`
- `GROUNDS_DIALOGUE_BINDING_SHA256=<sha256 of dialogue/binding.json>`

Supply the expected digest through trusted deployment configuration. Run the distribution's
exact executable JAR, set `GROUNDS_NPC_CONTENT` to its `server-content.zip`, and select the map
whose raw `scene.json` matches `dialogue/scene.json`. Keep the seven pinned artifacts in their
exported relative paths. The runtime checks the actual installed menu services, loaded content
snapshot, catalogs, provider identities and final scene composition against the binding.

Configured verification requires executable, catalog and provider classes and their binding
resources to originate from the same qualified fat JAR. External catalog or binding-resource
classpath arrangements remain supported for unconfigured startup only. Stage preview and native
scene transformations cannot qualify. Ordinary Docker packaging does not add private artifacts;
mount and configure this distribution explicitly.

Configured verification currently requires Linux with readable `/proc/self/fd` and the standard
JVM application classloader. Before opening any verification archives, it requires exactly one
already-open executable archive handle, checks its inode and pinned bytes, and checks again before
admission. Missing, ambiguous or replaced handles deny Dialogue admission. Launch using the exact
mounted executable path; custom classloaders and archive launch aliases are not qualified.
Keep the executable mounted read-only and never modify it in place during the JVM's lifetime:
open-handle checks detect atomic pathname replacement, but cannot reconstruct already-loaded
class bytes after an in-place rewrite. Unconfigured startup retains its existing platform behavior.
NPC ZIP snapshots load in bounded memory and do not require writable temporary storage.

With neither variable present, existing startup behavior is preserved. Either variable being
present enables verification, including blank, incomplete or malformed configuration. Dialogue
admission remains closed until verification succeeds. A verification denial rejects scene and
direct owner starts before creating sessions or UI, logs one contextual startup outcome, and
preserves spawn admission, commands, unrelated scene actions and normal cleanup. Existing world,
scene decoding and runtime creation failures retain their fatal handling. The immutable decision
applies for this startup only; replacing content or re-pinning a map requires a new Lobby runtime.
Authored Guide Stories use complete map sidecars: `dialogues/*.json`, canonical
`texts/<locale>.json`, `context/host-catalog.json` and `grounds/dialogue.json`. The operator must
prepare the exact distribution profile and binding SHA first; author bindings only map the
`guide` role to the single Scene NPC and name that anchored profile. They never select providers.
The host captures the catalogue once, validates it with Dialogue JSON 1.8 and the actual menu
services and runtime generation, then checks the unchanged inventory and final distribution
before admission. Missing, partial, malformed or mismatched authored inputs fail Story-map
installation before spawn commands or admission listeners register. A configured legacy built-in
profile denial still disables only dialogues. A present Story catalogue requires a Scene and
cannot qualify a Stage preview or native demo transformation.

Authored sessions use captured graph texts, their declared supported locales and graph default.
Canonical requested locale tags survive live preference changes; an unsupported tag resolves
through that graph's default without borrowing another graph's translations. Refresh preserves
conversation progress. Guide presentation, voice assets and volume remain the existing host
contract behavior, with chat fallback when the pack is unavailable. This does not change Maps
runtime availability or publish a distribution.

The JAR contains the addon SHA-1 and content-ID bindings. Set
`GROUNDS_NPC_CONTENT=/absolute/path/server-content.zip` (or `-Dgrounds.npc.content=...`)
when starting the private host. The file is loaded once per server run and must match
the built distribution. No path means neutral content; a configured missing, invalid or
wrong-target file fails startup, and a scene requiring unavailable models is rejected.
Restart to replace content. The receipt also pins the shared importer JARs. When the Stage demo is enabled, startup rejects a
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

Ordinary Stage LOD serving, permission checks and its server-only configuration are documented in
[LOD serving](lod-serving.md). The older finite pilot remains separately opt-in.

### Shared NPC engine integration

NPC rendering delegates to `groundsgg/library-npc` through `scene-npc-minestom`.
The host validates the external bundle, exact Grounds catalogue bounds/animations/sounds,
and `grounds:npc_parts/` mesh references. The executable contains no purchased model JSON
and the verifier rejects embedded legacy engine copies.

Scene uses released `1.0.1`. The sound-admission adapter uses NPC `2.0.0`,
as pinned by the current build.
`-PnpcVersion=... -PnpcRepository=file:///...` selects exact local NPC Maven candidates;
Scene continues resolving from its published repository. New importer/CLI modules are
build tooling and are not bundled into the host. `resourcepacks-npc-content` is no longer
a required dependency. No empty Maven content artifact or PackSet release is needed.

The product adapter preserves qualified rigid/composite geometry, archive naming,
original sound metadata and Scene catalog generation. Its eligibility identity includes
both adapter sources and every selected importer classpath JAR; requalify the policy after
changing any of these. The private build snapshots these JARs and records their hashes.
Use the addon generated by the same build: resource IDs deliberately isolate client content.

Private distributions require committed, pinned inputs. The builder runs the complete
host suite and the actual `NpcContentLoader` plus production Scene loader against the
external bundle and selected pack. Native client acceptance and real proxy/server transfers
remain separate product checks. Building does not activate a map, deploy a server or advance a CDN.

When the NPC asset definitions are unchanged, a private build can keep an existing
immutable neutral catalogue using `--catalog-jar /private/catalog.jar` and
`--catalog-sha256 EXACT_SHA256`. The builder snapshots and verifies the original
bytes, builds against that catalogue and validates the complete selected scene
through the production loader. The receipt preserves its existing version and
digest. Model compilation still uses a fresh build identity; it never regenerates
new catalogue bytes under the existing version. This lets a library or model
packaging update retain a compatible published map without changing its pin.

Ordinary CI images keep licensed content external. At startup a read-only content
volume can provide `server-content.zip`, the selected immutable neutral catalogue
JAR and the exact `npc-addon-sha1.txt` / `npc-content-id.txt` binding resources.
Use an explicit Java classpath with the catalogue and binding directory before
the executable JAR, and set `GROUNDS_NPC_CONTENT` to the mounted content bundle.
Select the same catalogue/version as the published map and verify the complete
scene and addon contract before activating the deployment. Keep private model
files outside Git and public image layers.
