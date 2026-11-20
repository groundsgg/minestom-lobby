package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneHand
import gg.grounds.scene.format.SceneId
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.StringArgument
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneActionContext
import gg.grounds.scene.minestom.SceneActionResult
import gg.grounds.scene.minestom.SceneAssetRenderContext
import gg.grounds.scene.minestom.SceneAssetRendererFactory
import gg.grounds.scene.minestom.SceneAssetRendererRegistry
import gg.grounds.scene.minestom.SceneRuntimeIdentity
import gg.grounds.scene.minestom.SceneViewerVisualState
import gg.grounds.scene.minestom.ScopedSceneAssetRendererFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityType
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.instance.AddEntityToInstanceEvent
import net.minestom.server.event.player.PlayerTickEvent
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
            val other = fixture.player(Pos(1.0, 0.0, 2.0))
            val lifetime = TestLabelLifetime()
            val label =
                StageDialogueLabel(station, Component.text("Ordinary label"), Pos.ZERO)
                    .attach(fixture.instance, lifetime, starter::npcDialogueViewers)
            tickUntil(fixture.instance) { player in label.viewers && other in label.viewers }
            assertTrue(player in label.viewers)
            assertTrue(other in label.viewers)
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
                label.update(0)
                assertFalse(player in label.viewers)
                assertTrue(other in label.viewers)
                starter.confirmPack(player, DialoguePackReadiness.State(2, false, assets.pin()))
                tickUntil(fixture.instance) {
                    starter.presentation(player)!!.effectiveSurface == "chat"
                }
                assertEquals("chat", starter.presentation(player)!!.effectiveSurface)
                label.update(0)
                assertTrue(player in label.viewers)
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                assertEquals("chat", starter.presentation(player)!!.effectiveSurface)

                starter.confirmPack(player, DialoguePackReadiness.State(3, true, assets.pin()))
                tickUntil(fixture.instance) {
                    starter.presentation(player)!!.effectiveSurface == "npc"
                }
                label.update(0)
                assertFalse(player in label.viewers)
                await(player.teleport(Pos(0.0, 0.0, 8.0)), fixture.instance)
                tickUntil(fixture.instance) {
                    EventDispatcher.call(PlayerTickEvent(player))
                    starter.presentation(player)?.diagnostic == "CLOSED"
                }
                assertEquals("CLOSED", starter.presentation(player)?.diagnostic)
                await(player.teleport(Pos(0.0, 0.0, 2.0)), fixture.instance)
                label.update(0)
                assertTrue(player in label.viewers)
                assertTrue(other in label.viewers)
            } finally {
                await(starter.closeAsync(), fixture.instance)
                lifetime.invalidate()
                await(checkNotNull(lifetime.cleanup), fixture.instance)
                assertTrue(label.isRemoved)
            }
        }
    }

    @Test
    fun `label setup failure does not start the delegated renderer`() {
        RendererFixture().use { fixture ->
            val station = LocalId("demo-bounce")
            var delegateStarted = false
            val delegate = SceneAssetRendererRegistry { _, _ ->
                SceneAssetRendererFactory {
                    delegateStarted = true
                    CompletableFuture<RenderedAssetHandle>()
                }
            }
            val renderers =
                StageDialogueLabel(station, Component.text("Title"), Pos.ZERO).renderers(
                    delegate,
                    guideKey,
                ) {
                    error("Label setup failed")
                }
            val renderer =
                renderers.rendererFor(guideKey, AssetKind.NPC_BODY)
                    as ScopedSceneAssetRendererFactory
            val context =
                SceneAssetRenderContext(fixture.instance, station, null, guideKey, sceneTransform())

            assertThrows(IllegalStateException::class.java) {
                renderer.createScoped(context, TestLabelLifetime())
            }
            assertFalse(delegateStarted)
        }
    }

    @Test
    fun `station label does not spawn for an invalidated scene renderer`() {
        RendererFixture().use { fixture ->
            val lifetime = TestLabelLifetime().also { it.invalidate() }
            val label =
                StageDialogueLabel(LocalId("demo-bounce"), Component.text("Title"), Pos.ZERO)
                    .attach(fixture.instance, lifetime) { emptySet() }
            await(checkNotNull(lifetime.cleanup), fixture.instance)
            assertFalse(label in fixture.instance.entities)
        }
    }

    @Test
    fun `cancelled station label spawn reports failure and drains cleanup`() {
        RendererFixture().use { fixture ->
            fixture.instance.eventNode().addListener(AddEntityToInstanceEvent::class.java) {
                if (it.entity.entityType == EntityType.TEXT_DISPLAY) it.isCancelled = true
            }
            val lifetime = TestLabelLifetime()
            val label =
                StageDialogueLabel(LocalId("demo-bounce"), Component.text("Title"), Pos.ZERO)
                    .attach(fixture.instance, lifetime) { emptySet() }
            assertNotNull(lifetime.failure)
            await(checkNotNull(lifetime.cleanup), fixture.instance)
            assertTrue(label.isRemoved)
            assertFalse(label in fixture.instance.entities)
        }
    }

    private class TestLabelLifetime : gg.grounds.scene.minestom.SceneRendererLifetime {
        private var current = true
        private var callback: Runnable? = null
        var failure: Throwable? = null
            private set

        var cleanup: CompletionStage<Void>? = null
            private set

        fun invalidate() {
            current = false
            callback?.run()
        }

        override fun isCurrent() = current

        override fun runIfCurrent(operation: Runnable): Boolean {
            if (!current) return false
            operation.run()
            return true
        }

        override fun onInvalidated(callback: Runnable): AutoCloseable {
            if (current) this.callback = callback else callback.run()
            return AutoCloseable { this.callback = null }
        }

        override fun reportFailure(failure: Throwable) {
            this.failure = failure
            invalidate()
        }

        override fun trackCleanup(cleanup: CompletionStage<Void>) {
            this.cleanup = cleanup
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
