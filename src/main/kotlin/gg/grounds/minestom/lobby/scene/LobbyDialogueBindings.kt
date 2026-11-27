package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.*
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec

/** Validate every declared dialogue binding before accepting this scene. */
internal fun PreparedLobbyScene.dialogueBindings(
    replaceNpcLabels: Boolean = true
): List<LobbyDialogueBinding> =
    scene.elements
        .filterIsInstance<Npc>()
        .flatMap { npc ->
            npc.bindings.flatMap { binding ->
                binding.actions
                    .filterIsInstance<ApplicationAction>()
                    .filter { it.key == LobbySceneCatalogs.START_DIALOGUE }
                    .map { action ->
                        val dialogue =
                            (action.arguments[LobbySceneCatalogs.DIALOGUE_ID] as? StringArgument)
                                ?.value
                        val speaker =
                            (action.arguments[LobbySceneCatalogs.SPEAKER] as? StringArgument)?.value
                        require(
                            action.arguments.keys ==
                                setOf(LobbySceneCatalogs.DIALOGUE_ID, LobbySceneCatalogs.SPEAKER) &&
                                dialogue in
                                    setOf(
                                        LobbyGuideContent.DIALOGUE_ID,
                                        StageLobbyDialogue.DIALOGUE_ID,
                                    ) &&
                                speaker == LobbyGuideContent.SPEAKER_ROLE &&
                                binding.trigger == SceneTrigger.RIGHT_CLICK &&
                                binding.conditions
                                    .filterIsInstance<HandCondition>()
                                    .singleOrNull()
                                    ?.hand == SceneHand.MAIN
                        ) {
                            "Unsupported lobby dialogue binding on ${npc.id.value}"
                        }
                        require(
                            dialogue != LobbyGuideContent.DIALOGUE_ID ||
                                npc.id.value == "spawn-guide"
                        ) {
                            "Lobby guide must use the spawn-guide element"
                        }
                        val origin = npc.transform.position
                        val position =
                            Pos(
                                origin.x,
                                origin.y,
                                origin.z,
                                // Scene +yaw faces east; Minestom anchors use the opposite sign.
                                -npc.transform.rotation.yaw.toFloat(),
                                0f,
                            )
                        val fallbackLabel =
                            if (dialogue == LobbyGuideContent.DIALOGUE_ID && npc.label == null) {
                                val top =
                                    npc.interactionBounds.center.y +
                                        npc.interactionBounds.size.y / 2
                                val height = top * npc.transform.scale.y + 0.4
                                StageDialogueLabel(
                                    npc.id,
                                    Component.text("Guide"),
                                    position.add(0.0, height, 0.0),
                                )
                            } else null
                        val replacementLabel =
                            if (replaceNpcLabels) {
                                npc.label?.let { text ->
                                    StageDialogueLabel(
                                        npc.id,
                                        text,
                                        position.add(npc.worldLabelOffset()),
                                    )
                                } ?: fallbackLabel
                            } else null
                        LobbyDialogueBinding(
                            npc.id,
                            position,
                            dialogue!!,
                            speaker,
                            replacementLabel,
                            fallbackLabel.takeUnless { replaceNpcLabels },
                        )
                    }
            }
        }
        .also { bindings ->
            require(bindings.map { it.elementId }.distinct().size == bindings.size) {
                "Duplicate lobby dialogue binding"
            }
        }

/** Replace only dialogue NPC labels with per-player visibility. */
internal fun PreparedLobbyScene.withDialogueLabels(
    bindings: List<LobbyDialogueBinding>,
    owner: LobbyDialogues,
): PreparedLobbyScene {
    var registry = renderers
    var document = scene
    for (binding in bindings) {
        val label = binding.replacementLabel ?: binding.standaloneLabel ?: continue
        val npc = scene.elements.filterIsInstance<Npc>().single { it.id == binding.elementId }
        val custom = binding.replacementLabel?.let { owner.customNameplate(it.text) }
        registry =
            label.renderers(registry, npc.body, custom, owner::nameplatePackViewers, ::emptySet)
        document =
            SceneDocument(
                document.schemaVersion,
                document.id,
                document.metadata,
                document.catalogs,
                document.groups,
                document.elements.map { element ->
                    if (element !== npc) element
                    else
                        Npc(
                            npc.id,
                            npc.group,
                            npc.transform,
                            npc.visible,
                            npc.activation,
                            npc.body,
                            null,
                            npc.labelOffset,
                            npc.look,
                            npc.initialAnimation,
                            npc.interactionBounds,
                            npc.proximity,
                            npc.bindings,
                            npc.collision,
                        )
                },
            )
    }
    return copy(scene = document, renderers = registry)
}

private fun Npc.worldLabelOffset(): Vec =
    Vec(
            labelOffset.x * transform.scale.x,
            labelOffset.y * transform.scale.y,
            labelOffset.z * transform.scale.z,
        )
        .rotateAroundZ(Math.toRadians(transform.rotation.roll))
        .rotateAroundX(Math.toRadians(transform.rotation.pitch))
        .rotateAroundY(Math.toRadians(transform.rotation.yaw))
