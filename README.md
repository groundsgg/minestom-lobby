# Minestom Lobby

The Grounds lobby application, implemented in Kotlin on Minestom with the Grounds runtime. It composes map loading, Scene/NPC rendering, Dialogue, the navigator, chat and optional service providers. See [release history](CHANGELOG.md) and [dependency pins](build.gradle.kts).

## Start here

Contributors need **JDK 25**, the checked-in Gradle wrapper and GitHub package-read credentials for Grounds build plugins and runtime dependencies. The repository is source-visible; it does **not** currently provide an anonymous build path. A fresh anonymous checkout fails during settings evaluation because `github.user` is absent; supplying placeholder credentials still cannot resolve the Grounds convention plugin. See [Contributing](CONTRIBUTING.md#dependency-access) for the exact boundary.

Keep credentials in your user Gradle properties, outside the checkout:

```properties
github.user=YOUR_GITHUB_USERNAME
github.token=YOUR_PACKAGE_READ_TOKEN
```

Build before starting the application:

```sh
./gradlew --no-parallel --max-workers=1 test
./gradlew --no-parallel --max-workers=1 spotlessApply
./gradlew --no-parallel --max-workers=1 build
```

For a local standalone online-mode lobby, use a neutral map directory without a Stage-only scene or licensed NPC requirements:

```sh
GROUNDS_PROXY_MODE=auto GROUNDS_ONLINE_MODE=true \
  CHAT_GLOBAL_ENABLED=false CHAT_GROUP= ANALYTICS_ENABLED=false \
  GROUNDS_LOBBY_MAP_PATH=/absolute/path/to/your/neutral-world \
  ./gradlew --no-parallel --max-workers=1 run
```

`GROUNDS_BIND_HOST`, `GROUNDS_BIND_PORT` and `GROUNDS_SERVER_BRAND` configure the runtime address and brand. Leave Agones, permissions, notifications, voice, LOD and Stage demo inputs unset for this basic path. This path still requires build artifact access and a suitable local world; it is not a credential-free public SDK demo. The [Dialogue SDK's neutral standalone example](https://github.com/groundsgg/library-dialogue/tree/main/examples/standalone-minestom) serves that separate use case.

Behind Velocity, configure modern forwarding and the matching secret, then enable the desired shared chat behavior. See [operations](docs/operations.md#behind-velocity) before connecting players. Shared chat, reports and routing use network services/proxy delivery; remote players must not be assumed local.

## Find the right entry point

| Goal                                                 | Entry point                                                                                     |
| ---------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Build, test or contribute                            | [Contributing](CONTRIBUTING.md)                                                                 |
| Understand current packages and lifecycle            | [Architecture](docs/architecture.md)                                                            |
| Configure world, proxy, chat or service providers    | [Operations](docs/operations.md)                                                                |
| Author or qualify a scene / private NPC distribution | [Scene and asset operations](docs/operations.md#versioned-lobby-scene)                          |
| Configure ordinary LOD serving                       | [LOD serving](docs/lod-serving.md)                                                              |
| Inspect the older finite pilot                       | [LOD pilot](docs/lod-pilot.md)                                                                  |
| Inspect historical decisions and results             | [Historical plans](docs/superpowers/plans), [historical specifications](docs/superpowers/specs) |

Licensed NPC archives stay external to Git and public artifacts. Private distribution qualification, native-client acceptance, real multi-proxy transfers and deployment are separate checks; a successful build does not activate a map or publish a pack.

## License

Licensed under the [GNU Affero General Public License v3.0](LICENSE).
