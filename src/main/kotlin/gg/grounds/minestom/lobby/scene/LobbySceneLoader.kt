package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.minestom.lobby.LoadedLobbyMap
import gg.grounds.minestom.lobby.LobbyMapSource
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

internal data class PreparedLobbyScene(
    val scene: SceneDocument,
    val assets: AssetCatalog,
    val actions: ActionCatalog,
    val identity: SceneRuntimeIdentity,
    val renderers: SceneAssetRendererRegistry,
)

internal class LobbySceneLoader(
    private val assets: AssetCatalog = GroundsAssetCatalog.catalog,
    private val renderers: SceneAssetRendererRegistry = LobbyPlaceholderRenderers(assets),
) {
    fun load(map: LoadedLobbyMap): PreparedLobbyScene? {
        val file = map.root.resolve("scene.json")
        val attributes =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return null // Only an absent root sidecar is a no-scene map.
            } catch (failure: Exception) {
                throw IllegalStateException("Cannot inspect lobby scene $file", failure)
            }
        check(attributes.isRegularFile) { "Lobby scene $file must be a regular, non-symlink file" }
        val bytes =
            try {
                Files.newInputStream(file, READ, NOFOLLOW_LINKS).use {
                    it.readNBytes(MAX_SCENE_BYTES + 1)
                }
            } catch (failure: Exception) {
                throw IllegalStateException("Cannot read lobby scene $file", failure)
            }
        check(bytes.size <= MAX_SCENE_BYTES) { "Lobby scene $file exceeds 16 MiB" }
        val scene =
            when (val decoded = SceneJson.decode(bytes)) {
                is SceneDecodeResult.Success -> decoded.scene
                is SceneDecodeResult.Failure ->
                    error("Invalid lobby scene $file: ${decoded.problems}")
            }
        val actions =
            LobbySceneCatalogs.resolve(scene.catalogs.actions)
                ?: error("Unsupported action catalog ${scene.catalogs.actions} in $file")
        val problems = SceneValidation.validateCatalogs(scene, assets, actions).problems
        check(problems.isEmpty()) { "Invalid lobby scene catalogs in $file: $problems" }

        // Resolve the finite capability set once. Preflight never invokes renderer factories;
        // the immutable snapshot below is also the registry supplied to SceneRuntimeFactory.
        val factories = linkedMapOf<Pair<AssetKey, AssetKind>, SceneAssetRendererFactory>()
        fun renderer(asset: AssetKey, kind: AssetKind) {
            factories.getOrPut(asset to kind) {
                renderers.rendererFor(asset, kind)
                    ?: error("Missing lobby scene renderer for $kind ${asset.value} in $file")
            }
        }
        scene.elements.forEach { element ->
            when (element) {
                is Prop -> {
                    check(element.initialAnimation == null) {
                        "Unsupported initial animation in $file"
                    }
                    renderer(element.asset, AssetKind.PROP)
                }
                is CompositeProp -> element.parts.forEach { renderer(it.asset, AssetKind.PROP) }
                is Npc -> {
                    check(element.initialAnimation == null) {
                        "Unsupported initial animation in $file"
                    }
                    renderer(element.body, AssetKind.NPC_BODY)
                }
            }
        }
        scene.boundActions().forEach { action ->
            when (action) {
                is StartAnimationAction,
                is StopAnimationAction -> error("Unsupported lobby animation in $file")
                is SetViewerScaleAction ->
                    error(
                        "Unsupported lobby viewer scale in $file; renderer supports highlight only"
                    )
                is PlaySoundAction ->
                    error("Unsupported lobby sound ${action.sound.value} in $file")
                is EmitParticleAction ->
                    error("Unsupported lobby particle ${action.particle.value} in $file")
                else -> Unit
            }
        }
        val snapshot = factories.toMap()
        val identity =
            when (val source = map.source) {
                is LobbyMapSource.Published ->
                    SceneRuntimeIdentity(scene.id, source.address, source.version)
                is LobbyMapSource.Local ->
                    SceneRuntimeIdentity(
                        scene.id,
                        "local:" + map.root.toAbsolutePath().normalize(),
                        0,
                    )
            }
        return PreparedLobbyScene(
            scene,
            assets,
            actions,
            identity,
            SceneAssetRendererRegistry { asset, kind -> snapshot[asset to kind] },
        )
    }

    private companion object {
        const val MAX_SCENE_BYTES = 16 * 1024 * 1024
    }
}

internal fun SceneDocument.boundActions(): Sequence<SceneAction> =
    elements.asSequence().filterIsInstance<Npc>().flatMap { it.bindings }.flatMap { it.actions }
