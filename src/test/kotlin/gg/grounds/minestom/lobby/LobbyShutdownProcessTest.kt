package gg.grounds.minestom.lobby

import gg.grounds.minestom.lobby.scene.lobbySceneFixture
import gg.grounds.minestom.lobby.scene.with
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.core.GroundsServer
import gg.grounds.runtime.core.RuntimeConfig
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.ServerFlag
import net.minestom.server.coordinate.Pos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyShutdownProcessTest {
    @Test
    fun `launcher owns JVM shutdown so real scene cleanup precedes provider and Minestom stop`(
        @TempDir root: Path
    ) {
        val authored = lobbySceneFixture(GroundsAssetCatalog.catalog, ORIGIN, ORIGIN)
        val marker =
            authored.elements
                .filterIsInstance<Prop>()
                .single()
                .copy(activation = ActivationPolicy.ALWAYS)
        Files.write(
            root.resolve("scene.json"),
            (SceneJson.encode(authored.with(elements = listOf(marker)))
                    as SceneEncodeResult.Success)
                .bytes,
        )
        val output = runProbe(root.toString())
        val ready = output.indexOf("SCENE_READY shutdown-on-signal=false")
        val provider = output.indexOf("PROVIDER_STOP_AFTER_SCENE_CLEANUP")
        val stopped = output.indexOf("Stopping Grounds server.")
        assertTrue(ready >= 0, output)
        assertTrue(provider > ready, output)
        assertTrue(stopped > provider, output)
        assertFalse(output.contains("TimeoutException"), output)
        assertFalse(output.contains("cleanup did not complete successfully"), output)
    }

    @Test
    fun `bootstrap fails closed if another launcher initialized the competing shutdown hook flag`() {
        assertTrue(runProbe("already-initialized").contains("LATE_BOOTSTRAP_REJECTED"))
    }

    private fun runProbe(argument: String): String {
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-ea",
                    "-Dminestom.shutdown-on-signal=true",
                    "-cp",
                    checkNotNull(System.getProperty("lobby.test.runtimeClasspath")),
                    LobbyShutdownProcessProbe::class.java.name,
                    argument,
                )
                .redirectErrorStream(true)
                .start()
        try {
            assertTrue(
                process.waitFor(12, TimeUnit.SECONDS),
                "Shutdown subprocess exceeded 12 seconds",
            )
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

/**
 * Separate JVM: exercise the actual launcher bootstrap, then real pinned runtime shutdown hooks.
 */
internal object LobbyShutdownProcessProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.single() == "already-initialized") {
            check(ServerFlag.SHUTDOWN_ON_SIGNAL)
            val failure = runCatching { buildLobbyServer(emptyMap()) }.exceptionOrNull()
            check(
                failure is IllegalStateException && failure.message.orEmpty().contains("before")
            ) {
                "Late bootstrap did not reject an already-initialized signal hook: $failure"
            }
            println("LATE_BOOTSTRAP_REJECTED")
            return
        }
        val env =
            mapOf(
                "GROUNDS_SERVER_TYPE" to "lobby",
                "GROUNDS_ENV" to "test",
                "GROUNDS_BIND_HOST" to "127.0.0.1",
                "GROUNDS_BIND_PORT" to "0",
                "GROUNDS_ONLINE_MODE" to "false",
                "GROUNDS_PROXY_MODE" to "offline",
                "GROUNDS_METRICS_ENABLED" to "false",
            )
        // This is the same bootstrap called by MainKt -> LobbyServer.start. Provider discovery
        // must not initialize ServerFlag before it has disabled Minestom's competing JVM hook.
        buildLobbyServer(env)
        check(!ServerFlag.SHUTDOWN_ON_SIGNAL) {
            "Lobby bootstrap left independent Minestom signal shutdown enabled"
        }

        val root = Path.of(arguments.single())
        lateinit var server: GroundsServer
        lateinit var world: LobbyMap
        val created = CompletableFuture<SceneRuntime>()
        val provider =
            object : GroundsModule {
                override val id = "test.cleanup-order-provider"

                override fun install(ctx: GroundsServerContext) = Unit

                override fun stop() {
                    check(created.join().isClosed)
                    check(world.instance.entities.isEmpty()) {
                        "Scene entities survived before provider stop"
                    }
                    println("PROVIDER_STOP_AFTER_SCENE_CLEANUP")
                }
            }
        val lobby =
            LobbyModule(
                fatalStop = { server.stop() },
                createWorld = {
                    LobbyMap(
                            MinecraftServer.getInstanceManager().createInstanceContainer(),
                            Pos.ZERO,
                            LoadedLobbyMap(root, LobbyMapSource.Local(root)),
                        )
                        .also { world = it }
                },
                createRuntime = { request ->
                    SceneRuntimeFactory.create(request).thenApply { result ->
                        check(result is SceneRuntimeCreationResult.Success) {
                            "Unexpected creation failure: $result"
                        }
                        created.complete(result.runtime)
                        result
                    }
                },
            )
        server =
            GroundsServer.builder()
                .config(RuntimeConfig.fromEnvironment(env))
                .use(provider)
                .use(lobby)
                .build()
        server.start()
        created.get(5, TimeUnit.SECONDS)
        // Owner acknowledgement lets the creation call stack finish publishing host readiness.
        val ownerAcknowledged = CompletableFuture<Void>()
        world.instance.scheduler().execute { ownerAcknowledged.complete(null) }
        ownerAcknowledged.get(5, TimeUnit.SECONDS)
        check(world.instance.entities.isNotEmpty()) { "Probe never installed a real scene display" }
        println("SCENE_READY shutdown-on-signal=${ServerFlag.SHUTDOWN_ON_SIGNAL}")
        kotlin.system.exitProcess(0)
    }
}
