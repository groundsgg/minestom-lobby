# Built-in dialogue authoring

The Lobby Guide and Stage mechanic are standard Dialogue SDK 1.8.0 projects. Edit their JSON files here. Both projects are packaged with the server artifact and checked by the normal Gradle test/build workflow, including when Stage dialogue is disabled. Publishing a content edit requires a new server build and runtime; this increment provides no hot reload. Existing sessions keep their validated content snapshots.

Each project contains:

- `dialogues/*.json`: SDK graphs, stable dialogue/node/choice IDs, ordered choices, locale policy and content version.
- `texts/<locale>.json`: flat maps of plain text keys to strings. Use canonical BCP 47 tags such as `en`, `de` or `fr-CA`.
- `context/host-catalog.json`: author context describing the actual host contract.
- `scenarios/*.md`: translator and conversation context, excluded from the runtime payload.

Keep these projects separate: their short text keys overlap and their catalogues have independent content pins. The source JSON is packaged byte for byte in reproducible ZIP resources. Every participating file is discovered from the project; adding or removing a locale needs no Kotlin locale list.

## Editing and validation

Keep IDs and text keys stable when changing wording. Preserve the order of choices. Every message and choice key must have a translation in every locale declared by its graph. To add a language, create its flat text JSON and add its canonical tag to `locales.supported` in the graph. Set `locales.default` deliberately. SDK runtime fallback is separate from the required translation checks.

Text is plain text, with no MiniMessage markup or system prefix. The host currently supplies no interpolation arguments: `{player}` fails packaged validation. Use `{{player}}` to display the literal `{player}`. Optional `speaker.guide` display labels are also checked when present in the resolver's fallback chain.

Use JDK 25 and the **1.8.0** standalone Dialogue SDK CLI distribution. Unpack `dialogue-cli-1.8.0.zip`. After unpacking it, run its `bin/dialogue` executable from this repository (replace the example installation path):

```sh
/path/to/dialogue-cli-1.8.0/bin/dialogue --version
/path/to/dialogue-cli-1.8.0/bin/dialogue validate --root dialogue-content/lobby-guide --format json
/path/to/dialogue-cli-1.8.0/bin/dialogue validate --root dialogue-content/stage-mechanic --format json
```

The CLI validates all graphs against the supplied author context; it does not activate content, check full placeholder parity or prove that a provider is available on the running host. Exit 0 allows warnings, 1 reports invalid content, and 2 reports usage/input/execution errors. The server's tests additionally compare every host-contract field except generation with trusted Kotlin authority and resolve referenced text with the real empty argument map. Editing host context cannot grant actions, conditions, gestures or capabilities.

CI also runs a separate content job with JDK 25 and the public, explicitly pinned SDK CLI 1.8.0 release ZIP. It verifies the published `.zip.sha256` checksum and validates every `dialogue-content/*/` project without private package credentials. This validator-tool pin is separate from the server's integration dependency; the normal Gradle tests still enforce trusted host authority, packaged provenance and actual text arguments.

Run the same packaging and admission checks as CI:

```sh
./gradlew test --tests '*BundledDialogueContentTest' --no-parallel --max-workers=1
./gradlew test --no-parallel --max-workers=1
./gradlew spotlessApply --no-parallel --max-workers=1
./gradlew build --no-parallel --max-workers=1
```

Pin fixtures in `src/test/resources/dialogue-migration/<project>.sha256` record each original dialogue's content pin, exported from the original Kotlin builders using the SDK. They detect accidental migration/content changes. For an intentional content edit, review the JSON and content version, run the focused test, and copy that project's **actual validated dialogue pin** from its pin assertion failure into the corresponding `.sha256` file. Rerun the checks. No Kotlin change is needed. The CLI reports a **catalogue** pin; do not copy it into these per-dialogue fixtures. Never update a fixture to bypass an admission diagnostic.

## Runtime ownership

The Guide retains the `grounds:lobby-guide` identity, `guide` speaker role, persistent `spawn-guide` NPC restriction, and actual Navigator/Duel menu actions. Stage retains `stage:mechanic`, role `guide`, and no action providers. Handler code, scene/NPC bindings, voice/prosody/sounds, pack pins and player language preferences remain host responsibilities. A map cannot replace a built-in ID through these resources. Requested locales still come from the existing player preference observer.

Packaging reads only graph JSON, text JSON and host context. Invalid ZIP paths, duplicates, unsupported nested locales, oversized inputs, SDK errors, stale host declarations and unavailable text arguments reject the entire project with project/path diagnostics. Resources are captured once without extraction or classpath directory enumeration.

A later distribution increment can publish the same standard project files independently. It will need explicit version selection, trusted admission and activation rules; the current source remains bundled with the Lobby server.
