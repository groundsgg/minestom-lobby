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
