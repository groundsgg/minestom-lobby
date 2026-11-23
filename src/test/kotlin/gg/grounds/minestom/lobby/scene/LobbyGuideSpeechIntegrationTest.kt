package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.play.EntitySoundEffectPacket
import net.minestom.server.network.packet.server.play.SoundEffectPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbyGuideSpeechIntegrationTest {
    @Test
    fun `real Guide admits NPC presentation and closes without generic typing`() {
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val neighbor = fixture.player(Pos(1.0, 0.0, 2.0))
            val identity = SceneRuntimeIdentity(SceneId("test:guide-speech"), "lobby/mainlobby", 19)
            val assets = StageDialogueAssets.load()
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    identity,
                    listOf(
                        LobbyDialogueBinding(
                            LocalId("spawn-guide"),
                            Pos.ZERO,
                            LobbyGuideContent.DIALOGUE_ID,
                            LobbyGuideContent.SPEAKER_ROLE,
                        )
                    ),
                    assets = assets,
                    proxyPackEnabled = true,
                    reducedMotion = { true },
                    locale = { "en" },
                    observeLocale = { _, _ -> AutoCloseable {} },
                )
            fun sounds(target: TestPlayer) =
                target.connection.packets.mapNotNull {
                    when (it) {
                        is EntitySoundEffectPacket -> it.soundEvent().key().asString()
                        is SoundEffectPacket -> it.soundEvent().key().asString()
                        else -> null
                    }
                }
            val context =
                SceneActionContext(
                    identity,
                    player,
                    LocalId("spawn-guide"),
                    SceneTrigger.RIGHT_CLICK,
                    SceneHand.MAIN,
                    System.nanoTime(),
                    emptyMap(),
                    SceneViewerVisualState(),
                )
            try {
                owner.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                val starting =
                    owner.start(
                        context,
                        LobbyGuideContent.DIALOGUE_ID,
                        LobbyGuideContent.SPEAKER_ROLE,
                    )
                await(starting, fixture.instance)
                tickUntil(fixture.instance) {
                    owner.presentation(player)?.effectiveSurface == "npc"
                }
                assertFalse(sounds(player).any { it.contains("typing") }, sounds(player).toString())
                assertFalse(sounds(neighbor).any { it.startsWith("dialogue:guide/") })
                await(owner.closeAsync(), fixture.instance)
                val spoken = sounds(player).count { it.startsWith("dialogue:guide/") }
                repeat(20) { fixture.instance.tick(0) }
                assertEquals(spoken, sounds(player).count { it.startsWith("dialogue:guide/") })
            } finally {
                await(owner.closeAsync(), fixture.instance)
            }
        }
    }

    private fun tickUntil(instance: Instance, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            instance.tick(0)
            MinecraftServer.getSchedulerManager().processTick()
            Thread.yield()
        }
        assertTrue(predicate())
    }

    private fun <T> await(stage: CompletionStage<T>, instance: Instance): T {
        val result = stage.toCompletableFuture()
        tickUntil(instance) { result.isDone }
        return result.get(1, TimeUnit.SECONDS)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun init() {
            MinecraftServer.init()
        }
    }
}
