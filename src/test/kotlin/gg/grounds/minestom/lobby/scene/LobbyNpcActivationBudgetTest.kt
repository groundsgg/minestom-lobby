package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.modules.core.DefaultServiceRegistry
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbyNpcActivationBudgetTest {
    private class NearbyNpcs : AutoCloseable {
        val fixture = RendererFixture()
        val starts = mutableListOf<Int>()
        var tick = 0
        val runtime: SceneRuntime

        init {
            fixture.player()
            val assets = GroundsAssetCatalog.catalog
            val authored = lobbySceneFixture(assets, Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
            val npc = authored.elements.filterIsInstance<Npc>().single()
            val scene =
                authored.with(
                    elements =
                        (0..2).map { index ->
                            Npc(
                                id = LocalId("queued-$index"),
                                group = null,
                                transform = npc.transform,
                                activation = ActivationPolicy.AUTOMATIC,
                                body = npc.body,
                                label = null,
                                labelOffset = npc.labelOffset,
                                look = npc.look,
                                initialAnimation = null,
                                interactionBounds = npc.interactionBounds,
                                proximity = null,
                                bindings = emptyList(),
                            )
                        }
                )
            val renderers =
                object : SceneAssetRendererRegistry {
                    override fun rendererFor(
                        asset: AssetKey,
                        kind: AssetKind,
                    ): SceneAssetRendererFactory? =
                        fixture.registry.rendererFor(asset, kind)?.let { delegate ->
                            SceneAssetRendererFactory { context ->
                                starts += tick
                                delegate.create(context)
                            }
                        }
                }
            val prepared =
                PreparedLobbyScene(
                    scene,
                    assets,
                    LobbySceneCatalogs.CURRENT,
                    SceneRuntimeIdentity(scene.id, "lobby/test", 1),
                    renderers,
                )
            val created =
                SceneRuntimeFactory.create(
                        prepared.runtimeRequest(fixture.instance, DefaultServiceRegistry())
                    )
                    .toCompletableFuture()
            repeatUntil { created.isDone }
            runtime =
                assertInstanceOf(SceneRuntimeCreationResult.Success::class.java, created.join())
                    .runtime
        }

        fun step() {
            tick++
            fixture.instance.tick(tick * 50L)
        }

        fun repeatUntil(done: () -> Boolean) {
            val deadline = tick + 50
            while (!done() && tick < deadline) step()
            assertTrue(done(), "Scene did not settle within fifty controlled ticks")
        }

        override fun close() {
            try {
                val closed = runtime.close().toCompletableFuture()
                repeatUntil { closed.isDone }
                closed.get(2, TimeUnit.SECONDS)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `nearby automatic NPCs are built across successive ticks and all become complete`() {
        NearbyNpcs().use { run ->
            run.repeatUntil { run.starts.size == 3 }
            assertTrue(
                run.starts.groupingBy { it }.eachCount().values.all { it == 1 },
                "Several NPCs were built in one tick: ${run.starts}",
            )
            assertEquals(
                3,
                run.fixture.instance.entities.filterIsInstance<HighlightableBlockDisplay>().size,
            )
        }
    }

    @Test
    fun `closing during the activation queue prevents later NPC construction`() {
        NearbyNpcs().use { run ->
            run.repeatUntil { run.starts.isNotEmpty() }
            assertEquals(1, run.starts.size, "First activation drained the whole queue")
            val closed = run.runtime.close().toCompletableFuture()
            run.repeatUntil { closed.isDone }
            // Scene close is marshalled to its owner tick; in-flight work may settle before it.
            val startsAtCloseCompletion = run.starts.size
            repeat(15) { run.step() }
            assertEquals(
                startsAtCloseCompletion,
                run.starts.size,
                "Closed runtime continued building queued NPCs",
            )
            assertTrue(run.fixture.instance.entities.none { it is HighlightableBlockDisplay })
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
