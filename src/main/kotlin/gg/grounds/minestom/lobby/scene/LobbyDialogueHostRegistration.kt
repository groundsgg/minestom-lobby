package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.port.ActionHandler
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.lobby.LobbyNavigatorService
import gg.grounds.lobby.LobbyQueueService
import java.util.Collections
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player

/** Shares built-in provider metadata and the actual installed menu operations. */
internal class LobbyDialogueHostRegistration
private constructor(val dialogueId: String, providers: List<ActionProvider>) {
    class ActionProvider
    internal constructor(
        val key: String,
        private val operation: ((Player) -> CompletionStage<Boolean>)?,
    ) {
        val spec = HostCatalog.ProviderSpec(emptyMap(), false)
        val installed: Boolean
            get() = operation != null

        fun open(player: Player): CompletionStage<Boolean>? = operation?.invoke(player)
    }

    val actionProviders: List<ActionProvider> = Collections.unmodifiableList(providers.toList())
    val speakers: Set<String> = setOf(LobbyGuideContent.SPEAKER_ROLE)
    val gestures: Set<String> = emptySet()
    val capabilities: Set<String> = emptySet()

    fun catalog(generation: String) =
        HostCatalog(
            generation,
            speakers,
            emptyMap(),
            actionProviders.associate { it.key to it.spec },
            gestures,
            capabilities,
            false,
        )

    fun bindActions(guardedHandler: (ActionProvider) -> ActionHandler): Map<String, ActionHandler> =
        actionProviders.associate { it.key to guardedHandler(it) }

    /** Static validation can describe unavailable menus; a prepared profile cannot claim them. */
    fun requireExportReady() {
        actionProviders.forEach { provider ->
            require(provider.installed) { "Missing installed dialogue provider: ${provider.key}" }
        }
    }

    companion object {
        fun create(
            dialogueId: String,
            navigator: LobbyNavigatorService? = null,
            queues: LobbyQueueService? = null,
        ): LobbyDialogueHostRegistration {
            val providers =
                when (dialogueId) {
                    LobbyGuideContent.DIALOGUE_ID ->
                        listOf(
                            ActionProvider(
                                LobbyGuideContent.NAVIGATOR_ACTION,
                                navigator?.let { it::open },
                            ),
                            ActionProvider(
                                LobbyGuideContent.DUELS_ACTION,
                                queues?.let { it::openDuels },
                            ),
                        )
                    StageDialogueContent.DIALOGUE_ID -> emptyList()
                    else -> throw IllegalArgumentException("Unsupported lobby dialogue $dialogueId")
                }
            return LobbyDialogueHostRegistration(dialogueId, providers)
        }
    }
}
