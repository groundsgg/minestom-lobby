package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbyDialogueAdmissionTest {
    @Test
    fun `required admission is closed until verified and cannot reopen after denial`() {
        val gate = LobbyDialogueAdmission(true)
        assertFalse(gate.allowed)
        gate.complete(LobbyDialogueRuntimeVerification.Denied("configuration_invalid"))
        assertFalse(gate.allowed)
        assertThrows(IllegalStateException::class.java) {
            gate.complete(LobbyDialogueRuntimeVerification.Admitted("verified"))
        }
        assertFalse(gate.allowed)
    }

    @Test
    fun `required admission rejects unconfigured fallback and admits verified decision once`() {
        val gate = LobbyDialogueAdmission(true)
        assertThrows(IllegalArgumentException::class.java) {
            gate.complete(LobbyDialogueRuntimeVerification.Unconfigured)
        }
        assertFalse(gate.allowed)
        gate.complete(LobbyDialogueRuntimeVerification.Admitted("verified"))
        assertTrue(gate.allowed)
        assertThrows(IllegalStateException::class.java) {
            gate.complete(LobbyDialogueRuntimeVerification.Denied("inputs_changed"))
        }
        assertTrue(gate.allowed)
        assertTrue(LobbyDialogueAdmission(false).allowed)
    }

    @Test
    fun `direct owner start creates no presentation or locale subscription while pending or denied`() {
        RendererFixture().use { fixture ->
            val gate = LobbyDialogueAdmission(true)
            val player = fixture.player(Pos.ZERO)
            val identity = SceneRuntimeIdentity(SceneId("test:admission"), "test:map", 1)
            var subscriptions = 0
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    identity,
                    listOf(
                        LobbyDialogueBinding(
                            LocalId("guide"),
                            Pos.ZERO,
                            StageDialogueContent.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        )
                    ),
                    observeLocale = { _, _ ->
                        subscriptions++
                        AutoCloseable {}
                    },
                    admission = gate,
                )
            try {
                val context =
                    SceneActionContext(
                        identity,
                        player,
                        LocalId("guide"),
                        SceneTrigger.RIGHT_CLICK,
                        SceneHand.MAIN,
                        0,
                        emptyMap(),
                        SceneViewerVisualState(),
                    )
                repeat(2) {
                    assertInstanceOf(
                        SceneActionResult.Rejected::class.java,
                        await(
                            owner.start(
                                context,
                                StageDialogueContent.DIALOGUE_ID,
                                StageLobbyDialogue.SPEAKER_ROLE,
                            ),
                            fixture.instance,
                        ),
                    )
                    assertNull(owner.presentation(player))
                    assertEquals(0, subscriptions)
                    if (it == 0)
                        gate.complete(
                            LobbyDialogueRuntimeVerification.Denied("runtime_scene_invalid")
                        )
                }
            } finally {
                await(owner.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `verified gate admits actual owner without replacing it`() {
        RendererFixture().use { fixture ->
            val gate = LobbyDialogueAdmission(true)
            val player = fixture.player(Pos.ZERO)
            val identity = SceneRuntimeIdentity(SceneId("test:admitted"), "test:map", 1)
            var subscriptions = 0
            var unsubscribed = 0
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    identity,
                    listOf(
                        LobbyDialogueBinding(
                            LocalId("guide"),
                            Pos.ZERO,
                            StageDialogueContent.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        )
                    ),
                    observeLocale = { _, _ ->
                        subscriptions++
                        AutoCloseable { unsubscribed++ }
                    },
                    admission = gate,
                )
            try {
                gate.complete(LobbyDialogueRuntimeVerification.Admitted("verified"))
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        owner.start(
                            SceneActionContext(
                                identity,
                                player,
                                LocalId("guide"),
                                SceneTrigger.RIGHT_CLICK,
                                SceneHand.MAIN,
                                0,
                                emptyMap(),
                                SceneViewerVisualState(),
                            ),
                            StageDialogueContent.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                        fixture.instance,
                    ),
                )
                assertNotNull(owner.presentation(player))
                assertEquals(1, subscriptions)
            } finally {
                await(owner.closeAsync(), fixture.instance)
            }
            assertEquals(1, unsubscribed)
            assertNull(owner.presentation(player))
        }
    }

    private fun <T> await(
        stage: java.util.concurrent.CompletionStage<T>,
        instance: net.minestom.server.instance.Instance,
    ): T {
        val future = stage.toCompletableFuture()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!future.isDone && System.nanoTime() < deadline) {
            instance.tick(0)
            MinecraftServer.getSchedulerManager().processTick()
            Thread.yield()
        }
        return future.get(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
