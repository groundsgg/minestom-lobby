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
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
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
                val label = "Was kann ich hier testen?"
                val firstOption = commandFor(first, label)
                assertNotEquals(firstOption, commandFor(second, label))
                MinecraftServer.getCommandManager().execute(first, firstOption.removePrefix("/"))
                tickUntil(fixture.instance) { received(first, "Auswählen") }
                assertTrue(received(first, "Auswählen"))
                val choice = commandFor(first, label)
                MinecraftServer.getCommandManager().execute(first, choice.removePrefix("/"))
                val response = "Diese anklickbaren Chat-Antworten"
                tickUntil(fixture.instance) { received(first, response) }
                assertTrue(received(first, response))
                assertFalse(received(second, response))
            } finally {
                await(starter.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `confirmed proxy pin selects NPC and revocation returns to chat`() {
        RendererFixture().use { fixture ->
            val station = LocalId("demo-bounce")
            val identity = SceneRuntimeIdentity(SceneId("test:npc"), "lobby/mainlobby", 19)
            val assets = StageDialogueAssets.load()
            val starter =
                StageLobbyDialogue(fixture.instance, identity, station, Pos.ZERO, assets, true) {
                    "en"
                }
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val context =
                SceneActionContext(
                    identity,
                    player,
                    station,
                    SceneTrigger.RIGHT_CLICK,
                    SceneHand.MAIN,
                    System.nanoTime(),
                    emptyMap(),
                    SceneViewerVisualState(),
                )
            try {
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        starter.start(
                            context,
                            StageLobbyDialogue.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                        fixture.instance,
                    ),
                )
                assertEquals("chat", starter.presentation(player)!!.effectiveSurface)
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                tickUntil(fixture.instance) {
                    starter.presentation(player)!!.effectiveSurface == "npc"
                }
                assertEquals(
                    "npc",
                    starter.presentation(player)!!.effectiveSurface,
                    starter.presentation(player).toString(),
                )
                starter.confirmPack(player, DialoguePackReadiness.State(2, false, assets.pin()))
                tickUntil(fixture.instance) {
                    starter.presentation(player)!!.effectiveSurface == "chat"
                }
                assertEquals("chat", starter.presentation(player)!!.effectiveSurface)
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                assertEquals("chat", starter.presentation(player)!!.effectiveSurface)
            } finally {
                await(starter.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `NPC reveals dialogue gradually when the pack is confirmed before start`() {
        assertNpcReveal(packReadyBeforeStart = true)
    }

    @Test
    fun `NPC reveals dialogue gradually when the pack is confirmed during a session`() {
        assertNpcReveal(packReadyBeforeStart = false)
    }

    private fun assertNpcReveal(packReadyBeforeStart: Boolean) {
        RendererFixture().use { fixture ->
            val station = LocalId("demo-bounce")
            val identity = SceneRuntimeIdentity(SceneId("test:reveal"), "lobby/mainlobby", 19)
            val assets = StageDialogueAssets.load()
            val starter =
                StageLobbyDialogue(fixture.instance, identity, station, Pos.ZERO, assets, true) {
                    "en"
                }
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val ready = DialoguePackReadiness.State(1, true, assets.pin())
            val context =
                SceneActionContext(
                    identity,
                    player,
                    station,
                    SceneTrigger.RIGHT_CLICK,
                    SceneHand.MAIN,
                    System.nanoTime(),
                    emptyMap(),
                    SceneViewerVisualState(),
                )
            try {
                if (packReadyBeforeStart) starter.confirmPack(player, ready)
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        starter.start(
                            context,
                            StageLobbyDialogue.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                        fixture.instance,
                    ),
                )
                if (!packReadyBeforeStart) starter.confirmPack(player, ready)

                val serializer = PlainTextComponentSerializer.plainText()
                fun npcText() =
                    player.connection.packets
                        .filterIsInstance<EntityMetaDataPacket>()
                        .flatMap { it.entries().values }
                        .mapNotNull { it.value() as? Component }
                        .map { serializer.serialize(it) }

                val opening = "Hello! This"
                tickUntil(fixture.instance) { npcText().any { it.contains(opening) } }
                val dialogueFrames = npcText().filter { it.contains("Hello!") }
                assertTrue(dialogueFrames.any { it.contains(opening) }, dialogueFrames.toString())
                assertFalse(dialogueFrames.first().contains(opening), dialogueFrames.toString())
            } finally {
                await(starter.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `proxy receiver applies only latest revision to current player connection`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val applied = mutableListOf<DialoguePackReadiness.State>()
            val receiver = DialoguePackReadiness { _, state -> applied += state }
            fun payload(revision: Long, ready: Boolean) =
                java.nio.ByteBuffer.allocate(90)
                    .put(1)
                    .putLong(player.uuid.mostSignificantBits)
                    .putLong(player.uuid.leastSignificantBits)
                    .putLong(revision)
                    .put(if (ready) 1.toByte() else 0.toByte())
                    .put("a".repeat(64).toByteArray(Charsets.US_ASCII))
                    .array()
            receiver.accept(player, payload(1, true))
            receiver.accept(player, payload(2, false))
            receiver.accept(player, payload(1, true))
            MinecraftServer.getSchedulerManager().processTick()
            assertEquals(listOf(DialoguePackReadiness.State(2, false, "a".repeat(64))), applied)
        }
    }

    private fun tickUntil(instance: Instance, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            instance.tick(0)
            MinecraftServer.getSchedulerManager().processTick()
            Thread.yield()
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
