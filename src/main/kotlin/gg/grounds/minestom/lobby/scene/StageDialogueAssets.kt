package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile

internal object StageDialogueAssets {
    private const val BASE = "/grounds-dialogue/grounds-26.3/"

    fun isPackaged() = StageDialogueAssets::class.java.getResource(BASE + "manifest.json") != null

    fun load(): AssetBundle {
        val profile = MinecraftPackProfile.grounds263()
        val manifest =
            StageDialogueAssets::class.java.getResourceAsStream(BASE + "manifest.json")
                ?: run {
                    org.slf4j.LoggerFactory.getLogger(StageDialogueAssets::class.java)
                        .warn(
                            "Disabled NPC dialogue pack capability (reason=packaged_assets_missing, profile={})",
                            profile.id,
                        )
                    return AssetBundle.loadDefault(profile)
                }
        val data = manifest.use { it.readNBytes(1024 * 1024 + 1) }
        val entries =
            JsonParser.parseString(data.toString(Charsets.UTF_8))
                .asJsonObject
                .getAsJsonObject("files")
                .keySet()
                .associateWith { path ->
                    require(path.startsWith("assets/dialogue/") && !path.contains(".."))
                    requireNotNull(
                            StageDialogueAssets::class.java.getResourceAsStream(BASE + path)
                        ) {
                            "Missing packaged dialogue asset: $path"
                        }
                        .use { it.readNBytes(32 * 1024 * 1024 + 1) }
                }
        return AssetBundle.load(profile, data, entries)
    }
}
