package gg.grounds.minestom.lobby

import com.github.ajalt.clikt.core.FileNotFound
import io.github.oshai.kotlinlogging.KotlinLogging
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.utils.chunk.ChunkSupplier
import net.minestom.server.utils.nbt.BinaryTagReader
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.notExists

class AnvilMapManager(
    private val instance: InstanceContainer,
    private val worldDir: Path = Path.of("worlds"),
) {

    private val logger = KotlinLogging.logger {}

    companion object {
        const val DEFAULT_MAP_NAME: String = "default lobby"
    }

    var instanceSpawn: Pos = Pos.ZERO

    init {
        instance.eventNode().addListener(PlayerSpawnEvent::class.java) { event ->
            val player = event.entity

            if (player is Player) {
                player.respawnPoint = instanceSpawn
                player.teleport(instanceSpawn)
                player.setView(instanceSpawn.yaw, instanceSpawn.pitch)
            }
        }

        // Enable lighting
        instance.chunkSupplier = ChunkSupplier { instance, x, y -> LightingChunk(instance, x, y) }
    }

    fun loadMap(name: String): Result<Unit> {
        val worldPath = worldDir.resolve(name)
        if (worldPath.notExists()) {
            return Result.failure(FileNotFound("$worldPath does not exist"))
        }

        // Loads the spawn from the level.dat
        // This is Anvil specific
        this.instanceSpawn = getSpawnFromLevelDat(worldPath.resolve("level.dat").toFile())

        for (chunk in instance.chunks) {
            // Players that are in a chunk are kicked from the server.
            // The documentation of unloadChunk() states that players are not removed, but apparently they are still kicked?
            // Due to this, we kick all players now with a nice message.
            chunk.viewers.forEach { viewer -> viewer.kick("The map is reloading!") }

            instance.unloadChunk(chunk)
        }

        // After all chunks have been unloaded, this set the new chunk loader.
        // This is anvil specific
        instance.chunkLoader = AnvilLoader(worldPath)

        logger.info { "Loaded world $worldPath" }
        return Result.success(Unit)
    }

    /**
     * Loads the level.dat file
     */
    private fun getSpawnFromLevelDat(levelFile: File): Pos {
        // This is ugly but apparently that is how it is done in Kotlin.
        val root = FileInputStream(levelFile).use { fileInputStream ->
            BufferedInputStream(fileInputStream).use { bufferedStream ->
                GZIPInputStream(bufferedStream).use { zipInputStream ->
                    DataInputStream(zipInputStream).use { dataInputStream ->
                        val binaryTagReader = BinaryTagReader(dataInputStream)
                        binaryTagReader.readNamed().value as CompoundBinaryTag
                    }
                }
            }
        }

        val data = root.getCompound("Data")
        var mapSpawn: Pos? = null

        val spawnNbt = data.getCompound("spawn")
        if (spawnNbt.isEmpty.not()) {
            val spawnPos = spawnNbt.getIntArray("pos")

            if (spawnPos.isNotEmpty()) {
                mapSpawn = Pos(
                    spawnPos[0].toDouble(),
                    spawnPos[1].toDouble(),
                    spawnPos[2].toDouble(),
                    spawnNbt.getFloat("yaw"),
                    spawnNbt.getFloat("pitch")
                )
            }
        }

        if (mapSpawn == null) {
            mapSpawn = Pos(
                data.getInt("SpawnX").toDouble(),
                data.getInt("SpawnY").toDouble(),
                data.getInt("SpawnZ").toDouble(),
            )
        }

        // center
        return mapSpawn.add(0.5, 0.0, 0.5)
    }
}