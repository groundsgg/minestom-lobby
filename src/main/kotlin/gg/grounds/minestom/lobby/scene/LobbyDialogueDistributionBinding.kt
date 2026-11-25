package gg.grounds.minestom.lobby.scene

import com.google.gson.*
import gg.grounds.lobby.GroundsPluginLobbyProvider
import gg.grounds.scene.format.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

/** Private build identity. Paths, sessions and output digests never enter the generation. */
internal object LobbyDialogueDistributionBinding {
    val files =
        mapOf(
            "executable" to "minestom-lobby.jar",
            "serverContent" to "server-content.zip",
            "addon" to "npc-addon.zip",
            "catalog" to "catalog.jar",
            "scene" to "dialogue/scene.json",
            "profile" to "dialogue/profile.json",
            "hostCatalog" to "dialogue/context/host-catalog.json",
        )
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun canonical(value: JsonElement): String =
        when {
            value.isJsonObject ->
                value.asJsonObject
                    .entrySet()
                    .sortedBy { it.key }
                    .joinToString(",", "{", "}") { (key, item) ->
                        gson.toJson(key) + ":" + canonical(item)
                    }
            value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
            else -> gson.toJson(value)
        }

    fun generation(manifest: JsonElement): String =
        hash(canonical(manifest).toByteArray(Charsets.UTF_8))

