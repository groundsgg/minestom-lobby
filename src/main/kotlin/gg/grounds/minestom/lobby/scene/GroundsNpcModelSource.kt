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
        val bytes = LobbyDialogueFileSnapshot.file(path, 512 * 1024 * 1024)
        val sourcePin = LobbyDialogueFileSnapshot.pin(bytes)
        val expected = archiveDirectory(bytes)
        val consumed = hashSetOf<String>()
        val resources = linkedMapOf<String, ByteArray>()
        val names = hashSetOf<String>()
        var total = 0
        java.util.zip.ZipInputStream(bytes.inputStream()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val description = requireNotNull(expected[entry.name]) { "Unindexed NPC ZIP entry" }
                require(consumed.add(entry.name)) { "Duplicate NPC ZIP entry" }
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
                require(
                    entry.method == description.method &&
                        entry.crc == description.crc &&
                        entry.size == description.size &&
                        entry.compressedSize == description.compressedSize
                ) {
                    "NPC ZIP entry differs from validated directory"
                }
            }
        }
        require(consumed == expected.keys) { "Missing indexed NPC ZIP entry" }
        val content = gg.grounds.npc.core.io.content.NpcContentLoader.load(resources)
        require(content.target == "26.3") { "NPC content must target this host's Minecraft 26.3" }
        val models = CapturedModels(content, sourcePin)
        return LoadedContent(content, models, sourcePin)
    }

    private data class ZipDescription(
        val method: Int,
        val crc: Long,
        val size: Long,
        val compressedSize: Long,
    )

    private fun archiveDirectory(bytes: ByteArray): Map<String, ZipDescription> {
        // Preserve the SDK's bounded, single-disk, non-ZIP64 directory contract before the
        // general-purpose parser allocates an index. Parse the exact captured bytes, never a path.
        fun u16(offset: Int) =
            (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun u32(offset: Int) =
            (0..3).fold(0L) { value, index ->
                value or ((bytes[offset + index].toLong() and 255L) shl (index * 8))
            }
        require(bytes.size >= 22) { "Missing NPC ZIP directory" }
        val end =
            requireNotNull(
                (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
                    u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
                }
            ) {
                "Missing NPC ZIP directory"
            }
        val count = u16(end + 10)
        val offset = u32(end + 16)
        require(
            u16(end + 4) == 0 &&
                u16(end + 6) == 0 &&
                u16(end + 8) == count &&
                count <= 8192 &&
                offset + u32(end + 12) == end.toLong()
        ) {
            "Unsupported NPC ZIP directory"
        }
        var cursor = offset.toInt()
        repeat(count) {
            require(cursor >= 0 && cursor.toLong() + 46 <= end && u32(cursor) == 0x02014b50L) {
                "Truncated NPC ZIP directory"
            }
            val nameSize = u16(cursor + 28)
            val next = cursor + 46 + nameSize + u16(cursor + 30) + u16(cursor + 32)
            require(
                nameSize in 1..1025 &&
                    next <= end &&
                    u32(cursor + 20) != 0xffffffffL &&
                    u32(cursor + 24) != 0xffffffffL
            ) {
                "Invalid NPC ZIP directory entry"
            }
            cursor = next
        }
        require(cursor == end) { "Unvalidated NPC ZIP directory entries" }
        return org.apache.commons.compress.archivers.zip.ZipFile.builder()
            .setSeekableByteChannel(
                org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)
            )
            .setUseUnicodeExtraFields(false)
            .get()
            .use { archive ->
                val result = linkedMapOf<String, ZipDescription>()
                archive.entries.asSequence().forEach { entry ->
                    val rawName =
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(entry.rawName))
                            .toString()
                    require(
                        entry.name == rawName &&
                            entry.diskNumberStart == 0L &&
                            (entry.externalAttributes ushr 16).toInt() and 0xf000 != 0xa000
                    ) {
                        "Unsupported NPC ZIP entry"
                    }
                    require(
                        result.put(
                            rawName,
                            ZipDescription(
                                entry.method,
                                entry.crc,
                                entry.size,
                                entry.compressedSize,
                            ),
                        ) == null
                    ) {
                        "Duplicate NPC ZIP directory entry"
                    }
                }
                require(result.size == count) { "NPC ZIP directory count mismatch" }
                result
            }
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
