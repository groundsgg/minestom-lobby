# Minestom Lobby

This is a simple lobby built on Minestom.
 
Following features are implemented:

- Official skins are loaded
- Works with velocity and as standalone
- Configurable address, port 

## Running 

Run `run --args="--help"` to print all available options.

### Behind Velocity

By default, the lobby will run behind Velocity.
This means, the server will run on 0.0.0.0:30066 (a non-standard Minecraft port), to not conflict with Velocity.

1. In Velocity, got to the `velocity.toml` and change `player-info-forwarding-mode` to `modern`.
(Example: `player-info-forwarding-mode = "modern"`).
2. Add the server to the `servers` section.
3. Add an env variable named `GROUNDS_LOBBY_VELOCITY_SECRET` and add the content of `forwarding.secret`

Use `gradle run` to run the server or use the IntelliJ run configuration `Run behind Velocity`

### As standalone

Use `gradle run --args="--auth no_proxy"` to rand as standalone or use the IntelliJ run configuration `Run standalone` 