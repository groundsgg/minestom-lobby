package gg.grounds.minestom.lobby

import gg.grounds.minestom.lobby.scene.RendererFixture
import gg.grounds.scene.format.*
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.*
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerSwapItemEvent
import net.minestom.server.item.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StageNpcDemoOffhandTest {
    @Test
    fun `cancelled swap hand key dispatches a genuine offhand interaction only while aiming at the hand station`() {
        MinecraftServer.init()
        RendererFixture().use { fixture ->
            val npc =
                Npc(
                    LocalId("demo-hands"),
                    null,
                    Transform(
                        Vec3(-23.0, 173.0, 1.0),
                        EulerRotation(0.0, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                    body = AssetKey("grounds:editor/guide"),
                    label = null,
                    labelOffset = Vec3(0.0, 0.0, 0.0),
                    look = LookBehavior.Fixed,
                    initialAnimation = null,
                    interactionBounds = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                    proximity = null,
                    bindings =
                        listOf(
                            TriggerBinding(
                                SceneTrigger.RIGHT_CLICK,
                                listOf(HandCondition(SceneHand.OFF)),
                                0,
                                0,
                                listOf(
                                    SendMessageAction(
                                        net.kyori.adventure.text.Component.text("off")
                                    )
                                ),
                            )
                        ),
                )
            val target = Entity(EntityType.INTERACTION)
            target.setInstance(fixture.instance, Pos(-23.0, 173.0, 1.0)).join()
            val player = fixture.player(Pos(-23.0, 173.0, -2.0, 0f, 0f))
            val access = StageNpcDemoAccess(fixture.instance, handStation = npc) { true }
            val node = EventNode.all("demo-offhand-input")
            val seen = mutableListOf<PlayerEntityInteractEvent>()
            node.addListener(PlayerEntityInteractEvent::class.java) { seen += it }
            access.register(node)
            MinecraftServer.getGlobalEventHandler().addChild(node)
            fun swap() {
                EventDispatcher.call(
                    PlayerSwapItemEvent(player, ItemStack.AIR, ItemStack.AIR).also {
                        it.isCancelled = true
                    }
                )
            }
            try {
                swap()
                assertEquals(1, seen.size)
                assertSame(target, seen.single().target)
                assertEquals(PlayerHand.OFF, seen.single().hand)
                player.teleport(player.position.withView(180f, 0f)).join()
                swap()
                assertEquals(1, seen.size)
                access.close()
                player.teleport(player.position.withView(0f, 0f)).join()
                swap()
                assertEquals(1, seen.size)
            } finally {
                MinecraftServer.getGlobalEventHandler().removeChild(node)
                access.close()
            }
        }
    }
}
