package gg.grounds.minestom.lobby.scene

/** Fixed startup admission, shared by the composition and every direct owner entry point. */
internal class LobbyDialogueAdmission(private val required: Boolean) {
    @Volatile private var decision: LobbyDialogueRuntimeVerification.Decision? = null

    val allowed: Boolean
        get() =
            when (decision) {
                is LobbyDialogueRuntimeVerification.Admitted -> true
                LobbyDialogueRuntimeVerification.Unconfigured -> !required
                is LobbyDialogueRuntimeVerification.Denied -> false
                null -> !required
            }

    @Synchronized
    fun complete(result: LobbyDialogueRuntimeVerification.Decision) {
        check(decision == null) { "Dialogue admission already completed" }
        require(!required || result != LobbyDialogueRuntimeVerification.Unconfigured) {
            "Configured dialogue admission requires verification"
        }
        decision = result
    }
}
