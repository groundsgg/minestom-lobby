package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.SceneEffectSink
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle

/** Only exact sound assets supplied by the same immutable catalogue as the scene. */
internal class LobbySceneEffects(private val assets: AssetCatalog) : SceneEffectSink {
    override fun supports(asset: AssetKey, kind: AssetKind) =
        assets.assets[asset]?.kind == kind &&
            (kind == AssetKind.SOUND || (kind == AssetKind.PARTICLE && asset == PARTICLE))

    override fun playSound(player: Player, sound: AssetKey, volume: Double, pitch: Double) {
        require(supports(sound, AssetKind.SOUND)) { "Unsupported lobby sound ${sound.value}" }
        player.playSound(
            Sound.sound(Key.key(sound.value), Sound.Source.VOICE, volume.toFloat(), pitch.toFloat())
        )
    }

    fun playNear(instance: Instance, origin: Vec3, sound: NpcClipSound) {
        require(supports(AssetKey(sound.key), AssetKind.SOUND))
        val event =
            Sound.sound(
                Key.key(sound.key),
                Sound.Source.VOICE,
                sound.volume.toFloat(),
                sound.pitch.toFloat(),
            )
        instance.players
            .filter { player ->
                val p = player.position
                (p.x() - origin.x) * (p.x() - origin.x) +
                    (p.y() - origin.y) * (p.y() - origin.y) +
                    (p.z() - origin.z) * (p.z() - origin.z) <= sound.radius * sound.radius
            }
            .forEach { it.playSound(event, origin.x, origin.y, origin.z) }
    }

    override fun emitParticle(
        instance: Instance,
        particle: AssetKey,
        point: Point,
        count: Int,
        offset: Vec3,
        speed: Double,
    ) {
        require(supports(particle, AssetKind.PARTICLE)) { "Unsupported lobby particle" }
        val packet =
            ParticlePacket(
                Particle.HAPPY_VILLAGER,
                point,
                net.minestom.server.coordinate.Vec(offset.x, offset.y, offset.z),
                speed.toFloat(),
                count,
            )
        instance.players
            .filter { it.position.distanceSquared(point) <= 32.0 * 32.0 }
            .forEach { it.sendPacket(packet) }
    }

    private companion object {
        val PARTICLE = AssetKey("grounds:scene/happy_villager")
    }
}