    fun hash(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun json(value: Any): ByteArray = canonical(gson.toJsonTree(value)).toByteArray(Charsets.UTF_8)

    fun pin(path: Path, addon: Boolean = false): Map<String, Any> {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile) { "Binding input must be a regular non-symlink file" }
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = if (addon) MessageDigest.getInstance("SHA-1") else null
        var size = 0L
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                sha256.update(buffer, 0, count)
                sha1?.update(buffer, 0, count)
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            after.isRegularFile &&
                size == before.size() &&
                size == after.size() &&
                before.fileKey() == after.fileKey() &&
                before.lastModifiedTime() == after.lastModifiedTime()
        ) {
            "Binding input changed while hashing"
        }
        return sortedMapOf<String, Any>(
                "sha256" to HexFormat.of().formatHex(sha256.digest()),
                "size" to size,
            )
            .apply { if (sha1 != null) put("sha1", HexFormat.of().formatHex(sha1.digest())) }
            .toMap()
    }

    fun manifest(
        composition: LobbySceneComposition,
        provider: GroundsPluginLobbyProvider,
        content: GroundsNpcModelSource.LoadedContent,
        pins: Map<String, Map<String, Any>>,
    ): BoundProfile {
        require(pins.keys == setOf("executable", "serverContent", "addon", "catalog", "scene"))
        val request = composition.request
        val prepared =
            PreparedLobbyScene(
                request.scene,
                request.assets,
                request.actions,
                request.identity,
                request.renderers,
            )
        val bindings = prepared.dialogueBindings(composition.dialogues?.metadataContract)
        val dialogue = bindings.map { it.dialogue }.distinct().singleOrNull()
        require(dialogue != null) { "Export requires exactly one supported dialogue" }
        require(composition.registrations.keys == setOf(dialogue)) {
            "Dialogue owner registration mismatch"
        }
        val registration = composition.registrations.getValue(dialogue)
        registration.requireExportReady()
        val roles = sortedMapOf<String, String>()
        bindings.forEach { binding ->
            val npc =
                request.scene.elements.filterIsInstance<Npc>().single { it.id == binding.elementId }
            require(npc.body in content.models) {
                "Dialogue body must be backed by loaded private model"
            }
            val prior = roles.put(binding.speaker, npc.body.value)
            require(prior == null || prior == npc.body.value) { "Conflicting dialogue role bodies" }
        }
        require(roles.keys == registration.speakers) {
            "Dialogue roles differ from installed registration"
        }
        fun reference(id: CatalogId, version: String) =
            mapOf("id" to id.value, "version" to version)
        val providers =
            registration.actionProviders
                .sortedBy { it.key }
                .associate {
                    it.key to
                        mapOf("arguments" to it.spec.arguments(), "durable" to it.spec.durable())
                }
        val manifest =
            gson.toJsonTree(
                mapOf(
                    "schemaVersion" to 1,
                    "serviceVariant" to provider.id,
                    "provider" to mapOf("id" to provider.id, "version" to provider.version),
                    "dialogueId" to dialogue,
                    "roles" to roles,
                    "actionProviders" to providers,
                    "assetCatalog" to reference(request.assets.id, request.assets.version),
                    "actionCatalogs" to
                        listOf(reference(request.actions.id, request.actions.version)),
                    "contentId" to content.contentId,
                    "files" to pins,
                )
            )
        require(canonical(manifest).all { it.code <= 127 }) {
            "Binding manifest strings must be ASCII"
        }
        val generation = generation(manifest)
        val id = registration.contract.profileId
        val profile =
            LobbyDialogueProfile(
                id,
                generation,
                generation,
                request.actionRegistry,
                request.assets,
                listOf(request.actions),
                request.renderers,
                mapOf(
                    gg.grounds.lobby.scene.LobbySceneCatalogs.START_DIALOGUE to
                        LobbyDialogueStartAction(
                            gg.grounds.lobby.scene.LobbySceneCatalogs.DIALOGUE_ID,
                            gg.grounds.lobby.scene.LobbySceneCatalogs.SPEAKER,
                        )
                ),
                roles.mapValues { LobbyDialogueRole(AssetKey(it.value), emptyMap()) },
                registration,
            )
        return BoundProfile(id, generation, manifest, profile)
    }

    class BoundProfile(
        val id: String,
        val generation: String,
        val manifest: JsonElement,
        val profile: LobbyDialogueProfile,
    ) {
        fun receipt(inputs: Map<String, Map<String, Any>>): ByteArray {
            val pins =
                inputs +
                    mapOf(
                        "profile" to
                            mapOf(
                                "sha256" to hash(profile.profileBytes),
                                "size" to profile.profileBytes.size.toLong(),
                            ),
                        "hostCatalog" to
                            mapOf(
                                "sha256" to hash(profile.hostCatalogBytes),
                                "size" to profile.hostCatalogBytes.size.toLong(),
                            ),
                    )
            return json(
                    mapOf(
                        "schemaVersion" to 1,
                        "id" to id,
                        "version" to generation,
                        "generation" to generation,
                        "manifest" to manifest,
                        "files" to
                            pins.mapValues { (role, pin) -> pin + ("file" to files.getValue(role)) },
                    )
                )
                .also { require(it.size <= 1048576) { "Binding exceeds 1 MiB" } }
        }
    }

    /** Compare exact neutral class/identity bytes, with standalone archive bounds. */
    fun verifyCatalog(executable: Path, catalog: Path, version: String) {
        require(Files.size(catalog) <= 16L * 1024 * 1024) { "Catalog archive exceeds 16 MiB" }
        fun selected(name: String) =
            name.startsWith("gg/grounds/resourcepacks/catalog/") ||
                name == "META-INF/resourcepacks-catalog.kotlin_module"
        fun entries(path: Path, standalone: Boolean): Map<String, String> =
            ZipFile(path.toFile()).use { archive ->
                val entries = archive.entries().asSequence().toList()
                if (standalone) {
                    require(
                        entries.size <= 1024 &&
                            entries.map { it.name }.distinct().size == entries.size
                    ) {
                        "Catalog contains duplicate or excessive entries"
                    }
                    require(
                        entries.all { it.size in 0..(64L * 1024 * 1024) } &&
                            entries.sumOf { it.size } <= 64L * 1024 * 1024
                    ) {
                        "Catalog exceeds uncompressed limit"
                    }
                    require(
                        entries
                            .filterNot { it.isDirectory }
                            .all {
                                (it.name.startsWith("gg/grounds/resourcepacks/catalog/") &&
                                    it.name.endsWith(".class")) ||
                                    it.name ==
                                        "gg/grounds/resourcepacks/catalog/catalog-version.txt" ||
                                    it.name in
                                        setOf(
                                            "META-INF/MANIFEST.MF",
                                            "META-INF/LICENSE",
                                            "META-INF/resourcepacks-catalog.kotlin_module",
                                        )
                            }
                    ) {
                        "Catalog contains non-neutral payload"
                    }
                }
                val chosen = entries.filter { !it.isDirectory && selected(it.name) }
                require(
                    chosen.any { it.name == "gg/grounds/resourcepacks/catalog/catalog-version.txt" }
                ) {
                    "Catalog identity metadata missing"
                }
                require(
                    chosen.size in 1..1024 && chosen.map { it.name }.distinct().size == chosen.size
                )
                require(
                    chosen.all { it.size in 0..(64L * 1024 * 1024) } &&
                        chosen.sumOf { it.size } <= 64L * 1024 * 1024
                )
                chosen.associate { entry ->
                    val bytes =
                        archive.getInputStream(entry).use { it.readNBytes(64 * 1024 * 1024 + 1) }
                    require(bytes.size <= 64 * 1024 * 1024 && bytes.size.toLong() == entry.size)
                    if (entry.name.endsWith("/catalog-version.txt"))
                        require(bytes.toString(Charsets.UTF_8).trim() == version) {
                            "Loaded catalog version differs from archive"
                        }
                    entry.name to hash(bytes)
                }
            }
        require(entries(executable, false) == entries(catalog, true)) {
            "Executable catalog differs from standalone catalog"
        }
    }
}
