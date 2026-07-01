# Minestom Lobby

This is a simple lobby built on Minestom.
 
Following features are implemented:

- Official skins are loaded
- Works with velocity and as standalone
- Configurable address and port through `grounds-minestom-runtime`
- Server startup is delegated to `grounds-minestom-runtime`
- Auth and profile forwarding are configured through `grounds-minestom-runtime`

## Running 

Use `GROUNDS_BIND_HOST` and `GROUNDS_BIND_PORT` to configure the bind address.

### Behind Velocity

1. In Velocity, got to the `velocity.toml` and change `player-info-forwarding-mode` to `modern`.
(Example: `player-info-forwarding-mode = "modern"`).
2. Add the server to the `servers` section.
3. Configure runtime proxy auth with `GROUNDS_PROXY_MODE=velocity`.
4. Set `GROUNDS_VELOCITY_FORWARDING_SECRET` to the content of Velocity's `forwarding.secret`.

Use `gradle run` to run the server.

### As standalone

Use `GROUNDS_PROXY_MODE=auto` with `GROUNDS_ONLINE_MODE=true` to run a standalone online-mode lobby.

## License

Licensed under the GNU Affero General Public License v3.0
