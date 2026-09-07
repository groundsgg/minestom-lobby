package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.LobbyNavigatorService
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
