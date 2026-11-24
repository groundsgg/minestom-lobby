package gg.grounds.minestom.lobby.scene

import com.google.gson.GsonBuilder
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.SceneActionRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class LobbyDialogueStartAction(
    val dialogueArgument: LocalId,
    val speakerArgument: LocalId,
)

internal data class LobbyDialogueRole(val asset: AssetKey, val gestures: Map<String, LocalId>)

/** Export only capabilities supplied by the runtime that owns these exact registries. */
internal class LobbyDialogueProfile(
    id: String,
    version: String,
    generation: String,
    registry: SceneActionRegistry,
    assets: AssetCatalog,
    actionCatalogs: List<ActionCatalog>,
    renderers: AnimatedSceneRenderers,
    startActions: Map<ActionKey, LobbyDialogueStartAction>,
    roles: Map<String, LobbyDialogueRole>,
    registration: LobbyDialogueHostRegistration,
) {
    private val profile: ByteArray
    private val host: ByteArray

    init {
        registration.requireExportReady()
        val catalog = registration.catalog(generation)
        require(roles.keys == catalog.speakers()) { "Unsupported dialogue speaker roles" }
        fun text(value: String) = require(value.isNotBlank() && value.length <= 128)
        fun identifier(value: String) {
            text(value)
            require(Regex("[a-zA-Z0-9_.:/-]+").matches(value))
        }
        listOf(id, version, generation).forEach(::text)
        val assetCatalogReference = CatalogReference(assets.id, assets.version)
        require(actionCatalogs.size in 1..16)
        val actionReferences = actionCatalogs.map { CatalogReference(it.id, it.version) }
        require(actionReferences.distinct().size == actionReferences.size)
        (actionReferences + assetCatalogReference).forEach {
            text(it.id.value)
            text(it.version)
        }
        require(startActions.size <= 128 && roles.size <= 4096)
        startActions.forEach { (key, binding) ->
            require(registry.handlerFor(key) != null) {
                "Missing installed start action handler: ${key.value}"
            }
            val definitions = actionCatalogs.mapNotNull { it.actions[key] }
            require(definitions.size == 1) {
                "Start action must belong to exactly one catalog: ${key.value}"
            }
            text(key.value)
            require(definitions.single().key == key)
            val parameters = definitions.single().parameters
            require(binding.dialogueArgument != binding.speakerArgument)
            require(parameters.keys == setOf(binding.dialogueArgument, binding.speakerArgument))
            parameters.forEach { (name, parameter) ->
                require(
                    parameter.id == name &&
                        parameter.type == ActionParameterType.STRING &&
                        parameter.required &&
                        parameter.defaultValue == null
                )
            }
        }
        val npcAssets = sortedMapOf<String, Map<String, Map<String, String>>>()
        roles.toSortedMap().forEach { (role, binding) ->
            identifier(role)
            text(binding.asset.value)
            require(binding.gestures.size <= 4096)
            require(binding.gestures.keys.all { it in catalog.gestures() }) {
                "Unsupported dialogue gesture mapping for $role"
            }
            val asset =
                requireNotNull(assets.assets[binding.asset]) {
                    "Missing role asset: ${binding.asset.value}"
                }
            require(asset.key == binding.asset && asset.kind == AssetKind.NPC_BODY)
            require(renderers.rendererFor(binding.asset, asset.kind) != null) {
                "Missing NPC renderer: ${binding.asset.value}"
            }
            binding.gestures.forEach { (gesture, animation) ->
                identifier(gesture)
                text(animation.value)
                require(
                    animation in asset.animations &&
                        renderers.supportsAnimation(binding.asset, animation)
                ) {
                    "Unsupported gesture animation: ${binding.asset.value}/${animation.value}"
                }
            }
            val entry =
                mapOf("gestures" to binding.gestures.toSortedMap().mapValues { it.value.value })
            val prior = npcAssets.put(binding.asset.value, entry)
            require(prior == null || prior == entry) {
                "Conflicting gesture mappings for ${binding.asset.value}"
            }
        }
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val hostJson =
            gson.toJson(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "generation" to catalog.generation(),
                    "speakers" to catalog.speakers().sorted(),
                    "conditions" to catalog.conditions(),
                    "actions" to
                        registration.actionProviders
                            .sortedBy { it.key }
                            .associate { provider ->
                                provider.key to
                                    linkedMapOf(
                                        "arguments" to provider.spec.arguments(),
                                        "durable" to provider.spec.durable(),
                                    )
                            },
                    "gestures" to catalog.gestures().sorted(),
                    "capabilities" to catalog.capabilities().sorted(),
                    "durableOutcomes" to catalog.durableOutcomes(),
                )
            )
        fun reference(ref: CatalogReference) =
            linkedMapOf("id" to ref.id.value, "version" to ref.version)
        host = hostJson.toByteArray(Charsets.UTF_8)
        profile =
            gson
                .toJson(
                    linkedMapOf(
                        "schemaVersion" to 1,
                        "id" to id,
                        "version" to version,
                        "hostCatalogJson" to hostJson,
                        "assetCatalog" to reference(assetCatalogReference),
                        "actionCatalogs" to
                            actionReferences
                                .sortedWith(compareBy({ it.id.value }, { it.version }))
                                .map(::reference),
                        "startActions" to
                            startActions.entries
                                .sortedBy { it.key.value }
                                .associate { (key, binding) ->
                                    key.value to
                                        linkedMapOf(
                                            "dialogueArgument" to binding.dialogueArgument.value,
                                            "speakerArgument" to binding.speakerArgument.value,
                                        )
                                },
                        "npcAssets" to npcAssets,
                    )
                )
                .toByteArray(Charsets.UTF_8)
        require(host.size <= 1048576 && profile.size <= 1048576)
    }

    val profileBytes: ByteArray
        get() = profile.clone()

    val hostCatalogBytes: ByteArray
        get() = host.clone()

    val profileSha256: String
        get() =
            MessageDigest.getInstance("SHA-256").digest(profile).joinToString("") {
                "%02x".format(it)
            }

    fun writeTo(directory: Path) {
        Files.createDirectories(directory.resolve("context"))
        Files.write(directory.resolve("profile.json"), profile)
        Files.write(directory.resolve("context/host-catalog.json"), host)
    }
}
