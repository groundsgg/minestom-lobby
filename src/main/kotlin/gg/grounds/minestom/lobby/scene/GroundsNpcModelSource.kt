package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.core.model.NpcModel
import gg.grounds.scene.format.*
import gg.grounds.scene.npc.minestom.SceneNpcTransformMapper
import java.util.Collections

/** Product content loading and exact catalogue checks stay in the Grounds host. */
internal object GroundsNpcModelSource {
    private val configuredContent by lazy {
        val configured =
            System.getProperty("grounds.npc.content") ?: System.getenv("GROUNDS_NPC_CONTENT")
        configured?.let {
            require(it.isNotBlank()) { "NPC content path must not be blank" }
            capture(java.nio.file.Path.of(it)).also { loaded -> requireBinding(loaded.content) }
        }
    }

    /** One immutable snapshot per host startup; absent configuration means neutral content. */
    fun load(assets: AssetCatalog): Map<AssetKey, NpcModel> =
        configuredContent?.also { validate(assets, it.models) }?.models ?: emptyMap()

    /** Explicit path for tooling and host tests. Configured errors are never treated as absence. */
    fun load(assets: AssetCatalog, contentPath: java.nio.file.Path?): Map<AssetKey, NpcModel> =
        contentPath?.let(::capture)?.also { validate(assets, it.models) }?.models ?: emptyMap()

    private fun read(path: java.nio.file.Path): gg.grounds.npc.core.io.content.NpcContent {
        val content = gg.grounds.npc.core.io.content.NpcContentLoader.load(path)
        require(content.target == "26.3") { "NPC content must target this host's Minecraft 26.3" }
        return content
    }

    private fun requireBinding(content: gg.grounds.npc.core.io.content.NpcContent) {
        GroundsNpcModelSource::class
            .java
            .getResourceAsStream("/gg/grounds/minestom/lobby/npc-content-id.txt")
            ?.use {
                require(it.bufferedReader().readText().trim() == content.contentId) {
                    "NPC content differs from this private distribution's addon binding"
                }
            }
    }

    /**
     * Required private binding for one-shot qualification; normal neutral loading stays optional.
     */
    fun requireContentBinding(
        contentId: String,
        resource: () -> java.io.InputStream? = {
            GroundsNpcModelSource::class
                .java
                .getResourceAsStream("/gg/grounds/minestom/lobby/npc-content-id.txt")
        },
    ) {
        val bound =
            requireNotNull(resource()) {
                    "NPC content binding missing; build a qualified private distribution"
                }
                .use {
                    val bytes = it.readNBytes(130)
                    require(bytes.size <= 129) { "NPC content binding exceeds size limit" }
                    bytes.toString(Charsets.US_ASCII).removeSuffix("\n")
                }
        require(bound == contentId) { "NPC content differs from private distribution binding" }
    }

    fun loadRequired(assets: AssetCatalog, path: java.nio.file.Path): LoadedContent {
        return capture(path).also {
            requireContentBinding(it.contentId)
            validate(assets, it.models)
        }
    }

    class LoadedContent
    internal constructor(
        internal val content: gg.grounds.npc.core.io.content.NpcContent,
        val models: Map<AssetKey, NpcModel>,
        val sourcePin: Map<String, Any> = emptyMap(),
    ) {
        val contentId
            get() = content.contentId

        fun verifyAddon(path: java.nio.file.Path) = content.verifyResourcePack(path)
    }

