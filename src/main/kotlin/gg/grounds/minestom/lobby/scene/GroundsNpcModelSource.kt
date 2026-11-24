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
            read(java.nio.file.Path.of(it)).also(::requireBinding)
        }
    }

    /** One immutable snapshot per host startup; absent configuration means neutral content. */
    fun load(assets: AssetCatalog): Map<AssetKey, NpcModel> = models(assets, configuredContent)

    /** Explicit path for tooling and host tests. Configured errors are never treated as absence. */
    fun load(assets: AssetCatalog, contentPath: java.nio.file.Path?): Map<AssetKey, NpcModel> =
        models(assets, contentPath?.let(::read))

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
        val content = read(path)
        requireContentBinding(content.contentId)
        return LoadedContent(content, models(assets, content))
    }

    class LoadedContent
    internal constructor(
        private val content: gg.grounds.npc.core.io.content.NpcContent,
        val models: Map<AssetKey, NpcModel>,
    ) {
        val contentId
            get() = content.contentId

        fun verifyAddon(path: java.nio.file.Path) = content.verifyResourcePack(path)
    }

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
