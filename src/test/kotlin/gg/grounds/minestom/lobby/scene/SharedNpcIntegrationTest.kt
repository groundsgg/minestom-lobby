package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.npc.minestom.PersonalNpcRenderedAssetHandle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.MetadataDef
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
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
                """{"schemaVersion":1,"asset":"test:bot","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[],"parts":[{"bone":null,"model":"grounds:npc_parts/test/cube","center":[0,1,0],"pivot":[0,1,0],"rotation":[0,0,0]}],"animations":{"idle":{"loop":"loop","length":1,"tracks":{}},"wave":{"loop":"once","length":1,"tracks":{}}},"sounds":{"wave":{"key":"test:wave","volume":0.8,"pitch":1,"radius":12,"durationMillis":5000}}}"""
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
            val admission = handle as AnimationAdmissionRenderedAssetHandle
            assertFalse(admission.canStartAnimation("wave"))
            assertNull(admission.reserveAnimation("wave"))
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

    @Test
    fun `only the root Spawn Guide delivers every tick without changing other NPC cohorts`() {
        MinecraftServer.init()
        val key = AssetKey("test:animated")
        val model =
            decodeNpcModel(
                """{"schemaVersion":1,"asset":"test:animated","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[{"id":"body","parent":null,"origin":[0,0,0],"rotation":[0,0,0]}],"parts":[{"bone":"body","model":"grounds:npc_parts/test/body","center":[0,1,0],"pivot":[0,1,0],"rotation":[0,0,0]}],"animations":{"idle":{"loop":"loop","length":2,"tracks":{}},"wave":{"loop":"once","length":2,"tracks":{"body":{"position":[{"time":0,"value":[0,0,0],"interpolation":"linear"},{"time":2,"value":[0,2,0],"interpolation":"linear"}]}}}},"sounds":{}}"""
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
                        )
                ),
            )
        val registry = LobbySceneRenderers(assets, mapOf(key to model))
        // The same asset on another element or an attached part must retain normal budgets.
        val cases =
            listOf(
                Triple("spawn-guide", null, listOf(50L, 50L, 50L)),
                Triple("another-guide", null, listOf(200L, 250L, 500L)),
                Triple("spawn-guide", "hat", listOf(200L, 250L, 500L)),
            )
        RendererFixture().use { fixture ->
            val allViewers = listOf(1.0, 24.0, 50.0).map { fixture.player(Pos(it, 0.0, 0.0)) }
            for ((element, part, intervals) in cases) {
                for (farOnly in listOf(false, true)) {
                    allViewers[0].teleport(Pos(if (farOnly) 100.0 else 1.0, 0.0, 0.0)).join()
                    allViewers[1].teleport(Pos(if (farOnly) 100.0 else 24.0, 0.0, 0.0)).join()
                    val viewers = if (farOnly) allViewers.takeLast(1) else allViewers
                    val expectedIntervals = if (farOnly) listOf(intervals.last()) else intervals
                    val scope = Scope()
                    val factory =
                        registry.rendererFor(key, AssetKind.NPC_BODY)
                            as ScopedSceneAssetRendererFactory
                    val creating =
                        factory
                            .createScoped(
                                SceneAssetRenderContext(
                                    fixture.instance,
                                    LocalId(element),
                                    part?.let(::LocalId),
                                    key,
                                    sceneTransform(),
                                ),
                                scope,
                            )
                            .toCompletableFuture()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (!creating.isDone && System.nanoTime() < deadline) fixture.instance.tick(
                        0
                    )
                    val handle = creating.get(1, TimeUnit.SECONDS)
                    assertTrue(handle is PersonalNpcRenderedAssetHandle)
                    assertTrue(handle is AnimationAdmissionRenderedAssetHandle)
                    val display =
                        fixture.instance.entities.single {
                            it.entityType == EntityType.ITEM_DISPLAY
                        }
                    handle.startAnimation(LocalId("wave"), 0)
                    fixture.instance.tick(0)
                    fun updates(viewer: TestPlayer) =
                        viewer.connection.packets.filterIsInstance<EntityMetaDataPacket>().filter {
                            it.entityId() == display.entityId
                        }
                    val duration = MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION.index()
                    for ((viewer, interval) in viewers.zip(expectedIntervals)) {
                        assertEquals(
                            (interval / 50).toInt(),
                            updates(viewer).mapNotNull { it.entries()[duration]?.value() }.last(),
                            "Interpolation for $element/$part at ${viewer.position}",
                        )
                    }
                    allViewers.forEach { it.connection.packets.clear() }
                    val receivedAt = viewers.associateWith { mutableListOf<Long>() }
                    val translation = MetadataDef.Display.TRANSLATION.index()
                    for (elapsed in 50L..1000L step 50L) {
                        handle.advanceAnimation(elapsed)
                        fixture.instance.tick(0)
                        for (viewer in viewers) {
                            val frames = updates(viewer).filter { translation in it.entries() }
                            if (frames.isNotEmpty()) {
                                assertEquals(
                                    1,
                                    frames.size,
                                    "Duplicate pose at $elapsed for $element/$part",
                                )
                                if (element == "spawn-guide" && part == null) {
                                    val position =
                                        frames.single().entries().getValue(translation).value()
                                            as Point
                                    assertEquals(
                                        1.0 + elapsed / 1000.0,
                                        position.y(),
                                        1e-6,
                                        "Current Guide sample at $elapsed",
                                    )
                                }
                                receivedAt.getValue(viewer).add(elapsed)
                            }
                            viewer.connection.packets.clear()
                        }
                    }
                    for ((viewer, interval) in viewers.zip(expectedIntervals)) {
                        assertEquals(
                            (interval..1000L step interval).toList(),
                            receivedAt.getValue(viewer),
                            "Pose cadence for $element/$part at ${viewer.position}, farOnly=$farOnly",
                        )
                    }
                    assertEquals(
                        1,
                        fixture.instance.entities.count { it.entityType == EntityType.ITEM_DISPLAY },
                    )
                    scope.invalidate()
                    val cleanup =
                        CompletableFuture.allOf(
                            *scope.cleanups.map { it.toCompletableFuture() }.toTypedArray()
                        )
                    val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (!cleanup.isDone && System.nanoTime() < cleanupDeadline) fixture.instance
                        .tick(0)
                    cleanup.get(1, TimeUnit.SECONDS)
                    assertTrue(
                        fixture.instance.entities.none { it.entityType == EntityType.ITEM_DISPLAY }
                    )
                    // Closing after invalidation remains idempotent and cannot resume delivery.
                    (handle as AsyncRenderedAssetHandle)
                        .closeAsync()
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS)
                    handle.advanceAnimation(1050)
                    fixture.instance.tick(0)
                    assertTrue(
                        fixture.instance.entities.none { it.entityType == EntityType.ITEM_DISPLAY }
                    )
                    assertTrue(
                        viewers.all {
                            updates(it).none { packet -> translation in packet.entries() }
                        }
                    )
                    assertTrue(scope.failures.isEmpty())
                    allViewers.forEach { it.connection.packets.clear() }
                }
            }
        }
    }

    private class Scope : SceneRendererLifetime {
        var valid = true
        val callbacks = mutableListOf<Runnable>()
        val failures = mutableListOf<Throwable>()

        val cleanups = mutableListOf<CompletionStage<Void>>()

        override fun trackCleanup(cleanup: CompletionStage<Void>) {
            cleanups += cleanup
        }

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
