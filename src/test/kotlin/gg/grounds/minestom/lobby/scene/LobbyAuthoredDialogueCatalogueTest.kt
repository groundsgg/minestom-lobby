package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.json.DialogueJsonCodec
import gg.grounds.modules.register
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyAuthoredDialogueCatalogueTest {
    @TempDir lateinit var root: Path
    private val id = "story:welcome"

    @Test
    fun `operator anchored profile captures exact identity host and defensive bytes without admission`() {
        val prepared = prepare()
        assertEquals(LobbyDialogueHostContract.GUIDE, prepared.profile.contract)
        assertEquals("grounds:lobby-guide", prepared.profile.id)
        val bytes = prepared.profile.hostCatalogBytes
        bytes[0] = 0
        assertEquals('{', prepared.profile.hostCatalogBytes[0].toInt().toChar())
        assertEquals(
            LobbyDialogueRuntimeVerification.Denied("runtime_provenance_invalid"),
            LobbyDialogueRuntimeVerification.verify(prepared, null, null, false),
        )
    }

    @Test
    fun `bindings and host must match exact operator snapshot`() {
        val prepared = prepare()
        story(prepared)
        LobbyAuthoredDialogueCatalogue.load(
            LobbyAuthoredDialogueInputs.read(root) as LobbyAuthoredDialogueInputs.Present,
            prepared,
        )
        for (field in listOf("id", "version", "sha256")) {
            story(prepared)
            val path = root.resolve("grounds/dialogue.json")
            val json =
                LobbyDialogueRuntimeVerification.strictJson(Files.readAllBytes(path)).asJsonObject
            json.getAsJsonObject("profile").addProperty(field, "wrong")
            Files.write(path, LobbyDialogueDistributionBinding.json(json))
            assertThrows(IllegalArgumentException::class.java) { load(prepared) }
        }
        story(prepared)
        Files.writeString(root.resolve("context/host-catalog.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { load(prepared) }
    }

    @Test
    fun `catalogue uses runtime JSON semantics and reserves builtin ids`() {
        val prepared = prepare()
        for (definition in
            listOf(
                DialogueBuilder.dialogue(LobbyGuideContent.DIALOGUE_ID)
                    .node("end", End("completed"))
                    .build(),
                DialogueBuilder.dialogue(id)
                    .node("start", Message("other", "hello", null, emptyList(), "end"))
                    .node("end", End("completed"))
                    .build(),
                DialogueBuilder.dialogue(id)
                    .node(
                        "start",
                        Action("invented:provider", emptyMap(), "end", "end", "end", "end"),
                    )
                    .node("end", End("completed"))
                    .build(),
                DialogueBuilder.dialogue(id)
                    .locales("en", "en", "fr")
                    .node("start", Message("guide", "hello", null, emptyList(), "end"))
                    .node("end", End("completed"))
                    .build(),
            )) {
            story(prepared)
            Files.write(root.resolve("dialogues/a.json"), DialogueJsonCodec().encode(definition))
            assertThrows(IllegalArgumentException::class.java) { load(prepared) }
        }
    }

    @Test
    fun `actual primary NPC body catalogue and start id must match captured bindings`() {
        val prepared = prepare()
        story(prepared)
        val candidate = load(prepared)
        assertEquals(id, candidate.requireScene(scene()))
        for (wrong in
            listOf(
                scene(dialogue = "story:missing"),
                scene(npcId = "wrong-npc"),
                scene(body = "test:other"),
                scene(catalogVersion = "2"),
                scene(speaker = "other"),
            )) {
            assertThrows(IllegalArgumentException::class.java) { candidate.requireScene(wrong) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            candidate.revalidate(scene(), emptyMap(), "runtime-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            candidate.revalidate(
                scene(),
                mapOf(
                    id to LobbyDialogueHostRegistration.create(LobbyDialogueHostContract.GUIDE, id)
                ),
                "runtime-1",
            )
        }
    }

    @Test
    fun `runtime proofs use installed providers and graph scoped locales with canonical requested tag`() {
        val prepared = prepare()
        story(prepared)
        val definition =
            DialogueBuilder.dialogue(id)
                .locales("fr-CA", "fr-CA")
                .node("start", Message("guide", "hello", null, emptyList(), "end"))
                .node("end", End("completed"))
                .build()
        write("dialogues/a.json", DialogueJsonCodec().encode(definition))
        write("texts/fr-CA.json", """{"hello":"Bonjour"}""".toByteArray())
        write("texts/en.json", """{"hello":"Do not leak"}""".toByteArray())
        val candidate = load(prepared)
        val generation = "lobby-dialogue-" + java.util.UUID.randomUUID()
        val entry =
            candidate.revalidate(scene(), mapOf(id to registration()), generation).getValue(id)
        assertEquals(generation, entry.validated.catalog().generation())
        assertEquals(setOf("fr-CA"), entry.texts.locales().keys)
        val locale = entry.locale("en-AU", 7)
        assertEquals("en-AU", locale.tag())
        assertEquals("fr-CA", locale.fallbackTag())
        val resolved =
            entry.resolver
                .resolve(
                    gg.grounds.dialogue.core.text.TextResolver.TextRequest(
                        "hello",
                        emptyMap(),
                        locale,
                    )
                )
                .toCompletableFuture()
                .join()
        assertEquals("Bonjour", resolved.plain)
        assertEquals("fr-CA", resolved.localeTag)
        Files.writeString(root.resolve("texts/en.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { candidate.requireUnchanged() }
    }

    @Test
    fun `malformed profile envelope and finite host escalation never prepare`() {
        val prepared = prepare()
        val profile = prepared.profile.profileBytes
        val host = prepared.profile.hostCatalogBytes
        for (mutate in
            listOf<(com.google.gson.JsonObject) -> Unit>(
                { it.addProperty("unexpected", true) },
                { it.addProperty("schemaVersion", "1") },
                { it.addProperty("version", "wrong") },
                { it.addProperty("id", "author:chosen") },
                { it.addProperty("hostCatalogJson", "{}") },
                {
                    it.getAsJsonObject("npcAssets")
                        .getAsJsonObject("grounds:editor/guide")
                        .getAsJsonObject("gestures")
                        .addProperty("wave", "wave")
                },
            )) {
            val json = LobbyDialogueRuntimeVerification.strictJson(profile).asJsonObject
            mutate(json)
            assertThrows(IllegalArgumentException::class.java) {
                LobbyDialogueTrustedProfile.parse(
                    LobbyDialogueDistributionBinding.json(json),
                    host,
                    prepared.profile.id,
                    prepared.generation,
                )
            }
        }
        val changedHost = LobbyDialogueRuntimeVerification.strictJson(host).asJsonObject
        changedHost.getAsJsonArray("speakers").add("invented")
        val changedBytes = LobbyDialogueDistributionBinding.json(changedHost)
        val changedProfile = LobbyDialogueRuntimeVerification.strictJson(profile).asJsonObject
        changedProfile.addProperty("hostCatalogJson", changedBytes.toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            LobbyDialogueTrustedProfile.parse(
                LobbyDialogueDistributionBinding.json(changedProfile),
                changedBytes,
                prepared.profile.id,
                prepared.generation,
            )
        }
    }

    @Test
    fun `prepared snapshots repeat every artifact pin and binding anchor`() {
        for ((role, file) in LobbyDialogueDistributionBinding.files) {
            val prepared = prepare()
            prepared.requireUnchanged()
            Files.writeString(prepared.root.resolve(file), "changed-$role")
            assertThrows(IllegalArgumentException::class.java) { prepared.requireUnchanged() }
        }
        val prepared = prepare()
        Files.writeString(prepared.root.resolve("dialogue/binding.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { prepared.requireUnchanged() }
    }

    @Test
    fun `no sidecars produces absent catalogue without a profile`() {
        assertSame(
            LobbyAuthoredDialogueCatalogue.Absent,
            LobbyAuthoredDialogueCatalogue.load(LobbyAuthoredDialogueInputs.read(root), null),
        )
    }

    @Test
    fun `authored owner uses exact registration generation and captured text on mapped NPC`() {
        net.minestom.server.MinecraftServer.init()
        val prepared = prepare()
        story(prepared)
        val candidate = load(prepared)
        RendererFixture().use { fixture ->
            val selected = scene()
            val owner =
                LobbySceneComposition.createAuthored(
                        selected,
                        fixture.instance,
                        gg.grounds.modules.core.DefaultServiceRegistry().apply {
                            register<gg.grounds.lobby.LobbyNavigatorService>(
                                object : gg.grounds.lobby.LobbyNavigatorService {
                                    override fun open(player: net.minestom.server.entity.Player) =
                                        java.util.concurrent.CompletableFuture.completedFuture(true)
                                }
                            )
                            register<gg.grounds.lobby.LobbyQueueService>(
                                object : gg.grounds.lobby.LobbyQueueService {
                                    override fun toggleQueue(
                                        player: net.minestom.server.entity.Player,
                                        modeId: String,
                                    ) =
                                        java.util.concurrent.CompletableFuture.completedFuture(
                                            false
                                        )

                                    override fun openDuels(
                                        player: net.minestom.server.entity.Player
                                    ) = java.util.concurrent.CompletableFuture.completedFuture(true)
                                }
                            )
                        },
                        candidate,
                        LobbyDialogueAdmission(false),
                    )
                    .dialogues!!
            val player = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
            fun context(npc: String) =
                gg.grounds.scene.minestom.SceneActionContext(
                    selected.identity,
                    player,
                    gg.grounds.scene.format.LocalId(npc),
                    gg.grounds.scene.format.SceneTrigger.RIGHT_CLICK,
                    gg.grounds.scene.format.SceneHand.MAIN,
                    0,
                    emptyMap(),
                    gg.grounds.scene.minestom.SceneViewerVisualState(),
                )
            try {
                assertInstanceOf(
                    gg.grounds.scene.minestom.SceneActionResult.Rejected::class.java,
                    owner.start(context("foreign"), id, "guide").toCompletableFuture().join(),
                )
                val started = owner.start(context("navigator"), id, "guide").toCompletableFuture()
                repeat(100) {
                    fixture.instance.tick(0)
                    net.minestom.server.MinecraftServer.getSchedulerManager().processTick()
                }
                assertEquals(gg.grounds.scene.minestom.SceneActionResult.Success, started.join())
                val messages =
                    player.connection.packets
                        .filterIsInstance<
                            net.minestom.server.network.packet.server.play.SystemChatPacket
                        >()
                        .map {
                            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText()
                                .serialize(it.message())
                        }
                assertTrue(messages.any { it.contains("Hello") }, messages.toString())
                assertEquals(LobbyDialogueHostContract.GUIDE, owner.authoredContract)
                assertNull(owner.metadataContract)
            } finally {
                val closed = owner.closeAsync().toCompletableFuture()
                repeat(100) {
                    fixture.instance.tick(0)
                    net.minestom.server.MinecraftServer.getSchedulerManager().processTick()
                }
                assertTrue(closed.isDone)
                closed.join()
            }
        }
    }

    @Test
    fun `root action opens once and late reconnect completion cannot resume predecessor`() {
        net.minestom.server.MinecraftServer.init()
        val prepared = prepare()
        story(prepared)
        write(
            "dialogues/a.json",
            DialogueJsonCodec()
                .encode(
                    DialogueBuilder.dialogue(id)
                        .node(
                            "start",
                            Action(
                                LobbyGuideContent.NAVIGATOR_ACTION,
                                emptyMap(),
                                "ok",
                                "bad",
                                "bad",
                                "bad",
                            ),
                        )
                        .node("ok", Message("guide", "hello", null, emptyList(), "end"))
                        .node("bad", Message("guide", "failed", null, emptyList(), "end"))
                        .node("end", End("completed"))
                        .build()
                ),
        )
        write("texts/en.json", """{"hello":"ACCEPTED","failed":"UNAVAILABLE"}""".toByteArray())
        val candidate = load(prepared)
        RendererFixture().use { fixture ->
            val opened = mutableListOf<net.minestom.server.entity.Player>()
            val pending = mutableListOf<java.util.concurrent.CompletableFuture<Boolean>>()
            val selected = scene()
            val generation = "lobby-dialogue-" + java.util.UUID.randomUUID()
            val registration =
                LobbyDialogueHostRegistration.create(
                    LobbyDialogueHostContract.GUIDE,
                    id,
                    object : gg.grounds.lobby.LobbyNavigatorService {
                        override fun open(
                            player: net.minestom.server.entity.Player
                        ): java.util.concurrent.CompletionStage<Boolean> {
                            opened += player
                            return java.util.concurrent.CompletableFuture<Boolean>().also {
                                pending += it
                            }
                        }
                    },
                    object : gg.grounds.lobby.LobbyQueueService {
                        override fun toggleQueue(
                            player: net.minestom.server.entity.Player,
                            modeId: String,
                        ) = java.util.concurrent.CompletableFuture.completedFuture(false)

                        override fun openDuels(player: net.minestom.server.entity.Player) =
                            java.util.concurrent.CompletableFuture.completedFuture(true)
                    },
                )
            val registrations = mapOf(id to registration)
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    selected.identity,
                    selected.dialogueBindings(candidate.contract),
                    authoredContract = candidate.contract,
                    generation = generation,
                    actualRegistrations = registrations,
                    authoredEntries = candidate.revalidate(selected, registrations, generation),
                )
            fun context(player: net.minestom.server.entity.Player) =
                gg.grounds.scene.minestom.SceneActionContext(
                    selected.identity,
                    player,
                    gg.grounds.scene.format.LocalId("navigator"),
                    gg.grounds.scene.format.SceneTrigger.RIGHT_CLICK,
                    gg.grounds.scene.format.SceneHand.MAIN,
                    0,
                    emptyMap(),
                    gg.grounds.scene.minestom.SceneViewerVisualState(),
                )
            val first = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
            val other = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
            try {
                owner.start(context(first), id, "guide")
                ticks(fixture)
                assertEquals(listOf(first), opened)
                assertInstanceOf(
                    gg.grounds.scene.minestom.SceneActionResult.Rejected::class.java,
                    owner.start(context(first), id, "guide").toCompletableFuture().join(),
                )
                owner.start(context(other), id, "guide")
                ticks(fixture)
                assertEquals(listOf(first, other), opened)
                val successor =
                    fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0), first.uuid)
                owner.start(context(successor), id, "guide")
                ticks(fixture)
                assertEquals(listOf(first, other, successor), opened)
                first.connection.packets.clear()
                pending[0].complete(true)
                ticks(fixture)
                assertFalse(
                    messages(first).any { it.contains("ACCEPTED") || it.contains("UNAVAILABLE") }
                )
                pending[2].complete(true)
                pending[1].complete(true)
                ticks(fixture)
                assertTrue(messages(successor).any { it.contains("ACCEPTED") })
                assertTrue(messages(other).any { it.contains("ACCEPTED") })
                val disconnected = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
                owner.start(context(disconnected), id, "guide")
                ticks(fixture)
                net.minestom.server.event.EventDispatcher.call(
                    net.minestom.server.event.player.PlayerDisconnectEvent(disconnected)
                )
                disconnected.connection.packets.clear()
                pending[3].complete(true)
                ticks(fixture)
                assertFalse(
                    messages(disconnected).any {
                        it.contains("ACCEPTED") || it.contains("UNAVAILABLE")
                    }
                )
                RendererFixture().use { target ->
                    val moved = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
                    owner.start(context(moved), id, "guide")
                    ticks(fixture)
                    moved
                        .setInstance(target.instance, net.minestom.server.coordinate.Pos.ZERO)
                        .join()
                    net.minestom.server.event.EventDispatcher.call(
                        net.minestom.server.event.player.PlayerTickEvent(moved)
                    )
                    moved.connection.packets.clear()
                    pending[4].complete(true)
                    ticks(fixture)
                    ticks(target)
                    assertFalse(
                        messages(moved).any {
                            it.contains("ACCEPTED") || it.contains("UNAVAILABLE")
                        }
                    )
                }
            } finally {
                drain(owner, fixture)
            }
        }
    }

    @Test
    fun `immediate End root releases owner so a second click can start`() {
        net.minestom.server.MinecraftServer.init()
        val prepared = prepare()
        story(prepared)
        write(
            "dialogues/a.json",
            DialogueJsonCodec()
                .encode(DialogueBuilder.dialogue(id).node("start", End("completed")).build()),
        )
        val candidate = load(prepared)
        RendererFixture().use { fixture ->
            val selected = scene()
            val generation = "lobby-dialogue-" + java.util.UUID.randomUUID()
            val registrations = mapOf(id to registration())
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    selected.identity,
                    selected.dialogueBindings(candidate.contract),
                    authoredContract = candidate.contract,
                    generation = generation,
                    actualRegistrations = registrations,
                    authoredEntries = candidate.revalidate(selected, registrations, generation),
                )
            val player = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
            val context =
                gg.grounds.scene.minestom.SceneActionContext(
                    selected.identity,
                    player,
                    gg.grounds.scene.format.LocalId("navigator"),
                    gg.grounds.scene.format.SceneTrigger.RIGHT_CLICK,
                    gg.grounds.scene.format.SceneHand.MAIN,
                    0,
                    emptyMap(),
                    gg.grounds.scene.minestom.SceneViewerVisualState(),
                )
            try {
                repeat(2) {
                    val started = owner.start(context, id, "guide").toCompletableFuture()
                    ticks(fixture)
                    assertEquals(
                        gg.grounds.scene.minestom.SceneActionResult.Success,
                        started.join(),
                    )
                    assertEquals("CLOSED", owner.presentation(player)?.diagnostic)
                }
            } finally {
                drain(owner, fixture)
            }
        }
    }

    @Test
    fun `authored live locales preserve canonical tags scoped fallback and current node`() {
        net.minestom.server.MinecraftServer.init()
        val prepared = prepare()
        story(prepared)
        write(
            "dialogues/a.json",
            DialogueJsonCodec()
                .encode(
                    DialogueBuilder.dialogue(id)
                        .locales("fr-CA", "fr-CA", "es-MX")
                        .node(
                            "start",
                            Action(
                                LobbyGuideContent.NAVIGATOR_ACTION,
                                emptyMap(),
                                "shown",
                                "shown",
                                "shown",
                                "shown",
                            ),
                        )
                        .node(
                            "shown",
                            Message(
                                "guide",
                                "hello",
                                null,
                                listOf(Choice("stay", "choice", "end", false, null)),
                                null,
                            ),
                        )
                        .node("end", End("completed"))
                        .build()
                ),
        )
        write(
            "texts/fr-CA.json",
            """{"hello":"Bonjour Québec","choice":"Continuer"}""".toByteArray(),
        )
        write("texts/es-MX.json", """{"hello":"Hola México","choice":"Continuar"}""".toByteArray())
        write("texts/en.json", """{"hello":"LEAKED","choice":"LEAKED"}""".toByteArray())
        val candidate = load(prepared)
        RendererFixture().use { fixture ->
            val selected = scene()
            val generation = "lobby-dialogue-" + java.util.UUID.randomUUID()
            var opened = 0
            var refresh: Runnable? = null
            var language = "es-mx"
            val registrations =
                mapOf(
                    id to
                        LobbyDialogueHostRegistration.create(
                            candidate.contract,
                            id,
                            object : gg.grounds.lobby.LobbyNavigatorService {
                                override fun open(
                                    player: net.minestom.server.entity.Player
                                ): java.util.concurrent.CompletionStage<Boolean> {
                                    opened++
                                    return java.util.concurrent.CompletableFuture.completedFuture(
                                        true
                                    )
                                }
                            },
                            object : gg.grounds.lobby.LobbyQueueService {
                                override fun toggleQueue(
                                    player: net.minestom.server.entity.Player,
                                    modeId: String,
                                ) = java.util.concurrent.CompletableFuture.completedFuture(false)

                                override fun openDuels(player: net.minestom.server.entity.Player) =
                                    java.util.concurrent.CompletableFuture.completedFuture(true)
                            },
                        )
                )
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    selected.identity,
                    selected.dialogueBindings(candidate.contract),
                    locale = { language },
                    observeLocale = { _, callback ->
                        refresh = callback
                        AutoCloseable { refresh = null }
                    },
                    authoredContract = candidate.contract,
                    generation = generation,
                    actualRegistrations = registrations,
                    authoredEntries = candidate.revalidate(selected, registrations, generation),
                )
            val player = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
            val context =
                gg.grounds.scene.minestom.SceneActionContext(
                    selected.identity,
                    player,
                    gg.grounds.scene.format.LocalId("navigator"),
                    gg.grounds.scene.format.SceneTrigger.RIGHT_CLICK,
                    gg.grounds.scene.format.SceneHand.MAIN,
                    0,
                    emptyMap(),
                    gg.grounds.scene.minestom.SceneViewerVisualState(),
                )
            try {
                owner.start(context, id, "guide")
                ticks(fixture)
                assertTrue(messages(player).any { it.contains("Hola México") })
                assertTrue(messages(player).any { it.contains("Continuar") })
                assertEquals(1, opened)
                player.connection.packets.clear()
                language = "en-AU"
                refresh!!.run()
                ticks(fixture)
                assertTrue(messages(player).any { it.contains("Bonjour Québec") })
                assertTrue(messages(player).any { it.contains("Continuer") })
                assertFalse(messages(player).any { it.contains("LEAKED") })
                player.connection.packets.clear()
                language = "es-MX"
                refresh!!.run()
                ticks(fixture)
                assertTrue(messages(player).any { it.contains("Hola México") })
                assertEquals(1, opened, "Refreshing a later node must not replay the entry action")
            } finally {
                drain(owner, fixture)
            }
            assertNull(refresh)
        }
    }

    @Test
    fun `closing during preparation does not reinsert session or leak late locale subscription`() {
        net.minestom.server.MinecraftServer.init()
        val prepared = prepare()
        story(prepared)
        val candidate = load(prepared)
        RendererFixture().use { fixture ->
            val selected = scene()
            val generation = "lobby-dialogue-" + java.util.UUID.randomUUID()
            val registrations = mapOf(id to registration())
            var subscriptionsClosed = 0
            lateinit var owner: LobbyDialogues
            owner =
                LobbyDialogues(
                    fixture.instance,
                    selected.identity,
                    selected.dialogueBindings(candidate.contract),
                    observeLocale = { _, _ ->
                        owner.closeAsync()
                        AutoCloseable { subscriptionsClosed++ }
                    },
                    authoredContract = candidate.contract,
                    generation = generation,
                    actualRegistrations = registrations,
                    authoredEntries = candidate.revalidate(selected, registrations, generation),
                )
            val player = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
            val context =
                gg.grounds.scene.minestom.SceneActionContext(
                    selected.identity,
                    player,
                    gg.grounds.scene.format.LocalId("navigator"),
                    gg.grounds.scene.format.SceneTrigger.RIGHT_CLICK,
                    gg.grounds.scene.format.SceneHand.MAIN,
                    0,
                    emptyMap(),
                    gg.grounds.scene.minestom.SceneViewerVisualState(),
                )
            try {
                val started = owner.start(context, id, "guide").toCompletableFuture()
                ticks(fixture)
                assertTrue(started.isDone)
                assertEquals(1, subscriptionsClosed)
                assertNull(owner.presentation(player))
                assertInstanceOf(
                    gg.grounds.scene.minestom.SceneActionResult.Rejected::class.java,
                    owner.start(context, id, "guide").toCompletableFuture().join(),
                )
            } finally {
                drain(owner, fixture)
            }
        }
    }

    @Test
    fun `owner abort cancels actual navigator queued delivery before UI operation`() {
        net.minestom.server.MinecraftServer.init()
        val prepared = prepare()
        story(prepared)
        write(
            "dialogues/a.json",
            DialogueJsonCodec()
                .encode(
                    DialogueBuilder.dialogue(id)
                        .node(
                            "start",
                            Action(
                                LobbyGuideContent.NAVIGATOR_ACTION,
                                emptyMap(),
                                "end",
                                "end",
                                "end",
                                "end",
                            ),
                        )
                        .node("end", End("completed"))
                        .build()
                ),
        )
        val candidate = load(prepared)
        for (abort in listOf("owner", "disconnect", "instance")) RendererFixture().use { fixture ->
            val elsewhere = RendererFixture()
            var delivered = 0
            val queued = mutableListOf<Runnable>()
            val operation: (net.minestom.server.entity.Player) -> Unit = { delivered++ }
            val dispatch: (net.minestom.server.entity.Player, Runnable) -> Unit = { _, action ->
                queued += action
            }
            val constructor =
                Class.forName("gg.grounds.lobby.DefaultLobbyNavigatorService")
                    .getDeclaredConstructor(Function1::class.java, Function2::class.java)
            constructor.isAccessible = true
            val navigator =
                constructor.newInstance(operation, dispatch)
                    as gg.grounds.lobby.LobbyNavigatorService
            val selected = scene()
            val generation = "lobby-dialogue-" + java.util.UUID.randomUUID()
            val registrations =
                mapOf(
                    id to
                        LobbyDialogueHostRegistration.create(
                            candidate.contract,
                            id,
                            navigator,
                            object : gg.grounds.lobby.LobbyQueueService {
                                override fun toggleQueue(
                                    player: net.minestom.server.entity.Player,
                                    modeId: String,
                                ) = java.util.concurrent.CompletableFuture.completedFuture(false)

                                override fun openDuels(player: net.minestom.server.entity.Player) =
                                    java.util.concurrent.CompletableFuture.completedFuture(true)
                            },
                        )
                )
            val owner =
                LobbyDialogues(
                    fixture.instance,
                    selected.identity,
                    selected.dialogueBindings(candidate.contract),
                    authoredContract = candidate.contract,
                    generation = generation,
                    actualRegistrations = registrations,
                    authoredEntries = candidate.revalidate(selected, registrations, generation),
                )
            val player = fixture.player(net.minestom.server.coordinate.Pos(0.0, 0.0, 2.0))
            val context =
                gg.grounds.scene.minestom.SceneActionContext(
                    selected.identity,
                    player,
                    gg.grounds.scene.format.LocalId("navigator"),
                    gg.grounds.scene.format.SceneTrigger.RIGHT_CLICK,
                    gg.grounds.scene.format.SceneHand.MAIN,
                    0,
                    emptyMap(),
                    gg.grounds.scene.minestom.SceneViewerVisualState(),
                )
            try {
                owner.start(context, id, "guide")
                ticks(fixture)
                assertEquals(1, queued.size)
                val closed =
                    when (abort) {
                        "owner" -> owner.closeAsync().toCompletableFuture()
                        "disconnect" -> {
                            net.minestom.server.event.EventDispatcher.call(
                                net.minestom.server.event.player.PlayerDisconnectEvent(player)
                            )
                            java.util.concurrent.CompletableFuture.completedFuture(null)
                        }
                        else -> {
                            player
                                .setInstance(
                                    elsewhere.instance,
                                    net.minestom.server.coordinate.Pos.ZERO,
                                )
                                .join()
                            java.util.concurrent.CompletableFuture.completedFuture(null)
                        }
                    }
                queued.single().run()
                assertEquals(
                    0,
                    delivered,
                    "Aborted queued service work must never open the navigator ($abort)",
                )
                ticks(fixture)
                assertTrue(closed.isDone)
            } finally {
                drain(owner, fixture)
                (navigator as AutoCloseable).close()
                elsewhere.close()
            }
        }
    }

    private fun messages(player: TestPlayer) =
        player.connection.packets
            .filterIsInstance<net.minestom.server.network.packet.server.play.SystemChatPacket>()
            .map {
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(it.message())
            }

    private fun ticks(fixture: RendererFixture) {
        repeat(100) {
            fixture.instance.tick(0)
            net.minestom.server.MinecraftServer.getSchedulerManager().processTick()
        }
    }

    private fun drain(owner: LobbyDialogues, fixture: RendererFixture) {
        val closed = owner.closeAsync().toCompletableFuture()
        ticks(fixture)
        assertTrue(closed.isDone)
        closed.join()
    }

    private fun registration() =
        LobbyDialogueHostRegistration.create(
            LobbyDialogueHostContract.GUIDE,
            id,
            object : gg.grounds.lobby.LobbyNavigatorService {
                override fun open(
                    player: net.minestom.server.entity.Player
                ): java.util.concurrent.CompletionStage<Boolean> =
                    error("Validation cannot open menus")
            },
            object : gg.grounds.lobby.LobbyQueueService {
                override fun toggleQueue(
                    player: net.minestom.server.entity.Player,
                    modeId: String,
                ): java.util.concurrent.CompletionStage<Boolean> = error("Validation cannot queue")

                override fun openDuels(
                    player: net.minestom.server.entity.Player
                ): java.util.concurrent.CompletionStage<Boolean> =
                    error("Validation cannot open menus")
            },
        )

    private fun scene(
        dialogue: String = id,
        npcId: String = "navigator",
        body: String = "grounds:editor/guide",
        catalogVersion: String = "1",
        speaker: String = "guide",
    ): PreparedLobbyScene {
        val assets =
            gg.grounds.scene.format.AssetCatalog(
                gg.grounds.scene.format.CatalogId("test:assets"),
                catalogVersion,
                gg.grounds.scene.format.CatalogVersionRange(
                    gg.grounds.scene.format.CatalogId("test:pack"),
                    "1",
                    "1",
                ),
                listOf("grounds:editor/guide", "test:other").associate { key ->
                    gg.grounds.scene.format.AssetKey(key).let {
                        it to
                            gg.grounds.scene.format.AssetDefinition(
                                it,
                                gg.grounds.scene.format.AssetKind.NPC_BODY,
                                emptySet(),
                                null,
                                emptyMap(),
                            )
                    }
                },
            )
        val original =
            lobbySceneFixture(
                assets,
                gg.grounds.scene.format.Vec3(0.0, 0.0, 0.0),
                gg.grounds.scene.format.Vec3(0.0, 0.0, 0.0),
            )
        val npc = original.elements.filterIsInstance<gg.grounds.scene.format.Npc>().single()
        val start =
            gg.grounds.scene.format.TriggerBinding(
                gg.grounds.scene.format.SceneTrigger.RIGHT_CLICK,
                listOf(
                    gg.grounds.scene.format.HandCondition(gg.grounds.scene.format.SceneHand.MAIN)
                ),
                0,
                0,
                listOf(
                    gg.grounds.scene.format.ApplicationAction(
                        gg.grounds.lobby.scene.LobbySceneCatalogs.START_DIALOGUE,
                        mapOf(
                            gg.grounds.lobby.scene.LobbySceneCatalogs.DIALOGUE_ID to
                                gg.grounds.scene.format.StringArgument(dialogue),
                            gg.grounds.lobby.scene.LobbySceneCatalogs.SPEAKER to
                                gg.grounds.scene.format.StringArgument(speaker),
                        ),
                    )
                ),
            )
        val mapped =
            gg.grounds.scene.format.Npc(
                gg.grounds.scene.format.LocalId(npcId),
                npc.group,
                npc.transform,
                body = gg.grounds.scene.format.AssetKey(body),
                label = null,
                labelOffset = npc.labelOffset,
                look = npc.look,
                initialAnimation = null,
                interactionBounds = npc.interactionBounds,
                proximity = null,
                bindings = listOf(start),
            )
        val actions = gg.grounds.lobby.scene.LobbySceneCatalogs.DIALOGUE
        val document =
            original.with(
                catalogs =
                    gg.grounds.scene.format.SceneCatalogReferences(
                        gg.grounds.scene.format.CatalogReference(assets.id, assets.version),
                        gg.grounds.scene.format.CatalogReference(actions.id, actions.version),
                    ),
                elements = listOf(mapped),
            )
        return PreparedLobbyScene(
            document,
            assets,
            actions,
            gg.grounds.scene.minestom.SceneRuntimeIdentity(document.id, "test:scene", 1),
            gg.grounds.scene.minestom.SceneAssetRendererRegistry { _, _ -> null },
        )
    }

    private fun load(prepared: LobbyDialogueRuntimeVerification.Prepared) =
        LobbyAuthoredDialogueCatalogue.load(
            LobbyAuthoredDialogueInputs.read(root) as LobbyAuthoredDialogueInputs.Present,
            prepared,
        )

    private fun story(prepared: LobbyDialogueRuntimeVerification.Prepared) {
        write(
            "dialogues/a.json",
            DialogueJsonCodec()
                .encode(
                    DialogueBuilder.dialogue(id)
                        .node("start", Message("guide", "hello", null, emptyList(), "end"))
                        .node("end", End("completed"))
                        .build()
                ),
        )
        write("texts/en.json", """{"hello":"Hello"}""".toByteArray())
        write("context/host-catalog.json", prepared.profile.hostCatalogBytes)
        write(
            "grounds/dialogue.json",
            LobbyDialogueDistributionBinding.json(
                mapOf(
                    "schemaVersion" to 1,
                    "profile" to
                        mapOf(
                            "id" to prepared.profile.id,
                            "version" to prepared.profile.version,
                            "sha256" to prepared.profile.sha256,
                        ),
                    "speakers" to mapOf("guide" to "navigator"),
                )
            ),
        )
    }

    private fun prepare(): LobbyDialogueRuntimeVerification.Prepared {
        val distribution = Files.createTempDirectory(root, "distribution")
        val manifest = com.google.gson.JsonObject()
        val generation = LobbyDialogueDistributionBinding.generation(manifest)
        val (profile, host) = dialogueProfileFixture(generation)
        val pins =
            LobbyDialogueDistributionBinding.files.mapValues { (role, file) ->
                val path = distribution.resolve(file)
                Files.createDirectories(path.parent)
                Files.write(
                    path,
                    when (role) {
                        "profile" -> profile
                        "hostCatalog" -> host
                        "scene" -> "{}".toByteArray()
                        else -> role.toByteArray()
                    },
                )
                LobbyDialogueDistributionBinding.pin(path, role == "addon")
            }
        val receipt =
            LobbyDialogueDistributionBinding.json(
                mapOf(
                    "schemaVersion" to 1,
                    "id" to "grounds:lobby-guide",
                    "version" to generation,
                    "generation" to generation,
                    "manifest" to manifest,
                    "files" to
                        pins.mapValues { (role, pin) ->
                            pin + ("file" to LobbyDialogueDistributionBinding.files.getValue(role))
                        },
                )
            )
        Files.write(distribution.resolve("dialogue/binding.json"), receipt)
        return LobbyDialogueRuntimeVerification.prepare(
            mapOf(
                "GROUNDS_DIALOGUE_DISTRIBUTION" to distribution.toString(),
                "GROUNDS_DIALOGUE_BINDING_SHA256" to LobbyDialogueDistributionBinding.hash(receipt),
            )
        ) as LobbyDialogueRuntimeVerification.Prepared
    }

    private fun write(path: String, bytes: ByteArray) {
        Files.createDirectories(root.resolve(path).parent)
        Files.write(root.resolve(path), bytes)
    }
}

internal fun dialogueProfileFixture(generation: String): Pair<ByteArray, ByteArray> {
    val host =
        LobbyDialogueDistributionBinding.json(
            mapOf(
                "schemaVersion" to 1,
                "generation" to generation,
                "speakers" to listOf("guide"),
                "conditions" to emptyMap<String, Any>(),
                "actions" to
                    listOf(LobbyGuideContent.NAVIGATOR_ACTION, LobbyGuideContent.DUELS_ACTION)
                        .associateWith {
                            mapOf("arguments" to emptyMap<String, Any>(), "durable" to false)
                        },
                "gestures" to emptyList<String>(),
                "capabilities" to emptyList<String>(),
                "durableOutcomes" to false,
            )
        )
    val profile =
        LobbyDialogueDistributionBinding.json(
            mapOf(
                "schemaVersion" to 1,
                "id" to "grounds:lobby-guide",
                "version" to generation,
                "hostCatalogJson" to host.toString(Charsets.UTF_8),
                "assetCatalog" to mapOf("id" to "test:assets", "version" to "1"),
                "actionCatalogs" to
                    listOf(
                        mapOf(
                            "id" to gg.grounds.lobby.scene.LobbySceneCatalogs.DIALOGUE.id.value,
                            "version" to gg.grounds.lobby.scene.LobbySceneCatalogs.DIALOGUE.version,
                        )
                    ),
                "startActions" to
                    mapOf(
                        gg.grounds.lobby.scene.LobbySceneCatalogs.START_DIALOGUE.value to
                            mapOf(
                                "dialogueArgument" to
                                    gg.grounds.lobby.scene.LobbySceneCatalogs.DIALOGUE_ID.value,
                                "speakerArgument" to
                                    gg.grounds.lobby.scene.LobbySceneCatalogs.SPEAKER.value,
                            )
                    ),
                "npcAssets" to
                    mapOf("grounds:editor/guide" to mapOf("gestures" to emptyMap<String, String>())),
            )
        )
    return profile to host
}
