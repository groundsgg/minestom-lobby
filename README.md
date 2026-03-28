# Minestom Lobby

This is a simple lobby built on Minestom.
 
Following features are implemented:

- Official skins are loaded
- Works with velocity and as standalone
- Configurable address, port 
- Dynamic map loading without restarting

### Dev Mode

To enable the dev mode, use the `--dev` flag.
The dev mode registers additional commands and sets the spawning gamemode to Creative.

### Loading a world

To load a new map, the chunks of the current instance are unloaded. 
This in turn kicks the player. 
Therefore, all players are kicked before a chunk is unloaded.
This also has the advantage that a player needs to rejoin and thus spawns at the new position.

In theory, it is also possible to unload the chunk while the player is in there.
So it would be possible to reload the map dynamically while players are still connected.
Although this could cause a lot of network traffic, if new chunks have to be sent to all players again.

## Commands

`/loadWorld <name>` - Loads a world with name from the `worlds` folder.
If a name contains spaces, surround the name by `"` (example `/loadWorld "Hello World"`).
This command only exist in dev mode (enabled by setting the `--dev` flag)

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

## Building the docker image

```
docker build --build-arg GITHUB_USER="your_github_username" --secret id=github_token,src=$HOME/.token.txt -t minestom-lobby . 
```

## License

Licensed under the GNU Affero General Public License v3.0
