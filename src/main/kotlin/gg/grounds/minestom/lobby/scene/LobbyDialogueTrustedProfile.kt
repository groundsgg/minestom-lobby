package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import gg.grounds.dialogue.json.DialogueJsonCodec
import gg.grounds.lobby.scene.LobbySceneCatalogs

/** Immutable data from the operator-anchored profile. Only Prepared carries trust authority. */
internal class LobbyDialogueTrustedProfile
private constructor(
    val id: String,
    val version: String,
    val contract: LobbyDialogueHostContract,
    profile: ByteArray,
    host: ByteArray,
    val assetCatalog: Reference,
    actions: Set<Reference>,
    bodies: Set<String>,
) {
    data class Reference(val id: String, val version: String)

    private val profile = profile.clone()
    private val host = host.clone()
    val profileBytes
        get() = profile.clone()

    val hostCatalogBytes
        get() = host.clone()

    val sha256 = LobbyDialogueDistributionBinding.hash(profile)
    val actionCatalogs: Set<Reference> = java.util.Set.copyOf(actions)
    val npcBodies: Set<String> = java.util.Set.copyOf(bodies)

    companion object {
        fun parse(
            profileBytes: ByteArray,
            hostBytes: ByteArray,
            expectedId: String,
            generation: String,
        ): LobbyDialogueTrustedProfile {
            val root = LobbyDialogueRuntimeVerification.strictJson(profileBytes).asJsonObject
            fields(
                root,
                setOf(
                    "schemaVersion",
                    "id",
                    "version",
                    "hostCatalogJson",
                    "assetCatalog",
                    "actionCatalogs",
                    "startActions",
                    "npcAssets",
                ),
            )
            require(root["schemaVersion"].toString() == "1")
            val id = text(root["id"])
            val version = text(root["version"])
            require(id == expectedId && version == generation)
            val contract = LobbyDialogueHostContract.fromProfileId(id)
            require(
                text(root["hostCatalogJson"], 1048576)
                    .toByteArray(Charsets.UTF_8)
                    .contentEquals(hostBytes)
            )
            val context = DialogueJsonCodec().prepareContext(emptyMap(), hostBytes)
            require(context.isValid()) { "Invalid trusted host catalogue: ${context.problems()}" }
            require(
                context.context()!!.catalog() ==
                    LobbyDialogueHostRegistration.create(contract, contract.builtInDialogueId)
                        .catalog(generation)
            ) {
                "Profile exceeds finite host contract"
            }
            val starts = root["startActions"].asJsonObject
            fields(starts, setOf(LobbySceneCatalogs.START_DIALOGUE.value))
            val start = starts[LobbySceneCatalogs.START_DIALOGUE.value].asJsonObject
            fields(start, setOf("dialogueArgument", "speakerArgument"))
            require(
                text(start["dialogueArgument"]) == LobbySceneCatalogs.DIALOGUE_ID.value &&
                    text(start["speakerArgument"]) == LobbySceneCatalogs.SPEAKER.value
            )
            val actions = root["actionCatalogs"].asJsonArray
            require(actions.size() in 1..16)
            val references = actions.map(::reference)
            require(references.distinct().size == references.size)
            val assets = root["npcAssets"].asJsonObject
            require(assets.size() in 1..4096)
            assets.entrySet().forEach { (key, value) ->
                text(com.google.gson.JsonPrimitive(key))
                fields(value.asJsonObject, setOf("gestures"))
                require(value.asJsonObject["gestures"].asJsonObject.size() == 0)
            }
            return LobbyDialogueTrustedProfile(
                id,
                version,
                contract,
                profileBytes,
                hostBytes,
                reference(root["assetCatalog"]),
                references.toSet(),
                assets.keySet(),
            )
        }

        internal fun fields(value: JsonObject, expected: Set<String>) =
            require(value.keySet() == expected)

        internal fun text(value: JsonElement, max: Int = 128): String {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString.also { require(it.isNotBlank() && it.length <= max) }
        }

        private fun reference(value: JsonElement): Reference {
            val obj = value.asJsonObject
            fields(obj, setOf("id", "version"))
            return Reference(text(obj["id"]), text(obj["version"]))
        }
    }
}