    private fun capture(path: java.nio.file.Path): LoadedContent {
        if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            val content = read(path)
            return LoadedContent(
                content,
                Collections.unmodifiableMap(content.models.mapKeys { AssetKey(it.key) }),
            )
        }
        val bytes = LobbyDialogueFileSnapshot.file(path, 128 * 1024 * 1024)
        val sourcePin = LobbyDialogueFileSnapshot.pin(bytes)
        val resources = linkedMapOf<String, ByteArray>()
        val names = hashSetOf<String>()
        var total = 0
        java.util.zip.ZipInputStream(bytes.inputStream()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val name = entry.name.removeSuffix("/")
                require(
                    name.isNotEmpty() &&
                        name.length <= 1024 &&
                        !name.startsWith('/') &&
                        '\\' !in name &&
                        ':' !in name &&
                        name.none { it.code < 32 } &&
                        name.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC) == name
                )
                require(names.add(name.lowercase(java.util.Locale.ROOT)) && names.size <= 8192) {
                    "Duplicate or excessive NPC archive entries"
                }
                if (entry.isDirectory)
                    require(archive.read() == -1) { "NPC directory has a payload" }
                else {
                    val maximum =
                        minOf(
                            64 * 1024 * 1024 - total,
                            if (name == "bundle.json") 4 * 1024 * 1024 else 2 * 1024 * 1024,
                        )
                    val payload = LobbyDialogueFileSnapshot.read(archive, maximum)
                    total += payload.size
                    resources[name] = payload
                }
                archive.closeEntry()
            }
        }
        val content = gg.grounds.npc.core.io.content.NpcContentLoader.load(resources)
        require(content.target == "26.3") { "NPC content must target this host's Minecraft 26.3" }
        val models = CapturedModels(content, sourcePin)
        return LoadedContent(content, models, sourcePin)
    }

    private class CapturedModels(
        val content: gg.grounds.npc.core.io.content.NpcContent,
        val pin: Map<String, Any>,
    ) :
        Map<AssetKey, NpcModel> by Collections.unmodifiableMap(
            content.models.mapKeys { AssetKey(it.key) }
        )

    internal fun snapshot(models: Map<AssetKey, NpcModel>): LoadedContent? =
        (models as? CapturedModels)?.let { LoadedContent(it.content, models, it.pin) }

    internal class RendererSnapshot(
        val content: LoadedContent?,
        val sourcePin: Map<String, Any>,
        val effectiveScene: SceneDocument,
    )

    private class CapturedRenderers(
        delegate: gg.grounds.scene.minestom.SceneAssetRendererRegistry,
        val snapshot: RendererSnapshot,
    ) : gg.grounds.scene.minestom.SceneAssetRendererRegistry by delegate

    internal fun remember(
        registry: gg.grounds.scene.minestom.SceneAssetRendererRegistry,
        snapshot: RendererSnapshot,
    ): gg.grounds.scene.minestom.SceneAssetRendererRegistry = CapturedRenderers(registry, snapshot)

    internal fun rendererSnapshot(
        registry: gg.grounds.scene.minestom.SceneAssetRendererRegistry
    ): RendererSnapshot? = (registry as? CapturedRenderers)?.snapshot

    private fun models(
        assets: AssetCatalog,
        content: gg.grounds.npc.core.io.content.NpcContent?,
    ): Map<AssetKey, NpcModel> {
        val result = content?.models?.mapKeys { AssetKey(it.key) } ?: emptyMap()
        validate(assets, result)
        return Collections.unmodifiableMap(result)
    }

    fun validate(assets: AssetCatalog, models: Map<AssetKey, NpcModel>) {
        models.forEach { (key, model) ->
            val definition =
                requireNotNull(assets.assets[key]) { "NPC model absent from exact catalogue: $key" }
            require(
                model.id == key.value &&
                    definition.kind == AssetKind.NPC_BODY &&
                    definition.defaultBounds ==
                        LocalBounds(
                            SceneNpcTransformMapper.toScene(model.bounds.center),
                            SceneNpcTransformMapper.toScene(model.bounds.size),
                        ) &&
                    definition.animations == model.animations.keys.map(::LocalId).toSet()
            ) {
                "NPC model/catalogue contract mismatch: $key"
            }
            require(model.parts.all { it.model.startsWith("grounds:npc_parts/") }) {
                "NPC mesh is outside the compiled Grounds part namespace: $key"
            }
            require(
                model.sounds.values.all { assets.assets[AssetKey(it.key)]?.kind == AssetKind.SOUND }
            ) {
                "NPC sound missing from catalogue"
            }
        }
    }
}
