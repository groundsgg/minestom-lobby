package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.GroundsPluginLobbyProvider
import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.core.DefaultServiceRegistry
import gg.grounds.runtime.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.SceneRuntimeIdentity
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbySceneCompositionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: java.nio.file.Path
    private val assets =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
            listOf(guideKey, AssetKey("test:other")).associateWith {
                AssetDefinition(it, AssetKind.NPC_BODY, emptySet(), unitBounds, emptyMap())
            },
        )

    private fun content(
        keys: List<AssetKey> = listOf(guideKey)
    ): GroundsNpcModelSource.LoadedContent {
        val root = java.nio.file.Files.createTempDirectory(directory, "content-")
        val entries =
            keys.mapIndexed { index, key ->
                val model =
                    """{"schemaVersion":1,"asset":"${key.value}","bounds":{"center":[0.5,0.5,0.5],"size":[1,1,1]},"bones":[],"parts":[{"bone":null,"model":"grounds:npc_parts/test/cube","center":[0.5,0.5,0.5],"pivot":[0,0,0],"rotation":[0,0,0]}],"animations":{},"sounds":{}}"""
                java.nio.file.Files.writeString(root.resolve("model$index.json"), model)
                """{"id":"${key.value}","resource":"model$index.json","sha256":"${LobbyDialogueDistributionBinding.hash(model.toByteArray())}"}"""
            }
        val payload =
            """{"bundleSchema":1,"clientFiles":{},"compilerVersion":"test","models":[${entries.joinToString(",")}],"soundEvents":{},"target":"26.3"}"""
        val contract = com.google.gson.JsonParser.parseString(payload).asJsonObject
        contract.addProperty(
            "contentId",
            LobbyDialogueDistributionBinding.hash(payload.toByteArray()),
        )
        java.nio.file.Files.writeString(root.resolve("bundle.json"), contract.toString())
        return GroundsNpcModelSource.LoadedContent(
            gg.grounds.npc.core.io.content.NpcContentLoader.load(root),
            GroundsNpcModelSource.load(assets, root),
        )
    }

    private fun pins() =
        listOf("executable", "serverContent", "addon", "catalog", "scene").associateWith {
            mapOf<String, Any>("sha256" to "a".repeat(64), "size" to 42L) +
                if (it == "addon") mapOf("sha1" to "b".repeat(40)) else emptyMap()
        }

    private fun prepared(
        fixture: RendererFixture,
        dialogue: String = LobbyGuideContent.DIALOGUE_ID,
        speaker: String = "guide",
    ): PreparedLobbyScene {
        val authored = lobbySceneFixture(assets, Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        val original = authored.elements.filterIsInstance<Npc>().single()
        val npc =
            Npc(
                LocalId("spawn-guide"),
                original.group,
                original.transform,
                original.visible,
                original.activation,
                original.body,
                original.label,
                original.labelOffset,
                original.look,
                original.initialAnimation,
                original.interactionBounds,
                original.proximity,
                original.bindings,
            )
        val scene =
            authored.with(
                elements =
                    listOf(
                        npc.with(
                            bindings =
                                listOf(
                                    TriggerBinding(
                                        SceneTrigger.RIGHT_CLICK,
                                        listOf(HandCondition(SceneHand.MAIN)),
                                        0,
                                        0,
                                        listOf(
                                            ApplicationAction(
                                                gg.grounds.lobby.scene.LobbySceneCatalogs
                                                    .START_DIALOGUE,
                                                mapOf(
                                                    gg.grounds.lobby.scene.LobbySceneCatalogs
                                                        .DIALOGUE_ID to StringArgument(dialogue),
                                                    gg.grounds.lobby.scene.LobbySceneCatalogs
                                                        .SPEAKER to StringArgument(speaker),
                                                ),
                                            )
                                        ),
                                    )
                                )
                        )
                    )
            )
        return PreparedLobbyScene(
            scene,
            assets,
            gg.grounds.lobby.scene.LobbySceneCatalogs.DIALOGUE,
            SceneRuntimeIdentity(scene.id, "test:" + UUID.randomUUID(), 1),
            fixture.registry,
        )
    }

    @Test
    fun `composition exposes actual owner registrations and exact request registry`() {
        RendererFixture().use { fixture ->
            val services = DefaultServiceRegistry()
            val hooks = mutableListOf<() -> Unit>()
            val module = GroundsPluginLobbyProvider().create()
            try {
                module.install(
                    object : GroundsServerContext {
                        override val serverType = ServerType.LOBBY
                        override val environment = RuntimeEnvironment.TEST
                        override val services: ServiceRegistry = services

                        override fun eventNode(name: String): EventNode<Event> = EventNode.all(name)

                        override fun onShutdown(action: () -> Unit) {
                            hooks += action
                        }
                    }
                )
                val selected = prepared(fixture)
                val composition = LobbySceneComposition.create(selected, fixture.instance, services)
                try {
                    assertNotNull(composition.dialogues)
                    assertSame(
                        composition.dialogues!!
                            .registrations
                            .getValue(LobbyGuideContent.DIALOGUE_ID),
                        composition.registrations.getValue(LobbyGuideContent.DIALOGUE_ID),
                    )
                    assertSame(composition.request.actionRegistry, composition.actionRegistry)
                    composition.registrations
                        .getValue(LobbyGuideContent.DIALOGUE_ID)
                        .requireExportReady()
                    val content = content()
                    val bound =
                        LobbyDialogueDistributionBinding.manifest(
                            composition,
                            GroundsPluginLobbyProvider(),
                            content,
                            pins(),
                        )
                    assertEquals("grounds:lobby-guide", bound.id)
                    assertEquals(
                        bound.generation,
                        com.google.gson.JsonParser.parseString(String(bound.profile.profileBytes))
                            .asJsonObject["version"]
                            .asString,
                    )
                    assertEquals(
                        "grounds:editor/guide",
                        bound.manifest.asJsonObject["roles"].asJsonObject["guide"].asString,
                    )
                    assertEquals(
                        GroundsPluginLobbyProvider().version,
                        bound.manifest.asJsonObject["provider"].asJsonObject["version"].asString,
                    )
                    val other =
                        LobbySceneComposition.create(
                            selected.copy(
                                identity =
                                    selected.identity.copy(mapId = "different:" + UUID.randomUUID())
                            ),
                            fixture.instance,
                            services,
                        )
                    try {
                        assertEquals(
                            bound.generation,
                            LobbyDialogueDistributionBinding.manifest(
                                    other,
                                    GroundsPluginLobbyProvider(),
                                    content,
                                    pins(),
                                )
                                .generation,
                        )
                    } finally {
                        other.dialogues!!.closeAsync().toCompletableFuture().join()
                    }
                    val receipt =
                        com.google.gson.JsonParser.parseString(String(bound.receipt(pins())))
                            .asJsonObject
                    assertEquals(
                        setOf("schemaVersion", "id", "version", "generation", "manifest", "files"),
                        receipt.keySet(),
                    )
                    assertEquals(7, receipt["files"].asJsonObject.size())
                    assertNotNull(
                        composition.actionRegistry.handlerFor(
                            gg.grounds.lobby.scene.LobbySceneCatalogs.START_DIALOGUE
                        )
                    )
                    assertThrows(UnsupportedOperationException::class.java) {
                        (composition.registrations as MutableMap).clear()
                    }
                } finally {
                    composition.dialogues!!.closeAsync().toCompletableFuture().join()
                }
            } finally {
                module.stop()
                hooks.asReversed().forEach { it() }
            }
        }
    }

    @Test
    fun `guide without backend cannot produce export-ready registration`() {
        RendererFixture().use { fixture ->
            val composition =
                LobbySceneComposition.create(
                    prepared(fixture),
                    fixture.instance,
                    DefaultServiceRegistry(),
                )
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    composition.registrations.values.single().requireExportReady()
                }
            } finally {
                composition.dialogues!!.closeAsync().toCompletableFuture().join()
            }
        }
    }

    @Test
    fun `no dialogue and mixed builtins cannot claim a profile`() {
        RendererFixture().use { fixture ->
            val content = content()
            val base = prepared(fixture, StageDialogueContent.DIALOGUE_ID)
            val noDialogue = base.copy(scene = base.scene.with(elements = emptyList()))
            val empty =
                LobbySceneComposition.create(noDialogue, fixture.instance, DefaultServiceRegistry())
            assertThrows(IllegalArgumentException::class.java) {
                LobbyDialogueDistributionBinding.manifest(
                    empty,
                    GroundsPluginLobbyProvider(),
                    content,
                    pins(),
                )
            }
            val stageNpc = base.scene.elements.filterIsInstance<Npc>().single()
            val stage =
                Npc(
                    LocalId("stage"),
                    stageNpc.group,
                    stageNpc.transform,
                    stageNpc.visible,
                    stageNpc.activation,
                    stageNpc.body,
                    stageNpc.label,
                    stageNpc.labelOffset,
                    stageNpc.look,
                    stageNpc.initialAnimation,
                    stageNpc.interactionBounds,
                    stageNpc.proximity,
                    stageNpc.bindings,
                )
            val guide = prepared(fixture).scene.elements.single()
            val mixed =
                LobbySceneComposition.create(
                    base.copy(scene = base.scene.with(elements = listOf(stage, guide))),
                    fixture.instance,
                    DefaultServiceRegistry(),
                )
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    LobbyDialogueDistributionBinding.manifest(
                        mixed,
                        GroundsPluginLobbyProvider(),
                        content,
                        pins(),
                    )
                }
            } finally {
                mixed.dialogues!!.closeAsync().toCompletableFuture().join()
            }
            assertThrows(IllegalArgumentException::class.java) {
                LobbySceneComposition.create(
                    prepared(fixture, "unsupported:story"),
                    fixture.instance,
                    DefaultServiceRegistry(),
                )
            }
        }
    }

    @Test
    fun `placeholder body cannot qualify even when Stage has no menu providers`() {
        RendererFixture().use { fixture ->
            val base = prepared(fixture, StageDialogueContent.DIALOGUE_ID)
            val owner =
                LobbySceneComposition.create(base, fixture.instance, DefaultServiceRegistry())
            val snapshot = content()
            val absent = content(listOf(AssetKey("test:other")))
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    LobbyDialogueDistributionBinding.manifest(
                        owner,
                        GroundsPluginLobbyProvider(),
                        absent,
                        pins(),
                    )
                }
                assertEquals(
                    "grounds:stage-dialogue",
                    LobbyDialogueDistributionBinding.manifest(
                            owner,
                            GroundsPluginLobbyProvider(),
                            snapshot,
                            pins(),
                        )
                        .id,
                )
            } finally {
                owner.dialogues!!.closeAsync().toCompletableFuture().join()
            }
        }
    }

    @Test
    fun `one role cannot claim two distinct loaded model bodies`() {
        RendererFixture().use { fixture ->
            val base = prepared(fixture, StageDialogueContent.DIALOGUE_ID)
            val first = base.scene.elements.filterIsInstance<Npc>().single()
            val second =
                Npc(
                    LocalId("second"),
                    first.group,
                    first.transform,
                    first.visible,
                    first.activation,
                    AssetKey("test:other"),
                    first.label,
                    first.labelOffset,
                    first.look,
                    first.initialAnimation,
                    first.interactionBounds,
                    first.proximity,
                    first.bindings,
                )
            val owner =
                LobbySceneComposition.create(
                    base.copy(scene = base.scene.with(elements = listOf(first, second))),
                    fixture.instance,
                    DefaultServiceRegistry(),
                )
            try {
                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        LobbyDialogueDistributionBinding.manifest(
                            owner,
                            GroundsPluginLobbyProvider(),
                            content(listOf(guideKey, AssetKey("test:other"))),
                            pins(),
                        )
                    }
                assertEquals("Conflicting dialogue role bodies", failure.message)
            } finally {
                owner.dialogues!!.closeAsync().toCompletableFuture().join()
            }
        }
    }

    @Test
    fun `authored metadata retains finite envelope and rejects runtime without catalogue`() {
        RendererFixture().use { fixture ->
            val services = DefaultServiceRegistry()
            val hooks = mutableListOf<() -> Unit>()
            val module = GroundsPluginLobbyProvider().create()
            module.install(
                object : GroundsServerContext {
                    override val serverType = ServerType.LOBBY
                    override val environment = RuntimeEnvironment.TEST
                    override val services: ServiceRegistry = services

                    override fun eventNode(name: String): EventNode<Event> = EventNode.all(name)

                    override fun onShutdown(action: () -> Unit) {
                        hooks += action
                    }
                }
            )
            try {
                val selected = prepared(fixture, "author:arrival")
                assertThrows(IllegalArgumentException::class.java) {
                    LobbySceneComposition.create(selected, fixture.instance, services)
                }
                val composition =
                    LobbySceneComposition.prepareMetadata(
                        selected,
                        fixture.instance,
                        services,
                        LobbyDialogueHostContract.GUIDE,
                    )
                try {
                    val bound =
                        LobbyDialogueDistributionBinding.manifest(
                            composition,
                            GroundsPluginLobbyProvider(),
                            content(),
                            pins(),
                        )
                    assertEquals("grounds:lobby-guide", bound.id)
                    assertEquals(
                        "author:arrival",
                        bound.manifest.asJsonObject["dialogueId"].asString,
                    )
                    assertEquals(
                        setOf(
                            "schemaVersion",
                            "id",
                            "version",
                            "hostCatalogJson",
                            "assetCatalog",
                            "actionCatalogs",
                            "startActions",
                            "npcAssets",
                        ),
                        com.google.gson.JsonParser.parseString(String(bound.profile.profileBytes))
                            .asJsonObject
                            .keySet(),
                    )
                    assertEquals(
                        setOf("executable", "serverContent", "addon", "catalog", "scene"),
                        bound.manifest.asJsonObject["files"].asJsonObject.keySet(),
                    )
                    assertEquals(
                        2,
                        bound.manifest.asJsonObject["actionProviders"].asJsonObject.size(),
                    )
                    assertEquals(
                        setOf(
                            "schemaVersion",
                            "serviceVariant",
                            "provider",
                            "dialogueId",
                            "roles",
                            "actionProviders",
                            "assetCatalog",
                            "actionCatalogs",
                            "contentId",
                            "files",
                        ),
                        bound.manifest.asJsonObject.keySet(),
                    )
                    assertEquals(setOf("author:arrival"), composition.registrations.keys)
                    val player = fixture.player()
                    val result =
                        composition.dialogues!!
                            .start(
                                gg.grounds.scene.minestom.SceneActionContext(
                                    selected.identity,
                                    player,
                                    LocalId("spawn-guide"),
                                    SceneTrigger.RIGHT_CLICK,
                                    SceneHand.MAIN,
                                    0,
                                    emptyMap(),
                                    gg.grounds.scene.minestom.SceneViewerVisualState(),
                                ),
                                "author:arrival",
                                "guide",
                            )
                            .toCompletableFuture()
                            .join()
                    assertInstanceOf(
                        gg.grounds.scene.minestom.SceneActionResult.Rejected::class.java,
                        result,
                    )
                    assertNull(composition.dialogues.presentation(player))
                    assertThrows(IllegalArgumentException::class.java) {
                        LobbyDialogueDistributionBinding.manifest(
                            composition,
                            GroundsPluginLobbyProvider(),
                            content(listOf(AssetKey("test:other"))),
                            pins(),
                        )
                    }
                } finally {
                    composition.dialogues!!.closeAsync().toCompletableFuture().join()
                }
                assertThrows(IllegalArgumentException::class.java) {
                    LobbySceneComposition.prepareMetadata(
                        selected,
                        fixture.instance,
                        services,
                        LobbyDialogueHostContract.STAGE,
                    )
                }
                assertThrows(IllegalArgumentException::class.java) {
                    LobbySceneComposition.prepareMetadata(
                        prepared(fixture, "author:arrival", "intruder"),
                        fixture.instance,
                        services,
                        LobbyDialogueHostContract.GUIDE,
                    )
                }
                val npc = selected.scene.elements.single()
                assertThrows(IllegalArgumentException::class.java) {
                    LobbySceneComposition.prepareMetadata(
                        selected.copy(scene = selected.scene.with(elements = listOf(npc, npc))),
                        fixture.instance,
                        services,
                        LobbyDialogueHostContract.GUIDE,
                    )
                }
            } finally {
                module.stop()
                hooks.asReversed().forEach { it() }
            }
        }
    }

    @Test
    fun `Stage metadata preserves multiple identical starts and rejects mixed duplicate and conflicting bindings`() {
        RendererFixture().use { fixture ->
            val base = prepared(fixture, StageDialogueContent.DIALOGUE_ID)
            val original = base.scene.elements.filterIsInstance<Npc>().single()
            fun npc(
                id: String,
                body: AssetKey = original.body,
                bindings: List<TriggerBinding> = original.bindings,
            ) =
                Npc(
                    LocalId(id),
                    original.group,
                    original.transform,
                    original.visible,
                    original.activation,
                    body,
                    original.label,
                    original.labelOffset,
                    original.look,
                    original.initialAnimation,
                    original.interactionBounds,
                    original.proximity,
                    bindings,
                )
            fun metadata(elements: List<Npc>) =
                LobbySceneComposition.prepareMetadata(
                    base.copy(scene = base.scene.with(elements = elements)),
                    fixture.instance,
                    DefaultServiceRegistry(),
                    LobbyDialogueHostContract.STAGE,
                )
            val first = npc("stage-one")
            val second = npc("stage-two")
            val composition = metadata(listOf(first, second))
            try {
                val bound =
                    LobbyDialogueDistributionBinding.manifest(
                        composition,
                        GroundsPluginLobbyProvider(),
                        content(),
                        pins(),
                    )
                assertEquals("grounds:stage-dialogue", bound.id)
                assertEquals(
                    StageDialogueContent.DIALOGUE_ID,
                    bound.manifest.asJsonObject["dialogueId"].asString,
                )
                assertEquals(
                    mapOf("guide" to guideKey.value),
                    bound.manifest.asJsonObject["roles"].asJsonObject.entrySet().associate {
                        it.key to it.value.asString
                    },
                )
                assertEquals(
                    setOf(StageDialogueContent.DIALOGUE_ID),
                    composition.registrations.keys,
                )
                assertEquals(
                    2,
                    base
                        .copy(scene = base.scene.with(elements = listOf(first, second)))
                        .dialogueBindings(LobbyDialogueHostContract.STAGE)
                        .size,
                )
            } finally {
                composition.dialogues!!.closeAsync().toCompletableFuture().join()
            }
            for (invalid in
                listOf(
                    listOf(first, first),
                    listOf(
                        first,
                        npc(
                            "spawn-guide",
                            bindings =
                                prepared(fixture, LobbyGuideContent.DIALOGUE_ID)
                                    .scene
                                    .elements
                                    .filterIsInstance<Npc>()
                                    .single()
                                    .bindings,
                        ),
                    ),
                    listOf(
                        first,
                        npc(
                            "stage-two",
                            bindings =
                                prepared(fixture, StageDialogueContent.DIALOGUE_ID, "intruder")
                                    .scene
                                    .elements
                                    .filterIsInstance<Npc>()
                                    .single()
                                    .bindings,
                        ),
                    ),
                )) {
                assertThrows(IllegalArgumentException::class.java) { metadata(invalid) }
            }
            val conflict = metadata(listOf(first, npc("stage-two", AssetKey("test:other"))))
            try {
                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        LobbyDialogueDistributionBinding.manifest(
                            conflict,
                            GroundsPluginLobbyProvider(),
                            content(listOf(guideKey, AssetKey("test:other"))),
                            pins(),
                        )
                    }
                assertEquals("Conflicting dialogue role bodies", failure.message)
            } finally {
                conflict.dialogues!!.closeAsync().toCompletableFuture().join()
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
