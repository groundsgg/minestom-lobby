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
private constructor(
    val contract: LobbyDialogueHostContract,
    val dialogueId: String,
    providers: List<ActionProvider>,
) {
    class ActionProvider
    internal constructor(
        val key: String,
        private val operation: ((Player) -> CompletionStage<Boolean>)?,
        private val service: Any? = null,
    ) {
        internal val serviceType: Class<*>?
            get() = service?.javaClass

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

    internal fun requireQualifiedServices(): List<Class<*>> {
        requireExportReady()
        return actionProviders.map { provider ->
            val expected =
                when (provider.key) {
                    LobbyGuideContent.NAVIGATOR_ACTION ->
                        "gg.grounds.lobby.DefaultLobbyNavigatorService"
                    LobbyGuideContent.DUELS_ACTION -> "gg.grounds.lobby.DefaultLobbyQueueService"
                    else -> error("Unqualified action provider")
                }
            val actual = requireNotNull(provider.serviceType)
            require(
                actual ===
                    Class.forName(
                        expected,
                        false,
                        gg.grounds.lobby.GroundsPluginLobbyProvider::class.java.classLoader,
                    )
            ) {
                "Installed dialogue service is not the qualified provider implementation"
            }
            actual
        }
    }

    companion object {
        fun create(
            dialogueId: String,
            navigator: LobbyNavigatorService? = null,
            queues: LobbyQueueService? = null,
        ): LobbyDialogueHostRegistration =
            create(LobbyDialogueHostContract.forBuiltIn(dialogueId), dialogueId, navigator, queues)

        fun create(
            contract: LobbyDialogueHostContract,
            dialogueId: String,
            navigator: LobbyNavigatorService? = null,
            queues: LobbyQueueService? = null,
        ): LobbyDialogueHostRegistration {
            require(dialogueId.isNotBlank()) { "Dialogue ID is required" }
            require(
                contract == LobbyDialogueHostContract.GUIDE ||
                    dialogueId == contract.builtInDialogueId
            ) {
                "Stage contract supports only the built-in dialogue"
            }
            require(
                dialogueId !in
                    LobbyDialogueHostContract.entries
                        .filter { it != contract }
                        .map { it.builtInDialogueId }
            ) {
                "Built-in dialogue cannot select another host contract"
            }
            val providers =
                when (contract) {
                    LobbyDialogueHostContract.GUIDE ->
                        listOf(
                            ActionProvider(
                                LobbyGuideContent.NAVIGATOR_ACTION,
                                navigator?.let { it::open },
                                navigator,
                            ),
                            ActionProvider(
                                LobbyGuideContent.DUELS_ACTION,
                                queues?.let { it::openDuels },
                                queues,
                            ),
                        )
                    LobbyDialogueHostContract.STAGE -> emptyList()
                }
            return LobbyDialogueHostRegistration(contract, dialogueId, providers)
        }
    }
}
