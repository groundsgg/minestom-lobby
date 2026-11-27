# Contributing to Minestom Lobby

Use JDK 25 and the checked-in Gradle wrapper. Implementation and tests are Kotlin. Read the [architecture](docs/architecture.md) before choosing a change location and [operations](docs/operations.md) for runtime and asset configuration.

## Dependency access

The build requires `github.user` and `github.token` Gradle properties during settings/plugin repository configuration. Put them in your user `~/.gradle/gradle.properties`, or supply them through your CI secret integration. The token needs package-read access to the selected Grounds artifacts and any organization/package authorization required by your account. Repository visibility alone does not guarantee artifact access. Do not put credentials in the checkout or command logs.

A fresh external-style check on 2026-11-26 used an empty user home and Gradle home, with no environment credentials or source substitutions:

```sh
./gradlew --no-parallel --max-workers=1 help
```

It failed in `settings.gradle.kts:9`: `Cannot query the value of Gradle property 'github.user' because it has no value available.` With non-secret placeholder properties, the next boundary is resolution of `gg.grounds.root:0.1.1` from the configured plugin repositories. No anonymous dependency closure or neutral application launch is claimed. Once settings/plugins resolve for your identity, remaining dependencies include the Grounds Minestom fork, runtime/providers, Scene/NPC/Dialogue and resourcepack catalogs/runtime; see [build.gradle.kts](build.gradle.kts). `help` success alone does not prove these application artifacts resolve.

The build includes a plugin `mavenLocal()` repository and explicit optional local-review/private-build overrides. A successful build on a developer's populated caches is not a clean remote-access proof. Do not silently replace published artifacts with neighboring source builds or new mirrors to work around access failures.

## Build, test and format

From a fresh checkout, with `JAVA_HOME` set to JDK 25 and credentials configured:

```sh
./gradlew --no-parallel --max-workers=1 test
./gradlew --no-parallel --max-workers=1 spotlessApply
./gradlew --no-parallel --max-workers=1 build
```

After code changes, run all three commands and inspect their results. Format changed Markdown with Prettier when available (`prettier --write PATH`, then `prettier --check PATH`). Docs-only changes require relevant command, link and Markdown checks; no new runtime acceptance is implied.

Choose meaningful existing test seams: `LobbyRuntimeTest` checks provider selection; `LobbyModuleLifecycleTest` and `LobbySceneHostTest` cover admission and teardown; dialogue and Guide speech tests cover locale, action dispatch, virtual timing and late resources. Tests use synthetic/neutral fixtures where possible. The [test-only scene generator](docs/operations.md#generate-a-review-fixture-test-only) requires explicit coordinates and writes a new file under `build/fixtures`; it never modifies a live map.

## Local runtime and content

The [README](README.md#start-here) gives a standalone neutral-world invocation after build access is available. Basic local use does not require Agones, permissions, notifications, voice or licensed NPC content. Map and provider inputs still have to match the selected features: incomplete configuration is deliberately rejected. Authenticated online mode may require its normal external authentication access. A scene that requires unavailable models is not a neutral fixture.

For private Stage NPC content, follow the existing [distribution procedure](docs/operations.md#private-stage-npc-distribution). Keep purchased sources, generated bundles and qualification policy inputs external. Do not publish licensed assets, reuse immutable catalog versions for changed bytes or treat a private build receipt as client/proxy/deployment acceptance.

## Changes and review

Use Conventional Commits, keep contributions scoped and preserve unrelated work. The current package layout is the root lobby package plus `scene`; proposed feature folders and Draft authored-dialogue work are not current APIs. Reuse the published Scene/NPC/Dialogue engines and installed runtime providers instead of adding competing generic systems. Preserve startup preflight, connection/generation fencing, multi-proxy delivery and cleanup ordering. Logs must be factual English outcomes with relevant non-sensitive context. Record checks honestly, including artifact-access blockers and untested native/client behavior.
