package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.core.NpcBounds
import gg.grounds.npc.core.NpcMatrix
import gg.grounds.npc.minestom.NpcDisplayTransforms
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.npc.minestom.SceneNpcTransformMapper
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta

/** Thin Scene bounds/type adapter; affine/TRSR math belongs to the shared NPC library. */
internal class DisplayTransform
private constructor(private val applyTo: (AbstractDisplayMeta, Vec3) -> Boolean) {
    fun apply(meta: AbstractDisplayMeta, anchor: Vec3 = Vec3(0.0, 0.0, 0.0)) = applyTo(meta, anchor)

    companion object {
        fun from(transform: SceneRenderTransform, bounds: LocalBounds) =
            DisplayTransform { meta, anchor ->
                NpcDisplayTransforms.applyBounds(
                    meta,
                    SceneNpcTransformMapper.toNpc(transform),
                    NpcBounds(
                        SceneNpcTransformMapper.toNpc(bounds.center),
                        SceneNpcTransformMapper.toNpc(bounds.size),
                    ),
                    SceneNpcTransformMapper.toNpc(anchor),
                )
            }

        fun fromModel(transform: SceneRenderTransform, model: NpcMatrix) =
            DisplayTransform { meta, anchor ->
                NpcDisplayTransforms.applyModel(
                    meta,
                    SceneNpcTransformMapper.toNpc(transform),
                    model,
                    SceneNpcTransformMapper.toNpc(anchor),
                )
            }
    }
}
