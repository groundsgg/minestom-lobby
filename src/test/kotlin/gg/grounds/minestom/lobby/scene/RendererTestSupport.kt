package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneAssetRenderContext
import gg.grounds.scene.minestom.SceneRenderTransform
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.instance.AddEntityToInstanceEvent
import net.minestom.server.event.instance.InstanceChunkLoadEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.network.ConnectionState
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SpawnEntityPacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Assertions.assertTrue

internal val unitBounds = LocalBounds(Vec3(.5, .5, .5), Vec3(1.0, 1.0, 1.0))
internal val guideKey = AssetKey("grounds:editor/guide")
internal val markerKey = AssetKey("grounds:editor/marker")

internal fun sceneTransform(position: Vec3 = Vec3(0.0, 0.0, 0.0)) =
    SceneRenderTransform(
        Transform(position, EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
        null,
    )

internal class RendererFixture(val bounds: LocalBounds = unitBounds) : AutoCloseable {
    val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
    val registry =
        LobbyPlaceholderRenderers(
            AssetCatalog(
                CatalogId("grounds:editor"),
                "1.0.0",
                CatalogVersionRange(CatalogId("grounds:pack"), "1.0.0", "1.0.0"),
                listOf(guideKey to AssetKind.NPC_BODY, markerKey to AssetKind.PROP).associate {
                    (key, kind) ->
                    key to AssetDefinition(key, kind, emptySet(), bounds, emptyMap())
                },
            )
        )
    lateinit var display: HighlightableBlockDisplay

    init {
        instance.eventNode().addListener(AddEntityToInstanceEvent::class.java) {
            if (it.entity is HighlightableBlockDisplay)
                display = it.entity as HighlightableBlockDisplay
        }
    }

    fun create(
        transform: SceneRenderTransform = sceneTransform(),
        key: AssetKey = guideKey,
        kind: AssetKind = AssetKind.NPC_BODY,
        target: Instance = instance,
    ) =
        registry
            .rendererFor(key, kind)!!
            .create(SceneAssetRenderContext(target, LocalId("fixture"), null, key, transform))
            .toCompletableFuture()

    // Player's pinned future overrides join to process the scheduled spawn on the caller thread.
    fun player(position: Pos = Pos.ZERO) =
        TestPlayer().also { it.setInstance(instance, position).join() }

    override fun close() {
        instance.entities.toList().forEach { it.remove() }
        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }
}

internal class TestPlayer : Player(CapturingConnection(), GameProfile(UUID.randomUUID(), "test")) {
    val connection
        get() = playerConnection as CapturingConnection
}

internal class CapturingConnection : PlayerConnection() {
    val packets = CopyOnWriteArrayList<ServerPacket>()
    @Volatile var beforeSend: ((ServerPacket) -> Unit)? = null

    override fun sendPacket(packet: SendablePacket) {
        val decoded = SendablePacket.extractServerPacket(ConnectionState.PLAY, packet) ?: return
        beforeSend?.invoke(decoded)
        packets += decoded
    }

    override fun getRemoteAddress() = InetSocketAddress(0)

    fun flags(id: Int) =
        packets
            .filterIsInstance<EntityMetaDataPacket>()
            .filter { it.entityId() == id }
            .mapNotNull { it.entries()[0]?.value() as? Byte }
            .map { it.toInt() }

    fun spawns(id: Int) =
        packets.filterIsInstance<SpawnEntityPacket>().filter { it.entityId() == id }
}

/**
 * Gate only chunk I/O; real InstanceContainer completion runs all attachment/movement callbacks.
 */
internal class ChunkGate : AutoCloseable {
    val entered = CountDownLatch(1)
    val settled = CountDownLatch(1)
    private val release = CompletableFuture<Unit>()

    fun install(instance: InstanceContainer, chunkX: Int) {
        // The pinned implementation fires this event after completing the load future and
        // dependents.
        instance.eventNode().addListener(InstanceChunkLoadEvent::class.java) {
            if (it.chunk.chunkX == chunkX) settled.countDown()
        }
        instance.chunkLoader =
            object : ChunkLoader {
                override fun supportsParallelLoading() = true

                override fun loadChunk(instance: Instance, x: Int, z: Int): Chunk? {
                    if (x == chunkX) {
                        entered.countDown()
                        release.get(5, TimeUnit.SECONDS)
                    }
                    return null
                }

                override fun saveChunk(chunk: Chunk) = Unit
            }
    }

    override fun close() {
        release.complete(Unit)
    }
}

internal fun CountDownLatch.awaitReady() {
    assertTrue(await(5, TimeUnit.SECONDS), "Timed out waiting for packet/chunk boundary")
}

internal fun awaitBlocked(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) {
        Thread.yield()
    }
    assertTrue(
        thread.state == Thread.State.BLOCKED,
        "Expected lifecycle operation to block behind packet send, got ${thread.state}",
    )
}
