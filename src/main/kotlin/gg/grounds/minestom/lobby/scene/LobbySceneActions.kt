package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.LobbyNavigatorService
import gg.grounds.lobby.LobbyQueueService
import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.get
import gg.grounds.permissions.Permissions
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

internal fun interface LobbySceneDialogueStarter {
    fun start(
        context: SceneActionContext,
        dialogue: String,
        speaker: String,
    ): CompletionStage<SceneActionResult>
}

internal class LobbySceneActions(
    private val navigator: LobbyNavigatorService?,
    private val policy: ScenePlayerPolicy,
    private val queues: LobbyQueueService? = null,
    private val dialogueStarter: LobbySceneDialogueStarter? = null,
) : SceneActionRegistry {
    private val navigatorHandler =
        navigator?.let { installed ->
            SceneActionHandler { context ->
                if (context.arguments.isNotEmpty() || !policy.isEligible(context.player)) {
                    CompletableFuture.completedFuture(
                        SceneActionResult.Rejected("Player or arguments rejected")
                    )
                } else {
                    installed.open(context.player).thenApply { opened ->
                        if (opened) SceneActionResult.Success
                        else SceneActionResult.Rejected("Navigator unavailable")
                    }
                }
            }
        }

    private val toggleHandler =
        queues?.let { installed ->
            SceneActionHandler { context ->
                val mode = (context.arguments[LobbySceneCatalogs.MODE] as? StringArgument)?.value
                if (
                    context.arguments.keys != setOf(LobbySceneCatalogs.MODE) ||
                        mode == null ||
                        !LobbySceneCatalogs.isValidModeId(mode) ||
                        !policy.isEligible(context.player)
                ) {
                    CompletableFuture.completedFuture(
                        SceneActionResult.Rejected("Player or arguments rejected")
                    )
                } else
                    installed.toggleQueue(context.player, mode).thenApply { accepted ->
                        if (accepted) SceneActionResult.Success
                        else SceneActionResult.Rejected("Queue action unavailable")
                    }
            }
        }

    private val duelHandler =
        queues?.let { installed ->
            SceneActionHandler { context ->
                if (context.arguments.isNotEmpty() || !policy.isEligible(context.player)) {
                    CompletableFuture.completedFuture(
                        SceneActionResult.Rejected("Player or arguments rejected")
                    )
                } else
                    installed.openDuels(context.player).thenApply { accepted ->
                        if (accepted) SceneActionResult.Success
                        else SceneActionResult.Rejected("Duel selection unavailable")
                    }
            }
        }

    private val dialogueHandler =
        dialogueStarter?.let { installed ->
            SceneActionHandler { context ->
                val dialogue =
                    (context.arguments[LobbySceneCatalogs.DIALOGUE_ID] as? StringArgument)?.value
                val speaker =
                    (context.arguments[LobbySceneCatalogs.SPEAKER] as? StringArgument)?.value
                if (
                    context.arguments.keys !=
                        setOf(LobbySceneCatalogs.DIALOGUE_ID, LobbySceneCatalogs.SPEAKER) ||
                        dialogue == null ||
                        speaker == null ||
                        context.trigger !in
                            setOf(SceneTrigger.LEFT_CLICK, SceneTrigger.RIGHT_CLICK) ||
                        !policy.isEligible(context.player)
                ) {
                    CompletableFuture.completedFuture(
                        SceneActionResult.Rejected("Player or arguments rejected")
                    )
                } else {
                    installed.start(context, dialogue, speaker)
                }
            }
        }

    override fun handlerFor(key: ActionKey): SceneActionHandler? =
        when (key) {
            LobbySceneCatalogs.OPEN_NAVIGATOR -> navigatorHandler
            LobbySceneCatalogs.TOGGLE_QUEUE -> toggleHandler
            LobbySceneCatalogs.OPEN_DUEL_MENU -> duelHandler
            LobbySceneCatalogs.START_DIALOGUE -> dialogueHandler
            else -> null
        }
}

internal class LobbyScenePlayerPolicy(
    private val instance: Instance,
    private val permissions: Permissions?,
) : ScenePlayerPolicy {
    override fun isEligible(player: Player) = player.isOnline && player.instance === instance

    override fun hasPermission(player: Player, permission: String) =
        permissions?.hasPermission(player.uuid, permission) ?: false
}

internal fun PreparedLobbyScene.runtimeRequest(
    instance: Instance,
    services: ServiceRegistry,
    installedDialogue: LobbySceneDialogueStarter? = null,
): SceneRuntimeRequest {
    val applicationKeys =
        scene.boundActions().filterIsInstance<ApplicationAction>().map { it.key }.toSet()
    val navigator =
        if (LobbySceneCatalogs.OPEN_NAVIGATOR in applicationKeys) {
            services.get<LobbyNavigatorService>()
                ?: error(
                    "Selected lobby scene requires the installed navigator service for ${LobbySceneCatalogs.OPEN_NAVIGATOR.value}"
                )
        } else null
    val policy = LobbyScenePlayerPolicy(instance, services.get<Permissions>())
    val queues =
        if (
            applicationKeys.any {
                it == LobbySceneCatalogs.TOGGLE_QUEUE || it == LobbySceneCatalogs.OPEN_DUEL_MENU
            }
        ) {
            services.get<LobbyQueueService>()
                ?: error("Selected lobby scene requires the installed queue service")
        } else null
    val dialogueStarter =
        if (LobbySceneCatalogs.START_DIALOGUE in applicationKeys) {
            installedDialogue
                ?: services.get<LobbySceneDialogueStarter>()
                ?: error("Selected lobby scene requires the installed dialogue starter")
        } else null
    val registry = LobbySceneActions(navigator, policy, queues, dialogueStarter)
    // Resolve handlers without executing them, using the exact immutable registry passed below.
    applicationKeys.forEach { key ->
        check(registry.handlerFor(key) != null) {
            "Missing lobby scene action handler for ${key.value}"
        }
    }
    return SceneRuntimeRequest(
        scene,
        assets,
        actions,
        identity,
        instance,
        renderers,
        LobbySceneEffects(assets),
        policy,
        registry,
        // Spread complete NPC construction across ticks; keep each model's parts together.
        config = SceneRuntimeConfig(transitionBudgetPerTick = 1),
    )
}
