package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.json.DialogueCatalogLoader
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.Npc

/** Entire captured catalogue or failure; loading does not admit a scene or a session. */
internal object LobbyAuthoredDialogueCatalogue {
    sealed interface LoadResult

    data object Absent : LoadResult

    class Present internal constructor(val candidate: Candidate) : LoadResult

    fun load(
        inputs: LobbyAuthoredDialogueInputs,
        prepared: LobbyDialogueRuntimeVerification.Prepared?,
    ): LoadResult =
        when (inputs) {
            LobbyAuthoredDialogueInputs.Absent -> Absent
            is LobbyAuthoredDialogueInputs.Present ->
                Present(
                    load(
                        inputs,
                        requireNotNull(prepared) {
                            "Authored Story requires an operator-anchored profile"
                        },
                    )
                )
        }

    class Entry internal constructor(val validated: ValidatedDialogue) {
        val defaultLocale: String = validated.definition().locales().defaultTag()
        val supportedLocales: Set<String> =
            java.util.Set.copyOf(validated.definition().locales().supported())
        val texts: TextCatalog = validated.texts()
        val resolver: TextResolver = TextResolver.catalogue(texts, defaultLocale)

        fun locale(requested: String, revision: Long) =
            LocaleContext(requested, revision, defaultLocale)
    }

    class Candidate
    internal constructor(
        private val inputs: LobbyAuthoredDialogueInputs.Present,
        private val prepared: LobbyDialogueRuntimeVerification.Prepared,
        entries: Map<String, ValidatedDialogue>,
        speakers: Map<String, String>,
    ) {
        private val entries = java.util.Map.copyOf(entries)
        val speakers: Map<String, String> = java.util.Map.copyOf(speakers)
        val contract: LobbyDialogueHostContract = prepared.profile.contract
        val dialogueIds: Set<String> = java.util.Set.copyOf(entries.keys)

        fun requireUnchanged() = inputs.requireUnchanged()

        /** Final Scene association; body and catalogue identities come from the actual Scene. */
        fun requireScene(scene: PreparedLobbyScene): String {
            val profile = prepared.profile
            require(
                profile.assetCatalog ==
                    LobbyDialogueTrustedProfile.Reference(
                        scene.assets.id.value,
                        scene.assets.version,
                    )
            ) {
                "Scene asset catalogue differs from trusted profile"
            }
            require(
                LobbyDialogueTrustedProfile.Reference(
                    scene.actions.id.value,
                    scene.actions.version,
                ) in profile.actionCatalogs
            ) {
                "Scene action catalogue differs from trusted profile"
            }
            require(
                scene.scene.catalogs.assets.id == scene.assets.id &&
                    scene.scene.catalogs.assets.version == scene.assets.version &&
                    scene.scene.catalogs.actions.id == scene.actions.id &&
                    scene.scene.catalogs.actions.version == scene.actions.version
            ) {
                "Scene catalogue references differ from resolved catalogues"
            }
            val binding = scene.dialogueBindings(contract).single()
            require(binding.dialogue in entries) {
                "Scene start names missing authored dialogue: ${binding.dialogue}"
            }
            require(speakers == mapOf("guide" to binding.elementId.value)) {
                "Guide must explicitly bind the primary start NPC"
            }
            val npc =
                scene.scene.elements.filterIsInstance<Npc>().single { it.id == binding.elementId }
            require(
                npc.body.value in profile.npcBodies &&
                    scene.assets.assets[npc.body]?.kind == AssetKind.NPC_BODY
            ) {
                "Guide body is unsupported by trusted profile"
            }
            return binding.dialogue
        }

        /** Issue fresh proofs with installed finite providers and the actual runtime UUID. */
        fun revalidate(
            scene: PreparedLobbyScene,
            registrations: Map<String, LobbyDialogueHostRegistration>,
            generation: String,
        ): Map<String, Entry> {
            val primary = requireScene(scene)
            require(generation.isNotBlank() && generation != prepared.generation)
            require(registrations.keys == setOf(primary)) {
                "Actual dialogue registrations differ from primary start"
            }
            val actual = registrations.getValue(primary)
            require(actual.contract == contract && actual.dialogueId == primary)
            actual.requireExportReady()
            val result =
                entries.mapValues { (id, source) ->
                    val definition = source.definition()
                    val supported = definition.locales().supported().toSet()
                    val scoped =
                        TextCatalog(source.texts().locales().filterKeys { it in supported })
                    val report =
                        DialogueValidator()
                            .validate(
                                definition,
                                scoped,
                                actual.catalog(generation),
                                ValidationLimits.defaults(),
                            )
                    require(report.isValid()) {
                        "Runtime dialogue invalid (dialogue=$id, problems=${report.problems()})"
                    }
                    Entry(requireNotNull(report.validated()))
                }
            return java.util.Map.copyOf(result)
        }
    }

    fun load(
        inputs: LobbyAuthoredDialogueInputs.Present,
        prepared: LobbyDialogueRuntimeVerification.Prepared,
    ): Candidate {
        val profile = prepared.profile
        require(profile.contract == LobbyDialogueHostContract.GUIDE) {
            "Authored Stories require the finite Guide contract"
        }
        require(inputs.host().contentEquals(profile.hostCatalogBytes)) {
            "context/host-catalog.json differs from trusted profile"
        }
        val bindings = LobbyDialogueRuntimeVerification.strictJson(inputs.bindings()).asJsonObject
        LobbyDialogueTrustedProfile.fields(bindings, setOf("schemaVersion", "profile", "speakers"))
        require(bindings["schemaVersion"].toString() == "1")
        val reference = bindings["profile"].asJsonObject
        LobbyDialogueTrustedProfile.fields(reference, setOf("id", "version", "sha256"))
        require(
            LobbyDialogueTrustedProfile.text(reference["id"]) == profile.id &&
                LobbyDialogueTrustedProfile.text(reference["version"]) == profile.version &&
                LobbyDialogueTrustedProfile.text(reference["sha256"]) == profile.sha256
        ) {
            "grounds/dialogue.json profile reference differs from operator snapshot"
        }
        val speakerObject = bindings["speakers"].asJsonObject
        LobbyDialogueTrustedProfile.fields(speakerObject, setOf("guide"))
        val speakers =
            speakerObject.entrySet().associate { (role, value) ->
                role to LobbyDialogueTrustedProfile.text(value)
            }
        val loaded =
            DialogueCatalogLoader()
                .reload(
                    DialogueCatalogLoader.ContentSources(
                        inputs.dialogues(),
                        inputs.texts(),
                        profile.hostCatalogBytes,
                    )
                )
        require(loaded.isValid() && loaded.activated()) {
            "Story catalogue invalid: ${loaded.problems()}"
        }
        val entries = requireNotNull(loaded.candidate()).entries()
        require(
            entries.keys.none {
                it in
                    LobbyDialogueHostContract.entries.map { contract -> contract.builtInDialogueId }
            }
        ) {
            "Authored catalogue cannot replace built-in dialogue IDs"
        }
        entries.forEach { (id, content) ->
            require(
                content
                    .definition()
                    .nodes()
                    .values
                    .filterIsInstance<DialogueDefinition.Message>()
                    .all { it.speaker() in speakers }
            ) {
                "Story speaker has no explicit NPC binding (dialogue=$id)"
            }
        }
        return Candidate(inputs, prepared, entries, speakers)
    }
}
