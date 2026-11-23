package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.*
import net.minestom.server.coordinate.Pos

/** Validate every declared dialogue binding before accepting this scene. */
internal fun PreparedLobbyScene.dialogueBindings(): List<LobbyDialogueBinding> =
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
                        LobbyDialogueBinding(
                            npc.id,
                            Pos(
                                origin.x,
                                origin.y,
                                origin.z,
                                npc.transform.rotation.yaw.toFloat(),
                                0f,
                            ),
                            dialogue!!,
                            speaker,
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
    owner: LobbyDialogues,
    bindings: List<LobbyDialogueBinding>,
): PreparedLobbyScene {
    var registry = renderers
    var document = scene
    for (binding in bindings) {
        val npc = scene.elements.filterIsInstance<Npc>().single { it.id == binding.elementId }
        val text = npc.label ?: continue
        val offset = npc.labelOffset
        val transform = npc.transform
        val scaled =
            net.minestom.server.coordinate
                .Vec(
                    offset.x * transform.scale.x,
                    offset.y * transform.scale.y,
                    offset.z * transform.scale.z,
                )
                .rotateAroundZ(Math.toRadians(transform.rotation.roll))
                .rotateAroundX(Math.toRadians(transform.rotation.pitch))
                .rotateAroundY(Math.toRadians(transform.rotation.yaw))
        registry =
            StageDialogueLabel(npc.id, text, binding.position.add(scaled)).renderers(
                registry,
                npc.body,
            ) {
                owner.npcDialogueViewers(npc.id)
            }
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
