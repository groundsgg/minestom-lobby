package gg.grounds.minestom.lobby

import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.interaction.SceneBoundsRaycaster
import java.util.UUID
import net.kyori.adventure.text.Component
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.Event
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.EventListener
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.player.PlayerSwapItemEvent
import net.minestom.server.instance.Instance

internal fun stageNpcDemoEnabled(environment: Map<String, String> = System.getenv()): Boolean {
    if (environment["GROUNDS_NPC_DEMO_ENABLED"] != "true") return false
    require(environment["MAPS_ENVIRONMENT"] == "stage") { "NPC demo may only activate on Stage" }
    return true
}

internal fun requireStageNpcDemoMap(
    source: LobbyMapSource,
    scene: SceneDocument?,
    expectedSha256: String,
) {
    require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "NPC demo needs an exact map digest" }
    require(
        source is LobbyMapSource.Published &&
            source.address == "lobby/mainlobby" &&
            source.sha256 == expectedSha256
    ) {
        "NPC demo requires its verified published map; fallback is not safe"
    }
    require(
        scene != null &&
            scene.elements.count { it.id.value.startsWith("demo-") } == 15 &&
            scene.elements.any { it.id.value == "sensor-demo-entry" }
    ) {
        "NPC demo scene is missing"
    }
}

internal class StageNpcDemoAccess(
    private val instance: Instance,
    private val handStation: Npc? = null,
    private val permitted: (Player) -> Boolean,
) : AutoCloseable {
    private val modes = mutableMapOf<UUID, GameMode>()
    @Volatile private var closed = false

    fun register(node: EventNode<Event>) {
        node.addListener(PlayerMoveEvent::class.java) { moved(it.player, it.newPosition) }
        node.addListener(PlayerSpawnEvent::class.java) { moved(it.player, it.player.position) }
        node.addListener(PlayerDisconnectEvent::class.java) { disconnected(it.player) }
        node.addListener(EntityTeleportEvent::class.java) { event ->
            (event.entity as? Player)?.let { moved(it, event.newPosition) }
        }
        node.addListener(
            EventListener.builder(PlayerSwapItemEvent::class.java)
                .ignoreCancelled(false)
                .handler { if (offhand(it.player)) it.isCancelled = true }
                .build()
        )
    }

    private fun offhand(player: Player): Boolean {
        val npc = handStation ?: return false
        if (closed || player.instance !== instance) return false
        val eye = player.position.add(0.0, player.eyeHeight, 0.0)
        val direction = player.position.direction()
        if (
            SceneBoundsRaycaster.distance(
                npc.interactionBounds,
                npc.transform,
                null,
                Vec3(eye.x(), eye.y(), eye.z()),
                Vec3(direction.x(), direction.y(), direction.z()),
                5.0,
            ) == null
        )
            return false
        // This authored station is fixed and unscaled. Match its exact Interaction anchor,
        // then dispatch through the same instance event/condition path as a native click.
        val p = npc.transform.position
        val bounds = npc.interactionBounds
        val anchor =
            Pos(
                p.x + bounds.center.x,
                p.y + bounds.center.y - bounds.size.y / 2,
                p.z + bounds.center.z,
            )
        val target =
            instance.entities
                .filter {
                    it.entityType == EntityType.INTERACTION &&
                        it.position.distanceSquared(anchor) < 1e-8
                }
                .singleOrNull() ?: return false
        EventDispatcher.call(
            PlayerEntityInteractEvent(
                player,
                target,
                PlayerHand.OFF,
                net.minestom.server.coordinate.Vec.ZERO,
            )
        )
        return true
    }

    fun enter(player: Player) {
        if (closed || player.instance !== instance) return
        player.teleport(ENTRANCE)
        player.sendMessage(
            Component.text(
                "NPC-Testplattform: Die Labels erklären jede Station. /spawn bringt dich zurück."
            )
        )
    }

    @Synchronized
    fun toggleMode(player: Player): Boolean {
        if (
            closed || player.instance !== instance || !inside(player.position) || !permitted(player)
        )
            return false
        modes.putIfAbsent(player.uuid, player.gameMode)
        player.gameMode =
            if (player.gameMode == GameMode.CREATIVE) GameMode.ADVENTURE else GameMode.CREATIVE
        player.sendMessage(
            Component.text(
                "NPC-Testmodus: ${player.gameMode.name}. Beim Verlassen wird dein vorheriger Modus wiederhergestellt."
            )
        )
        return true
    }

    fun moved(player: Player, position: Point) {
        if (player.instance !== instance || !inside(position)) restore(player)
    }

    @Synchronized
    fun restore(player: Player) {
        modes.remove(player.uuid)?.let { player.gameMode = it }
    }

    @Synchronized
    fun disconnected(player: Player) {
        modes.remove(player.uuid)
    }

    @Synchronized
    override fun close() {
        closed = true
        instance.players.toList().forEach(::restore)
        modes.clear()
    }

    private fun inside(p: Point) =
        p.x() >= -27 && p.x() < 14 && p.z() >= -15 && p.z() < 21 && p.y() >= 172 && p.y() < 197

    companion object {
        val ENTRANCE = Pos(-7.0, 173.0, 17.0, 180f, 0f)
    }
}

internal class StageNpcDemoCommand(private val access: StageNpcDemoAccess) : Command("npctest") {
    init {
        setDefaultExecutor { sender, _ ->
            if (sender is Player) access.enter(sender)
            else sender.sendMessage(Component.text("Dieser Befehl ist nur im Spiel verfügbar."))
        }
        addSyntax(
            { sender, _ ->
                if (sender is Player && !access.toggleMode(sender))
                    sender.sendMessage(
                        Component.text(
                            "Den Testmodus können nur Editoren auf der NPC-Testplattform umschalten."
                        )
                    )
            },
            ArgumentType.Literal("mode"),
        )
    }
}
