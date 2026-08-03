# Minestom Lobby

This is a simple lobby built on Minestom.
 
Following features are implemented:

- Official skins are loaded
- Works with velocity and as standalone
- Configurable address and port through `grounds-minestom-runtime`
- Server startup is delegated to `grounds-minestom-runtime`
- Auth and profile forwarding are configured through `grounds-minestom-runtime`

## Running 

Use `GROUNDS_BIND_HOST`, `GROUNDS_BIND_PORT`, and `GROUNDS_SERVER_BRAND` to configure the server.

### Behind Velocity

1. In Velocity, got to the `velocity.toml` and change `player-info-forwarding-mode` to `modern`.
(Example: `player-info-forwarding-mode = "modern"`).
2. Add the server to the `servers` section.
3. Configure runtime proxy auth with `GROUNDS_PROXY_MODE=velocity`.
4. Set `GROUNDS_VELOCITY_FORWARDING_SECRET` to the content of Velocity's `forwarding.secret`.

Use `./gradlew run` to run the server.

### Permissions runtime

Set both `PERMISSIONS_SERVICE_URL` and `PERMISSIONS_TOKEN_FILE` to enable the
REST permissions provider. In Kubernetes, Forge and the `grounds-gamemode`
chart supply these values together with the projected workload token. Leaving
both unset disables the provider; partial configuration fails startup.

### As standalone

Use `GROUNDS_PROXY_MODE=auto` with `GROUNDS_ONLINE_MODE=true` to run a standalone online-mode lobby.

## License

Licensed under the GNU Affero General Public License v3.0

## Where the world comes from

By default the lobby loads the world baked into its image (`GROUNDS_LOBBY_MAP_PATH`, or `lobby/`
next to the working directory).

Set **`GROUNDS_LOBBY_MAP`** to a map address — `lobby/mainlobby` — and it instead loads the version
pinned for its environment:

| Variable | Meaning |
|---|---|
| `GROUNDS_LOBBY_MAP` | Map address to load. Unset keeps the baked-in world |
| `MAPS_ENVIRONMENT` | Which pin file to read. Defaults to `stage` |
| `MAPS_CDN_BASE` | CDN origin for the pin file. Defaults to `https://maps.grounds.gg` |
| `MAPS_CACHE_DIR` | Where unpacked worlds are cached, keyed by digest |

**The map service is never called.** It publishes `pins/<env>.json` to the CDN and that file names
the content-addressed bundle, so a lobby boots and loads its world with the registry down. Bundles
are immutable and cached under their own digest, so a restart that changes nothing downloads
nothing.

If anything fails — no pin, no network, a broken bundle — the lobby **starts on the world it
shipped with** and says so in the log. An empty lobby is worse than a slightly old one.

The spawn comes from `grounds/pois.json` inside the world, which is what a builder marked with
`/ms spawn` on the build server. A world published before points existed falls back to the map
template's first spawn.
