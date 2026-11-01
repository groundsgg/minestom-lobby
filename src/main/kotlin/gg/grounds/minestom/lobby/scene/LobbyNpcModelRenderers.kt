package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletableFuture
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

internal class LobbyNpcModelRenderers(
    private val assets: AssetCatalog,
    private val models: Map<AssetKey, NpcModel> = NpcModels.load(assets),
) : AnimatedSceneRenderers {
    init {
        models.forEach { (key, model) ->
            val definition =
                requireNotNull(assets.assets[key]) { "NPC model absent from exact catalogue: $key" }
            require(
                definition.kind == AssetKind.NPC_BODY &&
                    definition.defaultBounds == model.bounds &&
                    definition.animations == model.animations.keys.map(::LocalId).toSet()
            ) {
                "NPC model/catalogue contract mismatch: $key"
            }
            require(
                model.sounds.values.all { assets.assets[AssetKey(it.key)]?.kind == AssetKind.SOUND }
            ) {
                "NPC sound missing from catalogue"
            }
        }
    }

    override fun supportsAnimation(asset: AssetKey, animation: LocalId?) =
        models[asset]?.let { animation == null || animation.value in it.animations } == true

    override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? {
        val model = models[asset]?.takeIf { kind == AssetKind.NPC_BODY } ?: return null
        return SceneAssetRendererFactory { context ->
            val lock = Any()
            val displays =
                model.parts.map { part ->
                    NpcItemDisplay(lock).also { display ->
                        display.updateViewableRule { player ->
                            NpcRenderPolicy.distanceSquared(
                                player,
                                context.instance,
                                context.transform.root.position,
                            ) <= NpcRenderPolicy.VIEW_DISTANCE_SQUARED
                        }
                        display.editEntityMeta(ItemDisplayMeta::class.java) { meta ->
                            meta.setItemStack(
                                ItemStack.of(Material.PAPER)
                                    .with(DataComponents.ITEM_MODEL, part.model)
                            )
                            meta.setTransformationInterpolationDuration(2)
                        }
                    }
                }
            val result = CompletableFuture<RenderedAssetHandle>()
            val anchor = context.transform.root.position
            val attached =
                displays.map { display ->
                    try {
                        display.setInstance(context.instance, Pos(anchor.x, anchor.y, anchor.z))
                            ?: CompletableFuture.failedFuture(
                                IllegalStateException("NPC attachment cancelled")
                            )
                    } catch (failure: Throwable) {
                        CompletableFuture.failedFuture<Void>(failure)
                    }
                }
            CompletableFuture.allOf(*attached.toTypedArray()).whenComplete { _, failure ->
                if (
                    failure != null ||
                        displays.any {
                            !it.isActive ||
                                it.isRemoved ||
                                it.instance !== context.instance ||
                                it.chunk == null ||
                                context.instance.getEntityById(it.entityId) !== it
                        }
                ) {
                    displays.forEach(Entity::remove)
                    result.completeExceptionally(
                        failure ?: IllegalStateException("NPC part attachment failed")
                    )
                } else {
                    try {
                        val handle =
                            AnimatedNpcHandle(
                                lock,
                                model,
                                displays,
                                context.transform,
                                LobbySceneEffects(assets),
                            )
                        if (!result.complete(handle)) handle.close()
                    } catch (error: Throwable) {
                        displays.forEach(Entity::remove)
                        result.completeExceptionally(error)
                    }
                }
            }
            result
        }
    }
}
