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
    fun `real Guide reveals organic voice privately and closes without generic typing`() {
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
                tickUntil(fixture.instance) { LobbyDialogues.GUIDE_SPEECH in sounds(player) }
                assertTrue(LobbyDialogues.GUIDE_SPEECH in sounds(player), sounds(player).toString())
                val voice =
                    player.connection.packets.filterIsInstance<SoundEffectPacket>().first {
                        it.soundEvent().key().asString() == LobbyDialogues.GUIDE_SPEECH
                    }
                assertEquals(net.kyori.adventure.sound.Sound.Source.VOICE, voice.source())
                assertEquals(0.0, voice.origin().x())
                assertEquals(1.6, voice.origin().y())
                assertEquals(0.0, voice.origin().z())
                assertFalse(sounds(player).any { it.contains("typing") }, sounds(player).toString())
                assertFalse(LobbyDialogues.GUIDE_SPEECH in sounds(neighbor))
                val before =
                    player.connection.packets.count { it.javaClass.simpleName == "StopSoundPacket" }
                await(owner.closeAsync(), fixture.instance)
                assertTrue(
                    player.connection.packets.count {
                        it.javaClass.simpleName == "StopSoundPacket"
                    } > before
                )
                val stopped =
                    player.connection.packets
                        .filterIsInstance<
                            net.minestom.server.network.packet.server.play.StopSoundPacket
                        >()
                        .last { it.sound() == LobbyDialogues.GUIDE_SPEECH }
                assertEquals(net.kyori.adventure.sound.Sound.Source.VOICE, stopped.source())
                val spoken = sounds(player).count { it == LobbyDialogues.GUIDE_SPEECH }
                repeat(20) { fixture.instance.tick(0) }
                assertEquals(spoken, sounds(player).count { it == LobbyDialogues.GUIDE_SPEECH })
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
