package gg.grounds.minestom.lobby.scene

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import gg.grounds.lobby.GroundsPluginLobbyProvider
import gg.grounds.minestom.lobby.NpcAddonBinding
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * One-shot verification of a deployment-anchored private distribution and the actual composition.
 */
internal object LobbyDialogueRuntimeVerification {
    sealed interface Preparation

    sealed interface Decision

    data object Unconfigured : Decision, Preparation

    data class Admitted(val buildGeneration: String) : Decision

    data class Denied(val code: String) : Decision, Preparation

    class Prepared
    private constructor(
        internal val root: Path,
        receipt: ByteArray,
        paths: Map<String, Path>,
        pins: Map<String, Map<String, Any>>,
        val generation: String,
        val profile: LobbyDialogueTrustedProfile,
    ) : Preparation {
        private val receipt = receipt.clone()
        internal val paths = java.util.Map.copyOf(paths)
        internal val pins = java.util.Map.copyOf(pins.mapValues { java.util.Map.copyOf(it.value) })

        internal fun receiptBytes() = receipt.clone()

        /** Rechecks captured operator inputs only; never issues an admission decision. */
        fun requireUnchanged() {
            paths.forEach { (role, path) ->
                requireSafe(path)
                val current =
                    jsonLimit(role)?.let { LobbyDialogueFileSnapshot.pin(bounded(path, it)) }
                        ?: LobbyDialogueDistributionBinding.pin(path, role == "addon")
                require(current == pins.getValue(role))
            }
            require(bounded(root.resolve("dialogue/binding.json"), 1048576).contentEquals(receipt))
        }

        companion object {
            internal fun anchored(environment: Map<String, String>): Preparation =
                capture(environment)

            private fun capture(environment: Map<String, String>): Preparation {
                if (DIRECTORY !in environment && DIGEST !in environment) return Unconfigured
                var phase = "configuration_invalid"
                return try {
                    val root = Path.of(requireNotNull(environment[DIRECTORY]))
                    val anchor = requireNotNull(environment[DIGEST])
                    require(
                        root.isAbsolute && root.toString().isNotBlank() && sha256.matches(anchor)
                    )
                    phase = "distribution_invalid"
                    requireSafe(root)
                    require(Files.isDirectory(root, NOFOLLOW_LINKS))
                    val binding = root.resolve("dialogue/binding.json")
                    val receiptBytes = bounded(binding, 1048576)
                    require(LobbyDialogueDistributionBinding.hash(receiptBytes) == anchor)
                    val receipt = strictJson(receiptBytes).asJsonObject
                    require(
                        receipt.keySet() ==
                            setOf(
                                "schemaVersion",
                                "id",
                                "version",
                                "generation",
                                "manifest",
                                "files",
                            )
                    )
                    require(integer(receipt["schemaVersion"]) == 1L)
                    val generation = string(receipt["generation"])
                    require(sha256.matches(generation) && string(receipt["version"]) == generation)
                    val manifest = receipt["manifest"].asJsonObject
                    require(LobbyDialogueDistributionBinding.generation(manifest) == generation)
                    val fileRecords = receipt["files"].asJsonObject
                    require(fileRecords.keySet() == LobbyDialogueDistributionBinding.files.keys)
                    val paths =
                        LobbyDialogueDistributionBinding.files.mapValues { (_, file) ->
                            root.resolve(file)
                        }
                    val pins =
                        paths.mapValues { (role, path) ->
                            val record = fileRecords[role].asJsonObject
                            require(
                                record.keySet() ==
                                    (setOf("file", "sha256", "size") +
                                        if (role == "addon") setOf("sha1") else emptySet())
                            )
                            require(
                                string(record["file"]) ==
                                    LobbyDialogueDistributionBinding.files.getValue(role)
                            )
                            require(sha256.matches(string(record["sha256"])))
                            require(integer(record["size"]) >= 0)
                            if (role == "addon") require(sha1.matches(string(record["sha1"])))
                            requireSafe(path)
                            val pin =
                                jsonLimit(role)?.let {
                                    LobbyDialogueFileSnapshot.pin(bounded(path, it))
                                } ?: LobbyDialogueDistributionBinding.pin(path, role == "addon")
                            require(
                                LobbyDialogueDistributionBinding.canonical(
                                    JsonParser.parseString(
                                        LobbyDialogueDistributionBinding.json(pin)
                                            .toString(Charsets.UTF_8)
                                    )
                                ) ==
                                    LobbyDialogueDistributionBinding.canonical(
                                        record.deepCopy().also { it.remove("file") }
                                    )
                            )
                            pin
                        }
                    val profileBytes = bounded(paths.getValue("profile"), 1048576)
                    val hostBytes = bounded(paths.getValue("hostCatalog"), 1048576)
                    require(LobbyDialogueFileSnapshot.pin(profileBytes) == pins.getValue("profile"))
                    require(
                        LobbyDialogueFileSnapshot.pin(hostBytes) == pins.getValue("hostCatalog")
                    )
                    val profile =
                        LobbyDialogueTrustedProfile.parse(
                            profileBytes,
                            hostBytes,
                            string(receipt["id"]),
                            generation,
                        )
                    strictJson(bounded(paths.getValue("scene"), 16 * 1024 * 1024))
                    Prepared(root, receiptBytes, paths, pins, generation, profile)
                } catch (_: Exception) {
                    Denied(phase)
                }
            }
        }
    }

