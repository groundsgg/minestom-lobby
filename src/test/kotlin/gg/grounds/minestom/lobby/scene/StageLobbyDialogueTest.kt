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
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.instance.AddEntityToInstanceEvent
import net.minestom.server.event.player.PlayerTickEvent
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.common.PostEffectsPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SystemChatPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class StageLobbyDialogueTest {
    @Test
    fun `NPC speech choices and hint follow saved and live language on the same ticket`() {
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val bystander = fixture.player(Pos(1.0, 0.0, 2.0))
            player.locale = java.util.Locale.ENGLISH
            gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
            assertEquals("en", player.locale!!.language)
            val identity = SceneRuntimeIdentity(SceneId("test:npc-language"), "lobby/mainlobby", 19)
            val assets = StageDialogueAssets.load()
            val starter =
                StageLobbyDialogue(
                    fixture.instance,
                    identity,
                    LocalId("guide"),
                    Pos.ZERO,
                    assets,
                    true,
                ) {
                    gg.grounds.minigame.minestom.SidebarLocale.localeOf(it).toLanguageTag()
                }
            val context =
                SceneActionContext(
                    identity,
                    player,
                    LocalId("guide"),
                    SceneTrigger.RIGHT_CLICK,
                    SceneHand.MAIN,
                    System.nanoTime(),
                    emptyMap(),
                    SceneViewerVisualState(),
                )
            val serializer = PlainTextComponentSerializer.plainText()
            fun visibleTexts() =
                npcTextDisplays(player, fixture.instance).map {
                    serializer.serialize((it.entityMeta as TextDisplayMeta).text)
                }
            fun assertLanguage(speech: String, answer: String, hint: String, staleAnswer: String) {
                tickUntil(fixture.instance) {
                    val texts = visibleTexts()
                    starter.presentation(player)?.effectiveSurface == "npc" &&
                        texts.any { it.contains(speech) } &&
                        texts.any { it.contains(answer) } &&
                        texts.any { it.contains(hint) } &&
                        texts.none { it.contains(staleAnswer) }
                }
                val texts = visibleTexts()
                assertEquals("npc", starter.presentation(player)?.effectiveSurface)
                assertTrue(texts.any { it.contains(speech) }, texts.toString())
                assertTrue(texts.any { it.contains(answer) }, texts.toString())
                assertTrue(texts.any { it.contains(hint) }, texts.toString())
                assertFalse(texts.any { it.contains(staleAnswer) }, texts.toString())
                assertTrue(
                    npcTextDisplays(player, fixture.instance).none { bystander in it.viewers }
                )
                assertInstanceOf(
                    SceneActionResult.Rejected::class.java,
                    await(
                        starter.start(
                            context,
                            StageLobbyDialogue.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                        fixture.instance,
                    ),
                )
            }
            try {
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
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
                assertLanguage(
                    "Hallo! Das ist der Stage-Dialogtest.",
                    "Was kann ich hier testen?",
                    "Auswählen",
                    "What can I test here?",
                )
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "en")
                assertLanguage(
                    "Hello! This is the Stage dialogue test.",
                    "What can I test here?",
                    "Select",
                    "Was kann ich hier testen?",
                )
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "en")
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
                assertLanguage(
                    "Hallo! Das ist der Stage-Dialogtest.",
                    "Was kann ich hier testen?",
                    "Auswählen",
                    "What can I test here?",
                )
                assertEquals("en", player.locale!!.language)
            } finally {
                await(starter.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `unavailable duel menu stays localized and never joins a queue`() {
        RendererFixture().use { fixture ->
            val identity = SceneRuntimeIdentity(SceneId("test:duel-failure"), "lobby/mainlobby", 19)
            var opened = 0
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    identity,
                    listOf(
                        LobbyDialogueBinding(
                            LocalId("spawn-guide"),
                            Pos.ZERO,
                            LobbyGuideContent.DIALOGUE_ID,
                            "guide",
                        )
                    ),
                    queues =
                        object : gg.grounds.lobby.LobbyQueueService {
                            override fun toggleQueue(
                                player: net.minestom.server.entity.Player,
                                modeId: String,
                            ): CompletionStage<Boolean> = fail("Dialogue must not join queues")

                            override fun openDuels(
                                player: net.minestom.server.entity.Player
                            ): CompletionStage<Boolean> {
                                opened++
                                return CompletableFuture.failedFuture(
                                    IllegalStateException("Unavailable")
                                )
                            }
                        },
                )
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
            fun choose(label: String) {
                repeat(2) {
                    MinecraftServer.getCommandManager()
                        .execute(player, commandFor(player, label).removePrefix("/"))
                    fixture.instance.tick(0)
                    MinecraftServer.getSchedulerManager().processTick()
                }
            }
            try {
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        owner.start(
                            SceneActionContext(
                                identity,
                                player,
                                LocalId("spawn-guide"),
                                SceneTrigger.RIGHT_CLICK,
                                SceneHand.MAIN,
                                System.nanoTime(),
                                emptyMap(),
                                SceneViewerVisualState(),
                            ),
                            LobbyGuideContent.DIALOGUE_ID,
                            "guide",
                        ),
                        fixture.instance,
                    ),
                )
                choose("Ein Spiel finden")
                tickUntil(fixture.instance) { received(player, "Mit dem Kompass") }
                choose("Duellmenü öffnen")
                tickUntil(fixture.instance) {
                    received(player, "Dieses Menü ist gerade nicht verfügbar")
                }
                assertTrue(received(player, "Dieses Menü ist gerade nicht verfügbar"))
                assertEquals(1, opened)
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "en")
                tickUntil(fixture.instance) { received(player, "That menu isn’t available") }
                assertTrue(received(player, "That menu isn’t available"))
                assertEquals(1, opened)
                assertNotNull(commandFor(player, "Back"))
            } finally {
                await(owner.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `persisted guide discovery rejects unsupported identity hand and trigger`() {
        val npc =
            gg.grounds.scene.format.Npc(
                id = LocalId("spawn-guide"),
                group = null,
                transform =
                    gg.grounds.scene.format.Transform(
                        gg.grounds.scene.format.Vec3(0.0, 0.0, 0.0),
                        gg.grounds.scene.format.ZERO_ROTATION,
                        gg.grounds.scene.format.Vec3(1.0, 1.0, 1.0),
                    ),
                body = guideKey,
                label = Component.text("Guide"),
                labelOffset = gg.grounds.scene.format.Vec3(0.0, 2.1, 0.0),
                look = gg.grounds.scene.format.LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds =
                    gg.grounds.scene.format.LocalBounds(
                        gg.grounds.scene.format.Vec3(0.0, 0.9, 0.0),
                        gg.grounds.scene.format.Vec3(0.6, 1.8, 0.6),
                    ),
                proximity = null,
                bindings = emptyList(),
            )
        fun prepared(
            dialogue: String,
            trigger: SceneTrigger = SceneTrigger.RIGHT_CLICK,
            hand: SceneHand = SceneHand.MAIN,
        ): PreparedLobbyScene {
            val binding =
                gg.grounds.scene.format.TriggerBinding(
                    trigger,
                    listOf(gg.grounds.scene.format.HandCondition(hand)),
                    0,
                    0,
                    listOf(
                        gg.grounds.scene.format.ApplicationAction(
                            LobbySceneCatalogs.START_DIALOGUE,
                            mapOf(
                                LobbySceneCatalogs.DIALOGUE_ID to StringArgument(dialogue),
                                LobbySceneCatalogs.SPEAKER to StringArgument("guide"),
                            ),
                        )
                    ),
                )
            val configured =
                gg.grounds.scene.format.Npc(
                    npc.id,
                    npc.group,
                    npc.transform,
                    npc.visible,
                    npc.activation,
                    npc.body,
                    npc.label,
                    npc.labelOffset,
                    npc.look,
                    npc.initialAnimation,
                    npc.interactionBounds,
                    npc.proximity,
                    listOf(binding),
                    npc.collision,
                )
            val assets = gg.grounds.resourcepacks.catalog.GroundsAssetCatalog.catalog
            val scene =
                gg.grounds.scene.format.SceneDocument(
                    1,
                    gg.grounds.scene.format.SceneId("test:bindings"),
                    gg.grounds.scene.format.SceneMetadata("Guide", null, emptySet()),
                    gg.grounds.scene.format.SceneCatalogReferences(
                        gg.grounds.scene.format.CatalogReference(assets.id, assets.version),
                        gg.grounds.scene.format.CatalogReference(
                            LobbySceneCatalogs.DIALOGUE.id,
                            LobbySceneCatalogs.DIALOGUE.version,
                        ),
                    ),
                    emptyList(),
                    listOf(configured),
                )
            return PreparedLobbyScene(
                scene,
                assets,
                LobbySceneCatalogs.DIALOGUE,
                SceneRuntimeIdentity(scene.id, "lobby/mainlobby", 19),
                SceneAssetRendererRegistry { _, _ -> null },
            )
        }
        assertEquals(
            listOf(LocalId("spawn-guide")),
            prepared(LobbyGuideContent.DIALOGUE_ID).dialogueBindings().map { it.elementId },
        )
        val guide = prepared(LobbyGuideContent.DIALOGUE_ID)
        assertEquals(2.1, guide.dialogueBindings().single().replacementLabel?.position?.y())
        assertNull(guide.dialogueBindings(replaceNpcLabels = false).single().replacementLabel)
        assertThrows(IllegalArgumentException::class.java) {
            prepared("unknown:dialogue").dialogueBindings()
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepared(LobbyGuideContent.DIALOGUE_ID, SceneTrigger.LEFT_CLICK).dialogueBindings()
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepared(LobbyGuideContent.DIALOGUE_ID, hand = SceneHand.OFF).dialogueBindings()
        }
    }

    @Test
    fun `guide opens only owned players menus and shares admission with preview`() {
        RendererFixture().use { fixture ->
            val identity = SceneRuntimeIdentity(SceneId("test:guide"), "lobby/mainlobby", 19)
            val guide = LocalId("spawn-guide")
            val preview = LocalId("demo-bounce")
            val opened = mutableListOf<net.minestom.server.entity.Player>()
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    identity,
                    listOf(
                        LobbyDialogueBinding(
                            guide,
                            Pos.ZERO,
                            LobbyGuideContent.DIALOGUE_ID,
                            LobbyGuideContent.SPEAKER_ROLE,
                        ),
                        LobbyDialogueBinding(
                            preview,
                            Pos.ZERO,
                            StageLobbyDialogue.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                    ),
                    navigator =
                        object : gg.grounds.lobby.LobbyNavigatorService {
                            override fun open(
                                player: net.minestom.server.entity.Player
                            ): CompletionStage<Boolean> {
                                opened += player
                                return CompletableFuture.completedFuture(true)
                            }
                        },
                )
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val other = fixture.player(Pos(0.0, 0.0, 2.0))
            gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
            fun context(who: net.minestom.server.entity.Player, element: LocalId = guide) =
                SceneActionContext(
                    identity,
                    who,
                    element,
                    SceneTrigger.RIGHT_CLICK,
                    SceneHand.MAIN,
                    System.nanoTime(),
                    emptyMap(),
                    SceneViewerVisualState(),
                )
            fun choose(label: String) {
                repeat(2) {
                    MinecraftServer.getCommandManager()
                        .execute(player, commandFor(player, label).removePrefix("/"))
                    fixture.instance.tick(0)
                    MinecraftServer.getSchedulerManager().processTick()
                }
            }
            try {
                assertInstanceOf(
                    SceneActionResult.Rejected::class.java,
                    await(
                        owner.start(
                            context(player).copy(hand = SceneHand.OFF),
                            LobbyGuideContent.DIALOGUE_ID,
                            "guide",
                        ),
                        fixture.instance,
                    ),
                )
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        owner.start(context(player), LobbyGuideContent.DIALOGUE_ID, "guide"),
                        fixture.instance,
                    ),
                )
                assertTrue(received(player, "Hallo! Ich bin dein Guide"))
                assertInstanceOf(
                    SceneActionResult.Rejected::class.java,
                    await(
                        owner.start(
                            context(player, preview),
                            StageLobbyDialogue.DIALOGUE_ID,
                            "guide",
                        ),
                        fixture.instance,
                    ),
                )
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        owner.start(
                            context(other, preview),
                            StageLobbyDialogue.DIALOGUE_ID,
                            "guide",
                        ),
                        fixture.instance,
                    ),
                )
                choose("Lobby oder Region wählen")
                tickUntil(fixture.instance) { received(player, "Im Navigator") }
                assertTrue(received(player, "Im Navigator"))
                choose("Navigator öffnen")
                tickUntil(fixture.instance) { opened.isNotEmpty() }
                assertEquals(listOf(player), opened)
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "en")
                fixture.instance.tick(0)
                assertEquals(listOf(player), opened)
                assertFalse(received(other, "Im Navigator"))
            } finally {
                await(owner.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `ticket locale observers close after completion host close and rejected starts`() {
        RendererFixture().use { fixture ->
            val identity = SceneRuntimeIdentity(SceneId("test:observer"), "lobby/mainlobby", 19)
            var observers = 0
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    identity,
                    listOf(
                        LobbyDialogueBinding(
                            LocalId("guide"),
                            Pos.ZERO,
                            StageLobbyDialogue.DIALOGUE_ID,
                            "guide",
                        )
                    ),
                    observeLocale = { _, _ ->
                        observers++
                        AutoCloseable { observers-- }
                    },
                )
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val context =
                SceneActionContext(
                    identity,
                    player,
                    LocalId("guide"),
                    SceneTrigger.RIGHT_CLICK,
                    SceneHand.MAIN,
                    System.nanoTime(),
                    emptyMap(),
                    SceneViewerVisualState(),
                )
            assertEquals(
                SceneActionResult.Success,
                await(
                    owner.start(context, StageLobbyDialogue.DIALOGUE_ID, "guide"),
                    fixture.instance,
                ),
            )
            assertEquals(1, observers)
            await(owner.start(context, StageLobbyDialogue.DIALOGUE_ID, "guide"), fixture.instance)
            assertEquals(1, observers)
            await(player.teleport(Pos(0.0, 0.0, 8.0)), fixture.instance)
            tickUntil(fixture.instance) {
                EventDispatcher.call(PlayerTickEvent(player))
                observers == 0
            }
            assertEquals(0, observers)
            await(player.teleport(Pos(0.0, 0.0, 2.0)), fixture.instance)
            assertEquals(
                SceneActionResult.Success,
                await(
                    owner.start(context, StageLobbyDialogue.DIALOGUE_ID, "guide"),
                    fixture.instance,
                ),
            )
            assertEquals(1, observers)
            await(owner.closeAsync(), fixture.instance)
            assertEquals(0, observers)
        }
    }

    @Test
    fun `committed language wins and active dialogue follows latest language`() {
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
            val identity = SceneRuntimeIdentity(SceneId("test:language"), "lobby/mainlobby", 19)
            val starter =
                StageLobbyDialogue(fixture.instance, identity, LocalId("guide"), Pos.ZERO) {
                    gg.grounds.minigame.minestom.SidebarLocale.localeOf(it).toLanguageTag()
                }
            try {
                val context =
                    SceneActionContext(
                        identity,
                        player,
                        LocalId("guide"),
                        SceneTrigger.RIGHT_CLICK,
                        SceneHand.MAIN,
                        System.nanoTime(),
                        emptyMap(),
                        SceneViewerVisualState(),
                    )
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
                assertTrue(received(player, "Hallo!"))
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "en")
                tickUntil(fixture.instance) { received(player, "Hello!") }
                assertTrue(received(player, "Hello!"))
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "en")
                gg.grounds.minigame.minestom.SidebarLocale.set(player, "de")
                tickUntil(fixture.instance) {
                    player.messages().lastOrNull()?.let {
                        PlainTextComponentSerializer.plainText().serialize(it).contains("Hallo!")
                    } == true
                }
                assertTrue(
                    PlainTextComponentSerializer.plainText()
                        .serialize(player.messages().last())
                        .contains("Hallo!")
                )
            } finally {
                await(starter.closeAsync(), fixture.instance)
            }
        }
    }

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
    fun `Stage USE hint uses the private control glyph and keeps the translated action verb`() {
        RendererFixture().use { fixture ->
            val identity = SceneRuntimeIdentity(SceneId("test:input-icon"), "lobby/mainlobby", 19)
            val assets = StageDialogueAssets.load()
            val starter =
                StageLobbyDialogue(
                    fixture.instance,
                    identity,
                    LocalId("demo-bounce"),
                    Pos.ZERO,
                    assets,
                    true,
                ) {
                    "de-DE"
                }
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val bystander = fixture.player(Pos(1.0, 0.0, 2.0))
            try {
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        starter.start(
                            SceneActionContext(
                                identity,
                                player,
                                LocalId("demo-bounce"),
                                SceneTrigger.RIGHT_CLICK,
                                SceneHand.MAIN,
                                System.nanoTime(),
                                emptyMap(),
                                SceneViewerVisualState(),
                            ),
                            StageLobbyDialogue.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                        fixture.instance,
                    ),
                )
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                val serializer = PlainTextComponentSerializer.plainText()
                tickUntil(fixture.instance) {
                    val visibleTexts =
                        npcTextDisplays(player, fixture.instance).map {
                            serializer.serialize((it.entityMeta as TextDisplayMeta).text)
                        }
                    visibleTexts.any { it.contains("Hallo!") } &&
                        visibleTexts.any { it.contains("Text anzeigen") }
                }

                val hintDisplays =
                    fixture.instance.entities.filter {
                        it.entityType == EntityType.TEXT_DISPLAY &&
                            serializer
                                .serialize((it.entityMeta as TextDisplayMeta).text)
                                .contains("Text anzeigen")
                    }
                val hintTexts =
                    hintDisplays.map {
                        serializer.serialize((it.entityMeta as TextDisplayMeta).text)
                    }
                assertTrue(hintDisplays.isNotEmpty(), "Expected the translated reveal hint display")
                assertTrue(
                    hintDisplays.all { player in it.viewers },
                    "The USE hint should be visible to the dialogue player",
                )
                assertTrue(
                    hintDisplays.none { bystander in it.viewers },
                    "The USE hint should remain private to the dialogue player",
                )
                assertTrue(
                    hintTexts.any { it.contains("\uE791") },
                    "Expected the USE control glyph U+E791 in the hint, got $hintTexts",
                )
                assertTrue(hintTexts.any { it.contains("Text anzeigen") }, hintTexts.toString())
                assertFalse(
                    hintTexts.any { it.contains("Benutzen") },
                    "The input action label should be replaced by its control glyph: $hintTexts",
                )
                assertFalse(
                    hintTexts.any { Regex("\\bUse\\b").containsMatchIn(it) },
                    "The input action label should not retain a separate Use word: $hintTexts",
                )
            } finally {
                await(starter.closeAsync(), fixture.instance)
            }
        }
    }

    @Test
    fun `native Stage vignette stays steady while typing and uses a matching exit key`() {
        RendererFixture().use { fixture ->
            val identity =
                SceneRuntimeIdentity(SceneId("test:native-vignette"), "lobby/mainlobby", 19)
            val assets = StageDialogueAssets.load()
            val starter =
                StageLobbyDialogue(
                    fixture.instance,
                    identity,
                    LocalId("demo-bounce"),
                    Pos.ZERO,
                    assets,
                    true,
                ) {
                    "en"
                }
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val bystander = fixture.player(Pos(1.0, 0.0, 2.0))
            try {
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        starter.start(
                            SceneActionContext(
                                identity,
                                player,
                                LocalId("demo-bounce"),
                                SceneTrigger.RIGHT_CLICK,
                                SceneHand.MAIN,
                                System.nanoTime(),
                                emptyMap(),
                                SceneViewerVisualState(),
                            ),
                            StageLobbyDialogue.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                        fixture.instance,
                    ),
                )
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                var backdropOpacitiesAtFirstAppearance: List<Int>? = null
                tickUntil(fixture.instance) {
                    val backdrops = backdropDisplays(fixture.instance)
                    if (backdrops.size == 2 && backdropOpacitiesAtFirstAppearance == null) {
                        backdropOpacitiesAtFirstAppearance = backdrops.map(::displayOpacity)
                    }
                    backdrops.size == 2 && effects(player).isNotEmpty()
                }
                val backdrops = backdropDisplays(fixture.instance)
                assertEquals(2, backdrops.size)
                assertEquals(listOf(255, 255), backdropOpacitiesAtFirstAppearance)
                assertTrue(backdrops.all { player in it.viewers })
                assertTrue(backdrops.none { bystander in it.viewers })
                assertTrue(backdrops.all { displayOpacity(it) == 255 })

                val entry = effects(player).singleOrNull()
                assertNotNull(entry, "Stage should install one native vignette entry effect")
                val entryKey = checkNotNull(entry)
                assertTrue(isNativeEntry(entryKey), "Expected a native entry key, got $entryKey")
                val entrySnapshots = effectSnapshots(player)
                assertFalse(entrySnapshots.flatten().any(::isLegacyVignetteFade))
                assertFalse(
                    entrySnapshots.flatten().any {
                        it == net.kyori.adventure.key.Key.key("dialogue:vignette")
                    },
                    "The native entry key remains installed; the legacy full vignette is not a completion marker",
                )
                val entryPacketCount = entrySnapshots.count { it == listOf(entryKey) }
                assertEquals(
                    1,
                    entryPacketCount,
                    "One native entry write is expected per activation",
                )
                assertEquals(
                    listOf(listOf(entryKey)),
                    entrySnapshots.filter { it.isNotEmpty() },
                    "The entry transition should emit one native effect packet",
                )

                val serializer = PlainTextComponentSerializer.plainText()
                tickUntil(fixture.instance) {
                    npcTextDisplays(player, fixture.instance).any {
                        serializer
                            .serialize((it.entityMeta as TextDisplayMeta).text)
                            .contains("Hello!")
                    }
                }
                assertEquals(
                    listOf(entryKey),
                    effects(player),
                    "Typing must keep the entry key installed",
                )
                assertEquals(
                    entryPacketCount,
                    effectSnapshots(player).count { it == listOf(entryKey) },
                    "Typing must not emit additional vignette strength packets",
                )

                val snapshotCount = effectSnapshots(player).size
                await(player.teleport(Pos(0.0, 0.0, 8.0)), fixture.instance)
                tickUntil(fixture.instance) {
                    EventDispatcher.call(PlayerTickEvent(player))
                    starter.presentation(player)?.diagnostic == "CLOSED" &&
                        effects(player).isEmpty()
                }
                val exitKey =
                    net.kyori.adventure.key.Key.key(
                        "dialogue:vignette_250_exit_${entryKey.value().substringAfterLast('_')}"
                    )
                val exitPackets = effectSnapshots(player).drop(snapshotCount)
                assertEquals(
                    listOf(listOf(entryKey, exitKey), emptyList()),
                    exitPackets,
                    "Distance close should append the matching exit key, then clear effects",
                )
                assertEquals("CLOSED", starter.presentation(player)?.diagnostic)
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
                assertTrue(effects(player).isEmpty())
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                var firstVisibleBackdropOpacity: Int? = null
                var fullyOpaqueBackdropDuringVignetteFade = false
                tickUntil(fixture.instance) {
                    val backdrop = backdropDisplay(player, fixture.instance)
                    if (backdrop != null && firstVisibleBackdropOpacity == null) {
                        firstVisibleBackdropOpacity = displayOpacity(backdrop)
                    }
                    val vignetteFadeActive = effects(player).any(::isVignetteFade)
                    if (vignetteFadeActive && backdrop != null && displayOpacity(backdrop) == 255) {
                        fullyOpaqueBackdropDuringVignetteFade = true
                    }
                    starter.presentation(player)?.effectiveSurface == "npc" &&
                        vignetteFadeActive &&
                        backdrop != null &&
                        displayOpacity(backdrop) == 255
                }
                val visibleBackdrop = checkNotNull(backdropDisplay(player, fixture.instance))
                assertTrue(
                    fullyOpaqueBackdropDuringVignetteFade,
                    "Backdrop should be fully opaque while the vignette fade is active",
                )
                assertEquals(255, firstVisibleBackdropOpacity)
                assertEquals(255, displayOpacity(visibleBackdrop))
                assertFalse(other in visibleBackdrop.viewers)
                val displaysDuringFade = npcTextDisplays(player, fixture.instance)
                assertTrue(displaysDuringFade.size > 1)
                assertTrue(
                    displaysDuringFade
                        .filter { it !== visibleBackdrop }
                        .all { displayOpacity(it) == 255 },
                    "Dialogue panels should remain fully opaque during the vignette fade",
                )
                tickUntil(fixture.instance) {
                    starter.presentation(player)!!.effectiveSurface == "npc" &&
                        isStableVignetteEntry(effects(player))
                }
                assertEquals(
                    "npc",
                    starter.presentation(player)!!.effectiveSurface,
                    starter.presentation(player).toString(),
                )
                assertTrue(isStableVignetteEntry(effects(player)), effects(player).toString())
                assertTrue(effects(other).isEmpty())
                label.update(0)
                assertFalse(player in label.viewers)
                assertTrue(other in label.viewers)
                starter.confirmPack(player, DialoguePackReadiness.State(2, false, assets.pin()))
                tickUntil(fixture.instance) {
                    starter.presentation(player)!!.effectiveSurface == "chat" &&
                        effects(player).isEmpty()
                }
                assertEquals("chat", starter.presentation(player)!!.effectiveSurface)
                assertTrue(effects(player).isEmpty())
                label.update(0)
                assertTrue(player in label.viewers)
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                assertEquals("chat", starter.presentation(player)!!.effectiveSurface)

                starter.confirmPack(player, DialoguePackReadiness.State(3, true, assets.pin()))
                tickUntil(fixture.instance) {
                    starter.presentation(player)!!.effectiveSurface == "npc" &&
                        isStableVignetteEntry(effects(player))
                }
                assertTrue(isStableVignetteEntry(effects(player)), effects(player).toString())
                label.update(0)
                assertFalse(player in label.viewers)
                await(player.teleport(Pos(0.0, 0.0, 8.0)), fixture.instance)
                tickUntil(fixture.instance) {
                    EventDispatcher.call(PlayerTickEvent(player))
                    starter.presentation(player)?.diagnostic == "CLOSED" &&
                        effects(player).isEmpty()
                }
                assertEquals("CLOSED", starter.presentation(player)?.diagnostic)
                assertTrue(effects(player).isEmpty())
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
    fun `closing the Stage dialogue host clears the active vignette effect`() {
        RendererFixture().use { fixture ->
            val identity =
                SceneRuntimeIdentity(SceneId("test:close-effects"), "lobby/mainlobby", 19)
            val assets = StageDialogueAssets.load()
            val starter =
                StageLobbyDialogue(
                    fixture.instance,
                    identity,
                    LocalId("demo-bounce"),
                    Pos.ZERO,
                    assets,
                    true,
                ) {
                    "en"
                }
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            try {
                assertEquals(
                    SceneActionResult.Success,
                    await(
                        starter.start(
                            SceneActionContext(
                                identity,
                                player,
                                LocalId("demo-bounce"),
                                SceneTrigger.RIGHT_CLICK,
                                SceneHand.MAIN,
                                System.nanoTime(),
                                emptyMap(),
                                SceneViewerVisualState(),
                            ),
                            StageLobbyDialogue.DIALOGUE_ID,
                            StageLobbyDialogue.SPEAKER_ROLE,
                        ),
                        fixture.instance,
                    ),
                )
                starter.confirmPack(player, DialoguePackReadiness.State(1, true, assets.pin()))
                tickUntil(fixture.instance) { isStableVignetteEntry(effects(player)) }
                assertTrue(isStableVignetteEntry(effects(player)), effects(player).toString())

                await(starter.closeAsync(), fixture.instance)

                assertTrue(effects(player).isEmpty())
                assertTrue(npcTextDisplays(player, fixture.instance).isEmpty())
            } finally {
                await(starter.closeAsync(), fixture.instance)
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
    fun `dialogue nameplate lease hides only the speaker audience immediately`() {
        RendererFixture().use { fixture ->
            val player = fixture.player(Pos(0.0, 0.0, 2.0))
            val other = fixture.player(Pos(1.0, 0.0, 2.0))
            val nameplate =
                StageDialogueLabel(LocalId("demo-bounce"), Component.text("Title"), Pos.ZERO)
            val lifetime = TestLabelLifetime()
            val label = nameplate.attach(fixture.instance, lifetime, ::emptySet)
            tickUntil(fixture.instance) { player in label.viewers && other in label.viewers }

            val lease = checkNotNull(nameplate.hideFor(player))
            assertFalse(player in label.viewers)
            assertTrue(other in label.viewers)
            fixture.instance.tick(0)
            assertFalse(player in label.viewers)
            lease.close()
            assertTrue(player in label.viewers)

            lifetime.invalidate()
            await(checkNotNull(lifetime.cleanup), fixture.instance)
            assertNull(nameplate.hideFor(player))
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

    private fun effects(player: TestPlayer): List<net.kyori.adventure.key.Key> =
        player.connection.packets.filterIsInstance<PostEffectsPacket>().lastOrNull()?.postEffects()
            ?: emptyList()

    private fun effectHistory(player: TestPlayer): List<net.kyori.adventure.key.Key> =
        player.connection.packets.filterIsInstance<PostEffectsPacket>().flatMap { it.postEffects() }

    private fun effectSnapshots(player: TestPlayer): List<List<net.kyori.adventure.key.Key>> =
        player.connection.packets.filterIsInstance<PostEffectsPacket>().map {
            it.postEffects().toList()
        }

    private fun isLegacyVignetteFade(key: net.kyori.adventure.key.Key): Boolean =
        key.namespace() == "dialogue" && key.value().startsWith("vignette_fade_")

    private fun isNativeEntry(key: net.kyori.adventure.key.Key): Boolean =
        key.namespace() == "dialogue" && key.value().startsWith("vignette_250_enter_")

    private fun isVignetteFade(key: net.kyori.adventure.key.Key): Boolean =
        isLegacyVignetteFade(key) || isNativeEntry(key)

    private fun isStableVignetteEntry(keys: List<net.kyori.adventure.key.Key>): Boolean =
        keys.size == 1 &&
            (keys.single() == net.kyori.adventure.key.Key.key("dialogue:vignette") ||
                isNativeEntry(keys.single()))

    private fun npcTextDisplays(player: TestPlayer, instance: Instance): List<Entity> =
        instance.entities.filter {
            it.entityType == EntityType.TEXT_DISPLAY && player in it.viewers
        }

    private fun backdropDisplays(instance: Instance): List<Entity> =
        instance.entities
            .filter {
                it.entityType == EntityType.TEXT_DISPLAY &&
                    PlainTextComponentSerializer.plainText()
                        .serialize((it.entityMeta as TextDisplayMeta).text) == "\uE300"
            }
            .sortedBy { it.position.z() }

    private fun backdropDisplay(player: TestPlayer, instance: Instance): Entity? =
        backdropDisplays(instance).firstOrNull { player in it.viewers }

    private fun displayOpacity(display: Entity): Int =
        (display.entityMeta as TextDisplayMeta).textOpacity.toInt() and 0xff

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
