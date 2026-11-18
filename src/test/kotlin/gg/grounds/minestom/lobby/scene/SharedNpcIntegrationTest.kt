package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SharedNpcIntegrationTest {
    @Test
    fun `Grounds shared renderer preserves exact sound delivery and active generation cleanup`() {
        MinecraftServer.init()
        val key = AssetKey("test:bot")
        val sound = AssetKey("test:wave")
        val model =
            decodeNpcModel(
                """{"schemaVersion":1,"asset":"test:bot","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[],"parts":[{"bone":null,"model":"grounds:npc_parts/test/cube","center":[0,1,0],"pivot":[0,1,0],"rotation":[0,0,0]}],"animations":{"idle":{"loop":"loop","length":1,"tracks":{}},"wave":{"loop":"once","length":1,"tracks":{}}},"sounds":{"wave":{"key":"test:wave","volume":0.8,"pitch":1,"radius":12}}}"""
            )
        val assets =
            AssetCatalog(
                CatalogId("test:assets"),
                "1",
                CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
                mapOf(
                    key to
                        AssetDefinition(
                            key,
                            AssetKind.NPC_BODY,
                            setOf(LocalId("idle"), LocalId("wave")),
                            LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                            emptyMap(),
                        ),
                    sound to AssetDefinition(sound, AssetKind.SOUND, emptySet(), null, emptyMap()),
                ),
            )
        val registry = LobbySceneRenderers(assets, mapOf(key to model))
        assertTrue(registry.supportsAnimation(key, LocalId("wave")))
        assertFalse(registry.supportsAnimation(key, LocalId("missing")))
        assertTrue(registry.supportsViewerScale(key))
        RendererFixture().use { fixture ->
            val near = fixture.player(Pos(1.0, 1.0, 1.0))
            val distant = fixture.player(Pos(30.0, 1.0, 30.0))
            val scope = Scope()
            val factory =
                registry.rendererFor(key, AssetKind.NPC_BODY) as ScopedSceneAssetRendererFactory
            val creating =
                factory
                    .createScoped(
                        SceneAssetRenderContext(
                            fixture.instance,
                            LocalId("bot"),
                            null,
                            key,
                            sceneTransform(),
                        ),
                        scope,
                    )
                    .toCompletableFuture()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!creating.isDone && System.nanoTime() < deadline) fixture.instance.tick(0)
            val handle = creating.get(1, TimeUnit.SECONDS)
            fun sounds(player: TestPlayer) =
                player.connection.packets.count { it.javaClass.simpleName == "SoundEffectPacket" }
            assertEquals(
                1,
                fixture.instance.entities.count { it.entityType == EntityType.ITEM_DISPLAY },
            )
            handle.startAnimation(LocalId("wave"), 0)
            fixture.instance.tick(0)
            assertEquals(1, sounds(near))
            assertEquals(0, sounds(distant))
            handle.advanceAnimation(500)
            fixture.instance.tick(0)
            handle.advanceAnimation(1500)
            fixture.instance.tick(0)
            assertEquals(1, sounds(near))
            scope.invalidate()
            (handle as AsyncRenderedAssetHandle)
                .closeAsync()
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS)
            handle.startAnimation(LocalId("wave"), 0)
            fixture.instance.tick(0)
            assertEquals(1, sounds(near))
            assertTrue(fixture.instance.entities.none { it.entityType == EntityType.ITEM_DISPLAY })
            assertTrue(scope.failures.isEmpty())
        }
    }

    private class Scope : SceneRendererLifetime {
        var valid = true
        val callbacks = mutableListOf<Runnable>()
        val failures = mutableListOf<Throwable>()

        override fun trackCleanup(cleanup: java.util.concurrent.CompletionStage<Void>) {}

        override fun isCurrent() = valid

        override fun runIfCurrent(operation: Runnable): Boolean {
            if (!valid) return false
            operation.run()
            return true
        }

        override fun onInvalidated(callback: Runnable): AutoCloseable {
            callbacks += callback
            return AutoCloseable { callbacks -= callback }
        }

        override fun reportFailure(failure: Throwable) {
            if (valid) failures += failure
        }

        fun invalidate() {
            valid = false
            callbacks.toList().forEach(Runnable::run)
        }
    }
}
