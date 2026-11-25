package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.LobbyNavigatorService
import gg.grounds.lobby.LobbyQueueService
import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.get
import gg.grounds.scene.minestom.SceneRuntimeRequest
import net.minestom.server.instance.Instance

/** The same finite registries and owner registrations feed runtime and metadata export. */
internal class LobbySceneComposition
private constructor(val request: SceneRuntimeRequest, val dialogues: LobbyDialogues?) {
    val registrations = dialogues?.registrations ?: emptyMap()
    val actionRegistry
        get() = request.actionRegistry

    companion object {
        fun create(
            prepared: PreparedLobbyScene,
            instance: Instance,
            services: ServiceRegistry,
            stagePreview: Boolean = false,
            admission: LobbyDialogueAdmission = LobbyDialogueAdmission(false),
        ): LobbySceneComposition =
            prepare(prepared, instance, services, stagePreview, admission, null)

        /** Describes installed handlers with a permanently closed session path and no graph. */
        fun prepareMetadata(
            prepared: PreparedLobbyScene,
            instance: Instance,
            services: ServiceRegistry,
            contract: LobbyDialogueHostContract,
        ): LobbySceneComposition =
            prepare(prepared, instance, services, false, LobbyDialogueAdmission(true), contract)

        private fun prepare(
            prepared: PreparedLobbyScene,
            instance: Instance,
            services: ServiceRegistry,
            stagePreview: Boolean,
            admission: LobbyDialogueAdmission,
            metadataContract: LobbyDialogueHostContract?,
        ): LobbySceneComposition {
            val selected =
                if (stagePreview) prepared.withStageDialoguePreview().prepared else prepared
            val bindings = selected.dialogueBindings(metadataContract)
            val owner =
                if (bindings.isEmpty()) null
                else
                    LobbyDialogues(
                        instance,
                        selected.identity,
                        bindings,
                        services.get<LobbyNavigatorService>(),
                        services.get<LobbyQueueService>(),
                        admission = admission,
                        metadataContract = metadataContract,
                    )
            try {
                val labeled =
                    if (owner == null) selected else selected.withDialogueLabels(owner, bindings)
                return LobbySceneComposition(
                    labeled.runtimeRequest(instance, services, owner),
                    owner,
                )
            } catch (failure: Throwable) {
                try {
                    owner?.closeAsync()?.toCompletableFuture()?.join()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }
}
