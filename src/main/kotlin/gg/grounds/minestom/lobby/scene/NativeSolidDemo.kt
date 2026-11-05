package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*

internal val NATIVE_SOLID_DEMO_IDS = setOf("demo-solid", "demo-fixed", "demo-hands")

/** Explicit Stage native-collision policy; also accepts the immutable older demo sidecar. */
internal fun nativeSolidDemoScene(scene: SceneDocument): SceneDocument {
    val targets =
        mapOf(
            "demo-fixed" to "grounds:npc/guildfolk/forge_master",
            "demo-hands" to "grounds:npc/craftmasters/dismantler",
        )
    val seen = mutableSetOf<String>()
    var changed = false
    val elements =
        scene.elements.map { element ->
            val asset = targets[element.id.value]
            if (asset == null) element
            else {
                require(
                    element is Npc &&
                        element.body.value == asset &&
                        element.look == LookBehavior.Fixed
                ) {
                    "Unexpected native solid demo model: ${element.id.value}"
                }
                require(
                    element.collision.mode in setOf(NpcCollisionMode.NONE, NpcCollisionMode.SOLID)
                ) {
                    "Unexpected native demo collision mode"
                }
                seen += element.id.value
                if (element.collision.mode == NpcCollisionMode.SOLID) element
                else {
                    changed = true
                    Npc(
                        element.id,
                        element.group,
                        element.transform,
                        element.visible,
                        element.activation,
                        element.body,
                        element.label,
                        element.labelOffset,
                        element.look,
                        element.initialAnimation,
                        element.interactionBounds,
                        element.proximity,
                        element.bindings,
                        NpcCollision(NpcCollisionMode.SOLID),
                    )
                }
            }
        }
    require(seen == targets.keys) { "Native solid prop stations missing" }
    return if (!changed) scene
    else
        SceneDocument(
            scene.schemaVersion,
            scene.id,
            scene.metadata,
            scene.catalogs,
            scene.groups,
            elements,
        )
}

/**
 * Authored static-prop envelopes, in NPC-local coordinates after the renderer's Y180 facing.
 * Derived from every shipped mesh corner and item composite transform in addon d7b3f7ae…eb5044fc.
 * Body bounds stay separate so the empty space above an anvil/table is not filled.
 */
internal fun nativeSolidPropBounds(npc: Npc): List<LocalBounds> =
    when (npc.body.value) {
        "grounds:npc/craftmasters/dismantler" ->
            listOf(
                LocalBounds(
                    Vec3(-0.011283562003231662, 0.625, 0.8184566761250252),
                    Vec3(1.8087921756749317, 1.25, 1.2380866477499497),
                )
            )
        "grounds:npc/guildfolk/forge_master" ->
            listOf(LocalBounds(Vec3(0.0, 0.46875, 0.78125), Vec3(1.25, 0.9375, 0.6875)))
        else -> emptyList()
    }
