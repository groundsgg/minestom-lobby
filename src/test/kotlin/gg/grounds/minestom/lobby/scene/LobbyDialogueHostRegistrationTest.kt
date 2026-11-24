package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.port.ActionHandler
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.lobby.LobbyNavigatorService
import gg.grounds.lobby.LobbyQueueService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LobbyDialogueHostRegistrationTest {
    private val guide = LobbyGuideContent.DIALOGUE_ID
    private val stage = StageDialogueContent.DIALOGUE_ID

    @Test
    fun `guide metadata and guarded bindings retain both provider specs with missing services`() {
        val registration = LobbyDialogueHostRegistration.create(guide)
        val catalog = registration.catalog("test-generation")
        val expected =
            mapOf(
                "grounds:lobby/open_navigator" to HostCatalog.ProviderSpec(emptyMap(), false),
                "grounds:lobby/open_duel_menu" to HostCatalog.ProviderSpec(emptyMap(), false),
            )
        assertEquals(expected, catalog.actions())
        assertEquals(
            expected,
            LobbyGuideContent.validated("test-generation", registration).catalog().actions(),
        )
        assertEquals("test-generation", catalog.generation())
        assertEquals(setOf("guide"), catalog.speakers())
        assertTrue(catalog.conditions().isEmpty())
        assertTrue(catalog.gestures().isEmpty())
        assertTrue(catalog.capabilities().isEmpty())
        assertFalse(catalog.durableOutcomes())
        val bound =
            registration.bindActions { provider ->
                object : ActionHandler {
                        override fun execute(request: ActionHandler.ActionRequest) =
                            CompletableFuture.completedFuture(
                                ActionHandler.ActionResult.UNAVAILABLE
                            )

                        override fun reconcile(reference: ActionHandler.ActionRef) =
                            CompletableFuture.completedFuture(
                                ActionHandler.ActionStatus.UNAVAILABLE
                            )
                    }
                    .also { assertTrue(provider.key in expected) }
            }
        assertEquals(expected.keys, bound.keys)
        assertThrows(UnsupportedOperationException::class.java) {
            (registration.actionProviders as MutableList).clear()
        }
        assertThrows(IllegalArgumentException::class.java) { registration.requireExportReady() }
    }

    @Test
    fun `stage metadata and bindings stay empty without requiring guide menu services`() {
        val registration = LobbyDialogueHostRegistration.create(stage)
        registration.requireExportReady()
        assertTrue(registration.catalog("stage").actions().isEmpty())
        assertTrue(
            StageDialogueContent.validated("stage", registration).catalog().actions().isEmpty()
        )
        assertTrue(registration.bindActions { error("Stage has no actions") }.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            LobbyGuideContent.validated("stage", registration)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StageDialogueContent.validated("guide", LobbyDialogueHostRegistration.create(guide))
        }
    }

    @Test
    fun `unknown dialogue cannot inherit guide providers`() {
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueHostRegistration.create("story:unknown")
        }
    }

    @Test
    fun `metadata preparation never calls installed menu operations`() {
        val navigator =
            object : LobbyNavigatorService {
                override fun open(player: Player): CompletionStage<Boolean> =
                    fail("Metadata preparation must not open the navigator")
            }
        val queues =
            object : LobbyQueueService {
                override fun toggleQueue(player: Player, modeId: String): CompletionStage<Boolean> =
                    fail("Metadata preparation must not join queues")

                override fun openDuels(player: Player): CompletionStage<Boolean> =
                    fail("Metadata preparation must not open duels")
            }
        val registration = LobbyDialogueHostRegistration.create(guide, navigator, queues)
        registration.requireExportReady()
        assertEquals(
            2,
            LobbyGuideContent.validated("metadata", registration).catalog().actions().size,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueHostRegistration.create(guide, navigator, null).requireExportReady()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueHostRegistration.create(guide, null, queues).requireExportReady()
        }
    }
}
