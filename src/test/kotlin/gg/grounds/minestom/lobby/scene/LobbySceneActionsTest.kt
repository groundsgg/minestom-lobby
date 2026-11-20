package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.LobbyNavigatorService
import gg.grounds.lobby.LobbyQueueService
import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.permissions.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbySceneActionsTest {
    @Test
    fun `only navigator key resolves and successful service completion is propagated`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val opened = CompletableFuture<Boolean>()
            val received = mutableListOf<Player>()
            val registry =
                LobbySceneActions(
                    object : LobbyNavigatorService {
                        override fun open(player: Player): CompletableFuture<Boolean> {
                            received += player
                            return opened
                        }
                    },
                    LobbyScenePlayerPolicy(fixture.instance, null),
                )
            assertNull(registry.handlerFor(ActionKey("grounds:unrelated")))
            val handler = registry.handlerFor(LobbySceneCatalogs.OPEN_NAVIGATOR)
            assertNotNull(handler)
            val result = handler!!.execute(context(player)).toCompletableFuture()
            assertFalse(result.isDone)
            assertEquals(listOf(player), received)
            opened.complete(true)
            assertEquals(SceneActionResult.Success, result.join())
        }
    }

    @Test
    fun `navigator unavailable is rejected and service exceptions are not converted to success`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val service =
                object : LobbyNavigatorService {
                    override fun open(player: Player) = CompletableFuture.completedFuture(false)
                }
            val handler =
                LobbySceneActions(service, LobbyScenePlayerPolicy(fixture.instance, null))
                    .handlerFor(LobbySceneCatalogs.OPEN_NAVIGATOR)
            assertNotNull(handler)
            assertEquals(
                SceneActionResult.Rejected("Navigator unavailable"),
                handler!!.execute(context(player)).toCompletableFuture().join(),
            )
            val failure = IllegalStateException("menu failed")
            val failing =
                object : LobbyNavigatorService {
                    override fun open(player: Player) =
                        CompletableFuture.failedFuture<Boolean>(failure)
                }
            val result =
                LobbySceneActions(failing, LobbyScenePlayerPolicy(fixture.instance, null))
                    .handlerFor(LobbySceneCatalogs.OPEN_NAVIGATOR)!!
                    .execute(context(player))
                    .toCompletableFuture()
            assertSame(
                failure,
                assertThrows(java.util.concurrent.CompletionException::class.java) { result.join() }
                    .cause,
            )
        }
    }

    @Test
    fun `arguments disconnected players and wrong instance never open the service`() {
        RendererFixture().use { fixture ->
            val service =
                object : LobbyNavigatorService {
                    override fun open(player: Player): CompletableFuture<Boolean> =
                        fail("Rejected player reached navigator")
                }
            val handler =
                LobbySceneActions(service, LobbyScenePlayerPolicy(fixture.instance, null))
                    .handlerFor(LobbySceneCatalogs.OPEN_NAVIGATOR)
            assertNotNull(handler)
            val player = fixture.player()
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                handler!!
                    .execute(
                        context(player)
                            .copy(
                                arguments = mapOf(LocalId("unexpected") to StringArgument("value"))
                            )
                    )
                    .toCompletableFuture()
                    .join(),
            )
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                handler.execute(context(TestPlayer())).toCompletableFuture().join(),
            )
            player.playerConnection.disconnect()
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                handler.execute(context(player)).toCompletableFuture().join(),
            )
        }
    }

    @Test
    fun `permission predicate delegates exact player and key and absent service denies`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val checked = mutableListOf<Pair<UUID, String>>()
            val permissions =
                object : Permissions {
                    override fun hasPermission(playerId: UUID, permission: String): Boolean {
                        checked += playerId to permission
                        return permission == "lobby.guide"
                    }

                    override fun hasPermission(
                        playerId: UUID,
                        permission: String,
                        scope: PermissionCheckScope,
                    ) = false

                    // Abstract since plugin-permissions 1.0.0; agree with hasPermission above.
                    override fun permissionDecision(playerId: UUID, permission: String) =
                        if (permission == "lobby.guide") PermissionDecision.ALLOW
                        else PermissionDecision.DENY

                    override fun permissionDecision(
                        playerId: UUID,
                        permission: String,
                        scope: PermissionCheckScope,
                    ) = PermissionDecision.DENY

                    override fun snapshot(playerId: UUID): PermissionSnapshot? = null
                }
            val policy = LobbyScenePlayerPolicy(fixture.instance, permissions)
            assertTrue(policy.isEligible(player))
            assertTrue(policy.hasPermission(player, "lobby.guide"))
            assertFalse(policy.hasPermission(player, "lobby.admin"))
            assertEquals(
                listOf(player.uuid to "lobby.guide", player.uuid to "lobby.admin"),
                checked,
            )
            assertFalse(
                LobbyScenePlayerPolicy(fixture.instance, null).hasPermission(player, "lobby.guide")
            )
            assertNull(
                LobbySceneActions(null, policy).handlerFor(LobbySceneCatalogs.OPEN_NAVIGATOR)
            )
        }
    }

    @Test
    fun `queue actions delegate the exact mode and duel selection without commands`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val received = mutableListOf<String>()
            val service =
                object : LobbyQueueService {
                    override fun toggleQueue(player: Player, modeId: String) =
                        CompletableFuture.completedFuture(true).also { received += modeId }

                    override fun openDuels(player: Player) =
                        CompletableFuture.completedFuture(true).also { received += "duel-menu" }
                }
            val registry =
                LobbySceneActions(null, LobbyScenePlayerPolicy(fixture.instance, null), service)
            val toggle = registry.handlerFor(ActionKey("grounds:lobby/toggle_queue"))!!
            assertEquals(
                SceneActionResult.Success,
                toggle
                    .execute(
                        context(player)
                            .copy(
                                arguments = mapOf(LocalId("mode") to StringArgument("bedwars-2x1"))
                            )
                    )
                    .toCompletableFuture()
                    .join(),
            )
            assertEquals(
                SceneActionResult.Success,
                registry
                    .handlerFor(ActionKey("grounds:lobby/open_duel_menu"))!!
                    .execute(context(player))
                    .toCompletableFuture()
                    .join(),
            )
            assertEquals(listOf("bedwars-2x1", "duel-menu"), received)
        }
    }

    @Test
    fun `malformed queue arguments and a player outside the scene cannot reach the queue service`() {
        RendererFixture().use { fixture ->
            val service =
                object : LobbyQueueService {
                    override fun toggleQueue(
                        player: Player,
                        modeId: String,
                    ): CompletableFuture<Boolean> = fail("Invalid queue action reached service")

                    override fun openDuels(player: Player): CompletableFuture<Boolean> =
                        fail("Invalid duel action reached service")
                }
            val registry =
                LobbySceneActions(null, LobbyScenePlayerPolicy(fixture.instance, null), service)
            val handler = registry.handlerFor(ActionKey("grounds:lobby/toggle_queue"))!!
            val player = fixture.player()
            for (args in
                listOf(
                    emptyMap(),
                    mapOf(LocalId("mode") to StringArgument("/queue leave")),
                    mapOf(LocalId("mode") to LongArgument(1)),
                    mapOf(
                        LocalId("mode") to StringArgument("duel"),
                        LocalId("extra") to StringArgument("x"),
                    ),
                )) {
                assertInstanceOf(
                    SceneActionResult.Rejected::class.java,
                    handler
                        .execute(context(player).copy(arguments = args))
                        .toCompletableFuture()
                        .join(),
                )
            }
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                handler
                    .execute(
                        context(TestPlayer())
                            .copy(arguments = mapOf(LocalId("mode") to StringArgument("duel")))
                    )
                    .toCompletableFuture()
                    .join(),
            )
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                registry
                    .handlerFor(ActionKey("grounds:lobby/open_duel_menu"))!!
                    .execute(
                        context(player)
                            .copy(arguments = mapOf(LocalId("extra") to StringArgument("x")))
                    )
                    .toCompletableFuture()
                    .join(),
            )
        }
    }

    @Test
    fun `dialogue preserves exact context and asynchronous starter result`() {
        RendererFixture().use { fixture ->
            val expected =
                context(fixture.player())
                    .copy(
                        arguments =
                            mapOf(
                                LobbySceneCatalogs.DIALOGUE_ID to StringArgument("guide/welcome"),
                                LobbySceneCatalogs.SPEAKER to StringArgument("guide"),
                            )
                    )
            val completion = CompletableFuture<SceneActionResult>()
            val handler =
                LobbySceneActions(
                        null,
                        LobbyScenePlayerPolicy(fixture.instance, null),
                        dialogueStarter =
                            LobbySceneDialogueStarter { context, dialogue, speaker ->
                                assertSame(expected, context)
                                assertEquals("guide/welcome", dialogue)
                                assertEquals("guide", speaker)
                                completion
                            },
                    )
                    .handlerFor(LobbySceneCatalogs.START_DIALOGUE)!!
            val result = handler.execute(expected).toCompletableFuture()
            assertFalse(result.isDone)
            completion.complete(SceneActionResult.Rejected("Dialogue unavailable"))
            assertEquals(SceneActionResult.Rejected("Dialogue unavailable"), result.join())
        }
    }

    @Test
    fun `dialogue rejects incorrect arguments and ineligible players before invoking starter`() {
        RendererFixture().use { fixture ->
            val handler =
                LobbySceneActions(
                        null,
                        LobbyScenePlayerPolicy(fixture.instance, null),
                        dialogueStarter =
                            LobbySceneDialogueStarter { _, _, _ ->
                                fail("Rejected action reached starter")
                            },
                    )
                    .handlerFor(LobbySceneCatalogs.START_DIALOGUE)!!
            val valid =
                mapOf(
                    LobbySceneCatalogs.DIALOGUE_ID to StringArgument("guide/welcome"),
                    LobbySceneCatalogs.SPEAKER to StringArgument("guide"),
                )
            listOf(
                    emptyMap(),
                    valid - LobbySceneCatalogs.SPEAKER,
                    valid + (LocalId("extra") to StringArgument("x")),
                    valid + (LobbySceneCatalogs.SPEAKER to BooleanArgument(true)),
                )
                .forEach { arguments ->
                    assertInstanceOf(
                        SceneActionResult.Rejected::class.java,
                        handler
                            .execute(context(fixture.player()).copy(arguments = arguments))
                            .toCompletableFuture()
                            .join(),
                    )
                }
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                handler
                    .execute(context(TestPlayer()).copy(arguments = valid))
                    .toCompletableFuture()
                    .join(),
            )
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                handler
                    .execute(
                        context(fixture.player())
                            .copy(arguments = valid, trigger = SceneTrigger.HOVER_ENTER)
                    )
                    .toCompletableFuture()
                    .join(),
            )
            val disconnected = fixture.player()
            disconnected.playerConnection.disconnect()
            assertInstanceOf(
                SceneActionResult.Rejected::class.java,
                handler
                    .execute(context(disconnected).copy(arguments = valid))
                    .toCompletableFuture()
                    .join(),
            )
            assertNull(
                LobbySceneActions(null, LobbyScenePlayerPolicy(fixture.instance, null))
                    .handlerFor(LobbySceneCatalogs.START_DIALOGUE)
            )
        }
    }

    @Test
    fun `dialogue scene without installed starter fails runtime preparation`() {
        RendererFixture().use { fixture ->
            val assets = gg.grounds.resourcepacks.catalog.GroundsAssetCatalog.catalog
            val authored = lobbySceneFixture(assets, Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
            val npc = authored.elements.filterIsInstance<Npc>().single()
            val scene =
                authored.with(
                    elements =
                        listOf(
                            npc.with(
                                bindings =
                                    listOf(
                                        TriggerBinding(
                                            SceneTrigger.RIGHT_CLICK,
                                            emptyList(),
                                            0,
                                            0,
                                            listOf(
                                                ApplicationAction(
                                                    LobbySceneCatalogs.START_DIALOGUE,
                                                    mapOf(
                                                        LobbySceneCatalogs.DIALOGUE_ID to
                                                            StringArgument("guide/welcome"),
                                                        LobbySceneCatalogs.SPEAKER to
                                                            StringArgument("guide"),
                                                    ),
                                                )
                                            ),
                                        )
                                    )
                            )
                        )
                )
            val prepared =
                PreparedLobbyScene(
                    scene,
                    assets,
                    LobbySceneCatalogs.DIALOGUE,
                    SceneRuntimeIdentity(scene.id, "lobby/test", 1),
                    fixture.registry,
                )
            assertTrue(
                assertThrows(IllegalStateException::class.java) {
                        prepared.runtimeRequest(
                            fixture.instance,
                            gg.grounds.modules.core.DefaultServiceRegistry(),
                        )
                    }
                    .message!!
                    .contains("dialogue starter")
            )
        }
    }

    private fun context(player: Player) =
        SceneActionContext(
            SceneRuntimeIdentity(SceneId("grounds:lobby-stage"), "lobby/mainlobby", 4),
            player,
            LocalId("navigator"),
            SceneTrigger.RIGHT_CLICK,
            SceneHand.MAIN,
            10,
            emptyMap(),
            SceneViewerVisualState(),
        )

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
