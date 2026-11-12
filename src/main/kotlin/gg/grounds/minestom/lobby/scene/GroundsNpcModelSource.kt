package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import gg.grounds.npc.core.*
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import gg.grounds.scene.npc.minestom.SceneNpcTransformMapper
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Product content loading and exact catalogue checks stay in the Grounds host. */
internal object GroundsNpcModelSource {
    @JvmOverloads
    fun load(
        assets: AssetCatalog,
        resources: (String) -> InputStream? = {
            GroundsAssetCatalog::class.java.getResourceAsStream(it)
        },
    ): Map<AssetKey, NpcModel> {
        fun read(path: String, max: Int): ByteArray? =
            resources(path)?.use {
                it.readNBytes(max + 1).also { bytes ->
                    require(bytes.size <= max) { "NPC resource exceeds size limit: $path" }
                }
            }
        val index = read("/gg/grounds/resourcepacks/npc/index.json", 64 * 1024) ?: return emptyMap()
        val root =
            JsonParser.parseString(
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(index))
                        .toString()
                )
                .asJsonObject
        require(root.get("schemaVersion").asInt == 1)
        val entries = root.getAsJsonArray("models")
        require(entries.size() <= 256)
        val result = linkedMapOf<AssetKey, NpcModel>()
        entries.forEach { value ->
            val entry = value.asJsonObject
            val key = AssetKey(entry.get("asset").asString)
            val path = entry.get("resource").asString
            require(
                path.startsWith("/gg/grounds/resourcepacks/npc/") &&
                    path.endsWith(".json") &&
                    ".." !in path &&
                    '\\' !in path
            )
            val decoded =
                NpcModelJson.decode(
                    requireNotNull(read(path, 2 * 1024 * 1024)) {
                        "Missing compiled NPC model $path"
                    }
                )
            require(decoded is NpcDecodeResult.Success) {
                "Invalid compiled NPC model $path: ${(decoded as? NpcDecodeResult.Failure)?.problems}"
            }
            val model = decoded.model
            require(model.id == key.value && result.put(key, model) == null) {
                "NPC index/model identity mismatch or duplicate"
            }
        }
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