    private const val DIRECTORY = "GROUNDS_DIALOGUE_DISTRIBUTION"
    private const val DIGEST = "GROUNDS_DIALOGUE_BINDING_SHA256"
    private val sha256 = Regex("[0-9a-f]{64}")
    private val sha1 = Regex("[0-9a-f]{40}")

    fun prepare(environment: Map<String, String>): Preparation = Prepared.anchored(environment)

    fun verify(
        environment: Map<String, String>,
        selectedRawScene: Path?,
        composition: LobbySceneComposition?,
        transformedScene: Boolean,
    ): Decision =
        when (val prepared = prepare(environment)) {
            Unconfigured -> Unconfigured
            is Denied -> prepared
            is Prepared -> verify(prepared, selectedRawScene, composition, transformedScene)
        }

    fun verify(
        prepared: Prepared,
        selectedRawScene: Path?,
        composition: LobbySceneComposition?,
        transformedScene: Boolean,
    ): Decision {
        var phase = "runtime_provenance_invalid"
        return try {
            val paths = prepared.paths
            val pins = prepared.pins
            val generation = prepared.generation
            val receiptBytes = prepared.receiptBytes()
            val binding = prepared.root.resolve("dialogue/binding.json")
            phase = "runtime_provenance_invalid"
            val executable = paths.getValue("executable")
            val actual = requireNotNull(composition)
            val serviceTypes = actual.registrations.values.flatMap { it.requireQualifiedServices() }
            val runtimeTypes =
                listOf(
                    LobbyDialogueRuntimeVerification::class.java,
                    GroundsAssetCatalog::class.java,
                    GroundsPluginLobbyProvider::class.java,
                    NpcAddonBinding::class.java,
                ) + serviceTypes
            LobbyDialogueExecutableIdentity.requireOpened(
                executable,
                pins.getValue("executable"),
                runtimeTypes,
            )
            listOf(
                    "gg/grounds/minestom/lobby/npc-content-id.txt",
                    "gg/grounds/minestom/lobby/npc-addon-sha1.txt",
                    "gg/grounds/resourcepacks/catalog/catalog-version.txt",
                )
                .forEach { resource -> requireResource(executable, resource) }
            LobbyDialogueDistributionBinding.verifyCatalog(
                executable,
                paths.getValue("catalog"),
                GroundsAssetCatalog.catalog.version,
            )
            NpcAddonBinding.load().requireMatches(pins.getValue("addon").getValue("sha1") as String)
            phase = "runtime_scene_invalid"
            val source = requireNotNull(selectedRawScene)
            requireSafe(source)
            val selectedPin = LobbyDialogueFileSnapshot.pin(bounded(source, 16 * 1024 * 1024))
            require(selectedPin == pins.getValue("scene"))
            val content = requireRuntimeSnapshot(actual, selectedPin, transformedScene)
            phase = "runtime_content_invalid"
            require(content.sourcePin == pins.getValue("serverContent"))
            GroundsNpcModelSource.requireContentBinding(content.contentId)
            content.verifyAddon(paths.getValue("addon"))
            phase = "runtime_capabilities_invalid"
            val inputs = pins.filterKeys { it !in setOf("profile", "hostCatalog") }
            val regenerated =
                LobbyDialogueDistributionBinding.manifest(
                    actual,
                    GroundsPluginLobbyProvider(),
                    content,
                    inputs,
                )
            require(regenerated.generation == generation)
            require(regenerated.profile.profileBytes.contentEquals(prepared.profile.profileBytes))
            require(
                regenerated.profile.hostCatalogBytes.contentEquals(
                    prepared.profile.hostCatalogBytes
                )
            )
            require(regenerated.receipt(inputs).contentEquals(receiptBytes))
            phase = "inputs_changed"
            prepared.requireUnchanged()
            require(LobbyDialogueFileSnapshot.pin(bounded(source, 16 * 1024 * 1024)) == selectedPin)
            LobbyDialogueExecutableIdentity.requireOpened(
                executable,
                pins.getValue("executable"),
                runtimeTypes,
            )
            require(bounded(binding, 1048576).contentEquals(receiptBytes))
            Admitted(generation)
        } catch (_: Exception) {
            Denied(phase)
        }
    }

