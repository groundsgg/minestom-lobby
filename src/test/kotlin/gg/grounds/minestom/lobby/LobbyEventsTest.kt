package gg.grounds.minestom.lobby

import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LobbyEventsTest {
    @Test
    fun `configuration during STARTING waits then admits only when scene is ready`() {
        val ready = ObservedReady()
        withAdmission(ready) { node, event, instance ->
            val completed = CompletableFuture<Void>()
            val thread =
                Thread.startVirtualThread {
                    node.call(event)
                    completed.complete(null)
                }
            try {
                assertTrue(
                    ready.entered.await(2, TimeUnit.SECONDS),
                    "Admission did not await readiness",
                )
                assertFalse(completed.isDone)
                assertNull(event.spawningInstance)
                ready.complete(null)
                completed.get(2, TimeUnit.SECONDS)
                assertSame(instance, event.spawningInstance)
                assertEquals(30, ready.waitSeconds)
            } finally {
                ready.completeExceptionally(IllegalStateException("test cleanup"))
                thread.join(2000)
            }
        }
    }

    @Test
    fun `failed or closed scene never assigns a spawning instance`() {
        withAdmission(CompletableFuture.failedFuture(IllegalStateException("startup failed"))) {
            node,
            event,
            _ ->
            Thread.startVirtualThread { node.call(event) }.join()
            assertNull(event.spawningInstance)
        }
        withAdmission(CompletableFuture.completedFuture(null), { true }) { node, event, _ ->
            Thread.startVirtualThread { node.call(event) }.join()
            assertNull(event.spawningInstance)
        }
    }

    @Test
    fun `shutdown while configuration awaits readiness denies admission`() {
        val ready = ObservedReady()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        withAdmission(ready, closed::get) { node, event, _ ->
            val thread = Thread.startVirtualThread { node.call(event) }
            try {
                assertTrue(ready.entered.await(2, TimeUnit.SECONDS))
                closed.set(true)
                ready.complete(null)
                thread.join(2000)
                assertFalse(thread.isAlive)
                assertNull(event.spawningInstance)
            } finally {
                ready.complete(null)
                thread.join(2000)
            }
        }
    }

    @Test
    fun `disconnect while awaiting readiness never assigns a spawning instance`() {
        val ready = ObservedReady()
        withAdmission(ready) { node, event, _ ->
            val thread = Thread.startVirtualThread { node.call(event) }
            try {
                assertTrue(ready.entered.await(2, TimeUnit.SECONDS))
                event.player.playerConnection.disconnect()
                ready.complete(null)
                thread.join(2000)
                assertFalse(thread.isAlive)
                assertNull(event.spawningInstance)
            } finally {
                ready.complete(null)
                thread.join(2000)
            }
        }
    }

    @Test
    fun `nonvirtual configuration never blocks on pending scene readiness`() {
        val ready = ObservedReady()
        withAdmission(ready) { node, event, _ ->
            assertFalse(LobbyEvents.awaitSceneAdmission(event.player, ready) { false })
            assertEquals(1, ready.entered.count)
            assertNull(event.spawningInstance)
        }
    }

    @Test
    fun `readiness timeout denies admission without changing the shared future`() {
        val ready =
            object : CompletableFuture<Void>() {
                override fun get(timeout: Long, unit: TimeUnit): Void? {
                    assertEquals(30, unit.toSeconds(timeout))
                    throw TimeoutException("controlled 30-second deadline")
                }
            }
        withAdmission(ready) { node, event, _ ->
            Thread.startVirtualThread { node.call(event) }.join()
            assertNull(event.spawningInstance)
            assertFalse(ready.isDone)
        }
    }

    private fun withAdmission(
        ready: CompletableFuture<Void>,
        closed: () -> Boolean = { false },
        test:
            (
                net.minestom.server.event.EventNode<net.minestom.server.event.Event>,
                AsyncPlayerConfigurationEvent,
                net.minestom.server.instance.InstanceContainer,
            ) -> Unit,
    ) {
        val node = EventNode.all("scene-admission-test")
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player = Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex"))
        try {
            LobbyEvents.register(node, instance, Pos(2.0, 64.0, 3.0), ready, closed)
            test(node, AsyncPlayerConfigurationEvent(player, true), instance)
        } finally {
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
        }
    }

    private class ObservedReady : CompletableFuture<Void>() {
        val entered = CountDownLatch(1)
        var waitSeconds = -1L

        override fun get(timeout: Long, unit: TimeUnit): Void? {
            waitSeconds = unit.toSeconds(timeout)
            entered.countDown()
            return super.get(timeout, unit)
        }
    }

    @Test
    fun `configuration assigns the supplied instance and exact spawn point`() {
        val node = EventNode.all("lobby-events-test")
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val spawn = Pos(10.5, 64.0, -3.5, 90.0f, -12.5f)
        val player = Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex"))
        val event = AsyncPlayerConfigurationEvent(player, true)
        LobbyEvents.register(node, instance, spawn)

        Thread.startVirtualThread { node.call(event) }.join()

        assertSame(instance, event.spawningInstance)
        assertEquals(spawn, player.respawnPoint)
    }

    private class FakeConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) {}

        override fun getRemoteAddress(): SocketAddress? = null
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
