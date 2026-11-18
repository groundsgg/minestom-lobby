package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneViewerVisualState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.metadata.display.BlockDisplayMeta
import net.minestom.server.event.EventListener
import net.minestom.server.event.entity.EntityDespawnEvent
import net.minestom.server.event.instance.AddEntityToInstanceEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@org.junit.jupiter.api.Timeout(20)
class PlaceholderDisplayHandleTest {
    @Test
    fun `factory waits for real attachment and tracks distant displays automatically`() {
        RendererFixture().use { fixture ->
            assertNull(fixture.registry.rendererFor(guideKey, AssetKind.PROP))
            assertNull(fixture.registry.rendererFor(markerKey, AssetKind.NPC_BODY))
            assertNull(fixture.registry.rendererFor(AssetKey("grounds:unknown"), AssetKind.PROP))
            val origin = fixture.player()
            ChunkGate().use { gate ->
                gate.install(fixture.instance, 62)
                val future = fixture.create(sceneTransform(Vec3(1000.0, 64.0, 1000.0)))
                gate.entered.awaitReady()
                val display = fixture.display
                assertFalse(future.isDone)
                assertNull(fixture.instance.getEntityById(display.entityId))
                assertEquals(
                    Block.LAPIS_BLOCK,
                    (display.entityMeta as BlockDisplayMeta).blockStateId,
                )
                gate.close()
                future.get(5, TimeUnit.SECONDS).use { handle ->
                    assertSame(display, fixture.instance.getEntityById(display.entityId))
                    assertEquals(Pos(1000.0, 64.0, 1000.0), display.position)
                    val nearby = fixture.player(Pos(1000.0, 64.0, 1000.0))
                    assertTrue(nearby.connection.spawns(display.entityId).isNotEmpty())
                    assertTrue(origin.connection.spawns(display.entityId).isEmpty())
                    val destination = fixture.player(Pos(2000.0, 64.0, 1000.0))
                    handle.applyTransform(sceneTransform(Vec3(2000.0, 64.0, 1000.0)))
                    assertEquals(Pos(2000.0, 64.0, 1000.0), display.position)
                    assertTrue(destination.connection.spawns(display.entityId).isNotEmpty())
                    assertFalse(display.isViewer(nearby))
                }
            }
            fixture.create(key = markerKey, kind = AssetKind.PROP).get(5, TimeUnit.SECONDS).use {
                assertEquals(
                    Block.REDSTONE_BLOCK,
                    (fixture.display.entityMeta as BlockDisplayMeta).blockStateId,
                )
            }
        }
    }

    enum class AttachmentFailure {
        UNLOADED,
        LOAD_EXCEPTION,
        ADD_CANCELLED,
        UNREGISTERED,
    }

    @ParameterizedTest
    @EnumSource(AttachmentFailure::class)
    fun `factory failure never returns a handle and removes the display`(
        failure: AttachmentFailure
    ) {
        RendererFixture().use { fixture ->
            var removed: HighlightableBlockDisplay? = null
            val removal =
                EventListener.of(EntityDespawnEvent::class.java) {
                    if (it.entity is HighlightableBlockDisplay)
                        removed = it.entity as HighlightableBlockDisplay
                }
            MinecraftServer.getGlobalEventHandler().addListener(removal)
            val exceptions = MinecraftServer.getExceptionManager()
            val previousHandler = exceptions.exceptionHandler
            val reported = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            exceptions.setExceptionHandler { reported += it }
            try {
                var target: Instance = fixture.instance
                when (failure) {
                    AttachmentFailure.UNLOADED -> fixture.instance.enableAutoChunkLoad(false)
                    AttachmentFailure.LOAD_EXCEPTION ->
                        fixture.instance.chunkLoader =
                            object : ChunkLoader {
                                override fun loadChunk(
                                    instance: Instance,
                                    chunkX: Int,
                                    chunkZ: Int,
                                ): Chunk? = throw IllegalStateException("fixture chunk failure")

                                override fun saveChunk(chunk: Chunk) = Unit
                            }
                    AttachmentFailure.ADD_CANCELLED ->
                        fixture.instance.eventNode().addListener(
                            AddEntityToInstanceEvent::class.java
                        ) {
                            it.isCancelled = true
                        }
                    AttachmentFailure.UNREGISTERED ->
                        target =
                            InstanceContainer(
                                java.util.UUID.randomUUID(),
                                fixture.instance.dimensionType,
                            )
                }
                val result = fixture.create(target = target)
                assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
                assertNotNull(removed)
                val display = removed!!
                assertTrue(display.isRemoved)
                assertNull(target.getEntityById(display.entityId))
                assertNull(display.viewerState)
                if (
                    failure == AttachmentFailure.UNLOADED ||
                        failure == AttachmentFailure.LOAD_EXCEPTION
                )
                    assertTrue(reported.isNotEmpty())
            } finally {
                exceptions.setExceptionHandler(previousHandler)
                MinecraftServer.getGlobalEventHandler().removeListener(removal)
            }
        }
    }

    @Test
    fun `cancelled caller releases a display after pending attachment settles`() {
        RendererFixture().use { fixture ->
            ChunkGate().use { gate ->
                gate.install(fixture.instance, 0)
                val future = fixture.create()
                gate.entered.awaitReady()
                val display = fixture.display
                assertTrue(future.cancel(false))
                gate.close()
                gate.settled.awaitReady()
                assertTrue(display.isRemoved)
                assertNull(fixture.instance.getEntityById(display.entityId))
                assertNull(display.viewerState)
            }
        }
    }