    /** Checks actual frozen renderer provenance; this alone does not grant admission. */
    internal fun requireRuntimeSnapshot(
        composition: LobbySceneComposition,
        selectedPin: Map<String, Any>,
        transformedScene: Boolean,
    ): GroundsNpcModelSource.LoadedContent {
        require(!transformedScene)
        val snapshot =
            requireNotNull(GroundsNpcModelSource.rendererSnapshot(composition.request.renderers))
        require(
            snapshot.sourcePin == selectedPin &&
                snapshot.effectiveScene === composition.request.scene
        )
        return requireNotNull(snapshot.content)
    }

    internal fun requireSafe(path: Path) {
        require(path.isAbsolute && path.normalize() == path)
        var part = path.root
        for (component in path) {
            part = part.resolve(component)
            require(!Files.isSymbolicLink(part))
        }
    }

    private fun jsonLimit(role: String): Int? =
        when (role) {
            "profile",
            "hostCatalog" -> 1048576
            "scene" -> 16 * 1024 * 1024
            else -> null
        }

    private fun bounded(path: Path, limit: Int): ByteArray {
        requireSafe(path)
        return LobbyDialogueFileSnapshot.file(path, limit)
    }

    internal fun requireResource(executable: Path, name: String) {
        val resource =
            requireNotNull(
                LobbyDialogueRuntimeVerification::class.java.classLoader.getResource(name)
            )
        val connection = resource.openConnection() as java.net.JarURLConnection
        require(Files.isSameFile(Path.of(connection.jarFileURL.toURI()), executable))
        val expected =
            ZipFile(executable.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filter { it.name == name }.toList()
                require(entries.size == 1 && entries.single().size in 0..1024)
                zip.getInputStream(entries.single()).use { it.readNBytes(1025) }
            }
        val loaded = resource.openStream().use { it.readNBytes(1025) }
        require(expected.size <= 1024 && loaded.contentEquals(expected))
    }

    private fun string(value: JsonElement): String {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun integer(value: JsonElement): Long {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        require(value.toString().matches(Regex("0|[1-9][0-9]*")))
        return value.asLong.also { require(it.toString() == value.toString()) }
    }

    /**
     * Gson's tree parser overwrites duplicates; read tokens explicitly before any schema access.
     */
    internal fun strictJson(bytes: ByteArray): JsonElement {
        require(bytes.size <= 16 * 1024 * 1024)
        val text =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        val reader = JsonReader(StringReader(text))
        reader.strictness = Strictness.STRICT
        var nodes = 0
        fun read(depth: Int): JsonElement {
            require(depth <= 64 && ++nodes <= 100000)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT ->
                    JsonObject().also { obj ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(!obj.has(key))
                            obj.add(key, read(depth + 1))
                        }
                        reader.endObject()
                    }
                JsonToken.BEGIN_ARRAY ->
                    JsonArray().also { array ->
                        reader.beginArray()
                        while (reader.hasNext()) array.add(read(depth + 1))
                        reader.endArray()
                    }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> {
                    reader.nextNull()
                    JsonNull.INSTANCE
                }
                else -> throw IllegalArgumentException("Invalid JSON token")
            }
        }
        return reader.use { read(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) } }
    }
}
