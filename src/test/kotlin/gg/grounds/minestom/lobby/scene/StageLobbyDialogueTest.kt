package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneHand
import gg.grounds.scene.format.SceneId
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.StringArgument
import gg.grounds.scene.minestom.SceneActionContext
import gg.grounds.scene.minestom.SceneActionResult
import gg.grounds.scene.minestom.SceneRuntimeIdentity
import gg.grounds.scene.minestom.SceneViewerVisualState
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.play.SystemChatPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class StageLobbyDialogueTest {
    @Test
    fun `preview requires explicit Stage authorization`() {
        assertFalse(stageDialoguePreviewEnabled(emptyMap()))
        val enabled = mapOf("GROUNDS_DIALOGUE_STAGE_DEMO_ENABLED" to "true")
        assertThrows(IllegalArgumentException::class.java) {
            stageDialoguePreviewEnabled(
                enabled + ("GROUNDS_PERMISSION_ENVIRONMENT" to "production")
            )
        }
        assertTrue(
            stageDialoguePreviewEnabled(enabled + ("GROUNDS_PERMISSION_ENVIRONMENT" to "stage"))
        )
    }

    @Test
    fun `actual library starts independent chat sessions only at configured station`() {
        RendererFixture().use { fixture ->
            val station = LocalId("demo-bounce")
            val identity = SceneRuntimeIdentity(SceneId("test:stage"), "lobby/mainlobby", 19)
            val starter =
                StageLobbyDialogue(fixture.instance, identity, station, Pos.ZERO) { "de-DE" }
            try {
                val handler =
                    LobbySceneActions(
                            null,
                            LobbyScenePlayerPolicy(fixture.instance, null),
                            dialogueStarter = starter,
                        )
                        .handlerFor(LobbySceneCatalogs.START_DIALOGUE)!!
                val first = fixture.player(Pos(0.0, 0.0, 2.0))
                val second = fixture.player(Pos(0.0, 0.0, 2.0))
                val context =
                    SceneActionContext(
                        identity,
                        first,
                        station,
                        SceneTrigger.RIGHT_CLICK,
                        SceneHand.MAIN,
                        System.nanoTime(),
                        mapOf(
                            LobbySceneCatalogs.DIALOGUE_ID to
                                StringArgument(StageLobbyDialogue.DIALOGUE_ID),
                            LobbySceneCatalogs.SPEAKER to
                                StringArgument(StageLobbyDialogue.SPEAKER_ROLE),
                        ),
                        SceneViewerVisualState(),
                    )
                assertInstanceOf(
                    SceneActionResult.Rejected::class.java,
                    await(
                        handler.execute(context.copy(elementId = LocalId("other"))),
                        fixture.instance,
                    ),
                )
                assertEquals(
                    SceneActionResult.Success,
                    await(handler.execute(context), fixture.instance),
                )
                assertInstanceOf(
                    SceneActionResult.Rejected::class.java,
                    await(handler.execute(context), fixture.instance),
                )
                assertEquals(
                    SceneActionResult.Success,
                    await(handler.execute(context.copy(player = second)), fixture.instance),
                )
                val firstOption = commandFor(first, "Was kann ich hier testen?")
                assertNotEquals(firstOption, commandFor(second, "Was kann ich hier testen?"))
                MinecraftServer.getCommandManager().execute(first, firstOption.removePrefix("/"))
                val response = "Diese anklickbaren Chat-Antworten"
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!received(first, response) && System.nanoTime() < deadline) {
                    fixture.instance.tick(0)
                    MinecraftServer.getSchedulerManager().processTick()
                    Thread.yield()
                }
                assertTrue(received(first, response))
                assertFalse(received(second, response))
            } finally {
                await(starter.closeAsync(), fixture.instance)
            }
        }
    }

    private fun TestPlayer.messages(): List<Component> =
        connection.packets.filterIsInstance<SystemChatPacket>().map { it.message() }

    private fun Component.parts(): Sequence<Component> =
        sequenceOf(this) + children().asSequence().flatMap { it.parts() }

    private fun commandFor(player: TestPlayer, label: String): String =
        player
            .messages()
            .asReversed()
            .asSequence()
            .flatMap { it.parts() }
            .filterIsInstance<TextComponent>()
            .filter { it.content() == label }
            .mapNotNull { it.clickEvent()?.payload() as? ClickEvent.Payload.Text }
            .first()
            .value()

    private fun received(player: TestPlayer, text: String): Boolean =
        player.messages().any {
            PlainTextComponentSerializer.plainText().serialize(it).contains(text)
        }

    private fun <T> await(stage: CompletionStage<T>, instance: Instance): T {
        val result = stage.toCompletableFuture()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!result.isDone && System.nanoTime() < deadline) {
            instance.tick(0)
            MinecraftServer.getSchedulerManager().processTick()
            Thread.yield()
        }
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