    @Test
    fun `final client flags remain private across transform retracking clear and close`() {
        RendererFixture().use { fixture ->
            val a = fixture.player()
            val b = fixture.player()
            val handle = fixture.create().get(5, TimeUnit.SECONDS)
            val display = fixture.display
            display.entityMeta.setOnFire(true)
            a.connection.packets.clear()
            b.connection.packets.clear()
            handle.applyViewerState(a, SceneViewerVisualState(highlighted = true))
            assertEquals(0x41, a.connection.flags(display.entityId).last())
            assertTrue(b.connection.flags(display.entityId).isEmpty())
            assertFalse(display.entityMeta.isHasGlowingEffect)
            handle.applyTransform(sceneTransform(Vec3(2.0, 0.0, 0.0)))
            assertEquals(0x41, a.connection.flags(display.entityId).last())
            display.removeViewer(a)
            display.addViewer(a)
            assertEquals(listOf(0x01, 0x41), a.connection.flags(display.entityId).takeLast(2))
            handle.clearViewerState(a)
            assertEquals(0x01, a.connection.flags(display.entityId).last())
            display.removeViewer(a)
            display.addViewer(a)
            assertEquals(0x01, a.connection.flags(display.entityId).last())
            handle.applyViewerState(a, SceneViewerVisualState(highlighted = true))
            handle.close()
            assertNull(display.viewerState)
            assertTrue(display.isRemoved)
            // Packet silence alone cannot prove the handle released its stored UUIDs.
            val retained =
                PlaceholderDisplayHandle::class
                    .java
                    .getDeclaredField("highlighted")
                    .apply { isAccessible = true }
                    .get(handle) as Map<*, *>
            assertTrue(retained.isEmpty())
            a.connection.packets.clear()
            handle.applyViewerState(a, SceneViewerVisualState(highlighted = true))
            handle.clearViewerState(a)
            handle.applyTransform(sceneTransform())
            display.updateNewViewer(a)
            assertTrue(
                a.connection.packets.isEmpty(),
                "Closed display must never spawn or replay again",
            )
            assertTrue(retained.isEmpty(), "Late apply must not repopulate closed viewer state")
        }
    }

    @Test
    fun `clear waits for in-flight replay and wins final packet ordering`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            fixture.create().get(5, TimeUnit.SECONDS).use { handle ->
                val display = fixture.display
                handle.applyViewerState(player, SceneViewerVisualState(highlighted = true))
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                player.connection.beforeSend = { packet ->
                    if (
                        packet is EntityMetaDataPacket &&
                            (packet.entries()[0]?.value() as? Byte)?.toInt() == 0x40
                    ) {
                        entered.countDown()
                        release.awaitReady()
                    }
                }
                val replay = CompletableFuture.runAsync { display.updateNewViewer(player) }
                try {
                    entered.awaitReady()
                    val clear = Thread.ofPlatform().start { handle.clearViewerState(player) }
                    awaitBlocked(clear)
                    release.countDown()
                    replay.get(5, TimeUnit.SECONDS)
                    clear.join(5000)
                    assertFalse(clear.isAlive)
                    assertEquals(0, player.connection.flags(display.entityId).last())
                } finally {
                    release.countDown()
                    player.connection.beforeSend = null
                }
            }
        }
    }

    @Test
    fun `close waits for base spawn and suppresses a later tracking callback`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val handle = fixture.create().get(5, TimeUnit.SECONDS)
            val display = fixture.display
            handle.applyViewerState(player, SceneViewerVisualState(highlighted = true))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            player.connection.beforeSend = { packet ->
                if (packet is SpawnEntityPacket && packet.entityId() == display.entityId) {
                    entered.countDown()
                    release.awaitReady()
                }
            }
            val spawn = CompletableFuture.runAsync { display.updateNewViewer(player) }
            try {
                entered.awaitReady()
                val close = Thread.ofPlatform().start { handle.close() }
                awaitBlocked(close)
                release.countDown()
                spawn.get(5, TimeUnit.SECONDS)
                close.join(5000)
                assertFalse(close.isAlive)
                assertTrue(player.connection.packets.last() is DestroyEntitiesPacket)
                val count = player.connection.packets.size
                display.updateNewViewer(player)
                handle.applyViewerState(player, SceneViewerVisualState(highlighted = true))
                assertEquals(count, player.connection.packets.size)
                assertNull(display.viewerState)
            } finally {
                release.countDown()
                player.connection.beforeSend = null
                handle.close()
            }
        }
    }

    @Test
    fun `late chunk completion cannot overwrite newer transform`() {
        RendererFixture().use { fixture ->
            val handle = fixture.create().get(5, TimeUnit.SECONDS)
            val display = fixture.display
            ChunkGate().use { gate ->
                gate.install(fixture.instance, 125)
                handle.applyTransform(sceneTransform(Vec3(2000.0, 0.0, 0.0)))
                gate.entered.awaitReady()
                handle.applyTransform(sceneTransform(Vec3(2.0, 0.0, 0.0)))
                gate.close()
                gate.settled.awaitReady()
                assertEquals(Pos(2.0, 0.0, 0.0), display.position)
            }
            handle.close()
        }
    }

    @Test
    fun `late chunk completion cannot move a closed display`() {
        RendererFixture().use { fixture ->
            val player = fixture.player()
            val handle = fixture.create().get(5, TimeUnit.SECONDS)
            val display = fixture.display
            ChunkGate().use { gate ->
                gate.install(fixture.instance, 250)
                handle.applyTransform(sceneTransform(Vec3(4000.0, 0.0, 0.0)))
                gate.entered.awaitReady()
                handle.close()
                player.connection.packets.clear()
                gate.close()
                gate.settled.awaitReady()
                assertEquals(Pos.ZERO, display.position)
                assertNull(fixture.instance.getEntityById(display.entityId))
                assertTrue(player.connection.spawns(display.entityId).isEmpty())
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
