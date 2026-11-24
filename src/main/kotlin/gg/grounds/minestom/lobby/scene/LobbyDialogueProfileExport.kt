package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.GroundsPluginLobbyProvider
import gg.grounds.minestom.lobby.LoadedLobbyMap
import gg.grounds.minestom.lobby.LobbyMapSource
import gg.grounds.minestom.lobby.NpcAddonBinding
import gg.grounds.minestom.lobby.selectedRuntimeProviderIds
import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.core.DefaultServiceRegistry
import gg.grounds.modules.get
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.runtime.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.system.exitProcess
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.instance.InstanceContainer

/** One-shot, private tooling entry point; never starts a listener, handler or player. */
object LobbyDialogueProfileExport {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val status =
            try {
                export(arguments, System.getenv())
                0
            } catch (failure: Throwable) {
                System.err.println(
                    "Dialogue profile export failed (reason=${failure.javaClass.simpleName})"
                )
                failure.printStackTrace(System.err)
                1
            }
        exitProcess(status)
    }

    internal fun requireToolingEnvironment(environment: Map<String, String>) {
        val allowed =
            setOf(
                "PATH",
                "JAVA_HOME",
                "HOME",
                "LANG",
                "LC_ALL",
                "TMPDIR",
                "TEMP",
                "TMP",
                "TZ",
                "SYSTEMROOT",
                "WINDIR",
                "COMSPEC",
                "PATHEXT",
                "OS",
            )
        require(environment.keys.all { it.uppercase(java.util.Locale.ROOT) in allowed }) {
            "Export requires an explicitly sanitized tooling environment"
        }
    }

    internal fun export(arguments: Array<String>, environment: Map<String, String>) {
        require(arguments.size == 4) { "Expected --distribution ROOT --scene DIR" }
        val options = arguments.toList().chunked(2)
        require(options.map { it[0] }.toSet() == setOf("--distribution", "--scene")) {
            "Expected unique distribution and scene flags"
        }
        val flags = options.associate { it[0] to it[1] }
        requireToolingEnvironment(environment)
        require(System.getProperty("grounds.npc.content") == null) {
            "Export requires explicit distribution content"
        }
        fun directory(value: String): Path {
            val path = Path.of(value).toAbsolutePath().normalize()
            var part = path.root
            for (component in path) {
                part = part.resolve(component)
                require(!Files.isSymbolicLink(part)) { "Export paths must not contain symlinks" }
            }
            require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Export directory must exist" }
            return path
        }
        val root = directory(flags.getValue("--distribution"))
        val selected = directory(flags.getValue("--scene")).resolve("scene.json")
        val destination = root.resolve("dialogue")
        require(!Files.exists(destination, NOFOLLOW_LINKS)) { "Dialogue output already exists" }
        val inputs =
            mapOf(
                "executable" to root.resolve("minestom-lobby.jar"),
                "serverContent" to root.resolve("server-content.zip"),
                "addon" to root.resolve("npc-addon.zip"),
                "catalog" to root.resolve("catalog.jar"),
                "scene" to selected,
            )
        val pins =
            inputs.mapValues { (role, path) ->
                LobbyDialogueDistributionBinding.pin(path, role == "addon")
            }
        val executable = inputs.getValue("executable")
        fun requireExecutable(type: Class<*>) {
            val source = Path.of(requireNotNull(type.protectionDomain.codeSource).location.toURI())
            require(Files.isSameFile(source, executable)) {
                "Exporter and loaded runtime metadata must come from the selected executable"
            }
        }
        requireExecutable(LobbyDialogueProfileExport::class.java)
        requireExecutable(GroundsAssetCatalog::class.java)
        requireExecutable(GroundsPluginLobbyProvider::class.java)
        require((pins.getValue("scene").getValue("size") as Long) <= 16L * 1024 * 1024) {
            "Selected scene exceeds 16 MiB"
        }
        val sceneBytes =
            Files.newInputStream(selected, NOFOLLOW_LINKS).use {
                it.readNBytes(16 * 1024 * 1024 + 1)
            }
        require(
            sceneBytes.size <= 16 * 1024 * 1024 &&
                LobbyDialogueDistributionBinding.hash(sceneBytes) ==
                    pins.getValue("scene")["sha256"]
        ) {
            "Selected scene changed during snapshot"
        }
        val assets = GroundsAssetCatalog.catalog
        LobbyDialogueDistributionBinding.verifyCatalog(
            executable,
            inputs.getValue("catalog"),
            assets.version,
        )
        NpcAddonBinding.load().requireMatches(pins.getValue("addon").getValue("sha1") as String)
        val content = GroundsNpcModelSource.loadRequired(assets, inputs.getValue("serverContent"))
        content.verifyAddon(inputs.getValue("addon"))
        var composition: LobbySceneComposition? = null
        var instance: InstanceContainer? = null
        val hooks = mutableListOf<() -> Unit>()
        val services = DefaultServiceRegistry()
        val provider = GroundsPluginLobbyProvider()
        require(
            provider.id == "grounds.lobby.navigator" &&
                provider.id in selectedRuntimeProviderIds(emptyMap())
        ) {
            "Dialogue service variant is not selected by this runtime"
        }
        val module = provider.create()
        val snapshot = Files.createTempDirectory("lobby-dialogue-scene-")
        var initialized = false
        var failure: Throwable? = null
        var bound: LobbyDialogueDistributionBinding.BoundProfile? = null
        try {
            Files.write(snapshot.resolve("scene.json"), sceneBytes)
            System.setProperty("minestom.shutdown-on-signal", "false")
            initialized = true
            MinecraftServer.init()
            module.install(
                object : GroundsServerContext {
                    override val serverType = ServerType.LOBBY
                    override val environment = RuntimeEnvironment.TEST
                    override val services: ServiceRegistry = services

                    override fun eventNode(name: String): EventNode<Event> = EventNode.all(name)

                    override fun onShutdown(action: () -> Unit) {
                        hooks += action
                    }
                }
            )
            require(
                services.get<gg.grounds.lobby.LobbyNavigatorService>() != null &&
                    services.get<gg.grounds.lobby.LobbyQueueService>() != null
            ) {
                "Actual lobby module did not install both menu services"
            }
            instance = MinecraftServer.getInstanceManager().createInstanceContainer()
            val prepared =
                requireNotNull(
                    LobbySceneLoader(assets, LobbySceneRenderers(assets, content.models))
                        .load(LoadedLobbyMap(snapshot, LobbyMapSource.Local(snapshot)))
                ) {
                    "Selected scene is missing"
                }
            composition = LobbySceneComposition.create(prepared, instance, services)
            bound = LobbyDialogueDistributionBinding.manifest(composition, provider, content, pins)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
            }
            cleanup {
                composition
                    ?.dialogues
                    ?.closeAsync()
                    ?.toCompletableFuture()
                    ?.get(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            cleanup { module.stop() }
            hooks.asReversed().forEach { cleanup(it) }
            cleanup {
                instance?.let { MinecraftServer.getInstanceManager().unregisterInstance(it) }
            }
            if (initialized) cleanup { MinecraftServer.stopCleanly() }
            cleanup {
                Files.deleteIfExists(snapshot.resolve("scene.json"))
                Files.deleteIfExists(snapshot)
            }
        }
        failure?.let { throw it }
        val result = requireNotNull(bound)
        val receipt = result.receipt(pins)
        require(
            inputs.all { (role, path) ->
                pins.getValue(role) == LobbyDialogueDistributionBinding.pin(path, role == "addon")
            }
        ) {
            "Distribution input changed before qualification"
        }
        val staging = Files.createTempDirectory(root, ".dialogue-export-")
        try {
            result.profile.writeTo(staging)
            Files.write(staging.resolve("scene.json"), sceneBytes)
            Files.write(staging.resolve("binding.json"), receipt)
            require(
                inputs.all { (role, path) ->
                    pins.getValue(role) ==
                        LobbyDialogueDistributionBinding.pin(path, role == "addon")
                }
            ) {
                "Distribution input changed before publication"
            }
            require(!Files.exists(destination, NOFOLLOW_LINKS)) {
                "Dialogue output appeared during export"
            }
            Files.move(staging, destination, ATOMIC_MOVE)
        } finally {
            if (Files.exists(staging, NOFOLLOW_LINKS)) {
                Files.walk(staging).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        }
    }
}
