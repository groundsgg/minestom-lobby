package gg.grounds.minestom.lobby.scene

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

/** Startup-only, bounded snapshots of the E1 Story sidecars. No profile or admission authority. */
internal sealed interface LobbyAuthoredDialogueInputs {
    data object Absent : LobbyAuthoredDialogueInputs

    class Present internal constructor(private val root: Path, sources: Map<String, ByteArray>) :
        LobbyAuthoredDialogueInputs {
        private val bytes = sources.mapValues { it.value.clone() }

        fun dialogues() = copy("dialogues/", false)

        fun texts() = copy("texts/", true)

        fun bindings() = bytes.getValue("grounds/dialogue.json").clone()

        fun host() = bytes.getValue("context/host-catalog.json").clone()

        private fun copy(prefix: String, locale: Boolean) =
            bytes
                .filterKeys { it.startsWith(prefix) }
                .mapKeys { (path, _) ->
                    path.removePrefix(prefix).let { if (locale) it.removeSuffix(".json") else it }
                }
                .mapValues { it.value.clone() }

        fun requireUnchanged() {
            val current = read(root) as? Present ?: error("Story inventory disappeared")
            require(
                current.bytes.keys == bytes.keys &&
                    bytes.all { (path, value) -> current.bytes.getValue(path).contentEquals(value) }
            ) {
                "Story inputs changed"
            }
        }
    }

    companion object {
        private const val MIB = 1048576
        private val reserved = setOf("dialogues", "texts", "grounds", "context")

        fun read(root: Path): LobbyAuthoredDialogueInputs {
            LobbyDialogueRuntimeVerification.requireSafe(root)
            require(Files.isDirectory(root, NOFOLLOW_LINKS))
            val paths = linkedMapOf<String, Path>()
            var visited = 0
            fun visit(path: Path, depth: Int) {
                require(++visited <= 10000 && depth <= 64) { "Story discovery exceeds limit" }
                val relative = root.relativize(path).joinToString("/")
                val parts = relative.split('/')
                val normalized = parts.map { it.trimEnd(' ', '.').lowercase(Locale.ROOT) }
                val first = normalized.first()
                if (first !in reserved) return
                require(parts.first() == first) { "Noncanonical reserved Story root: $relative" }
                LobbyDialogueRuntimeVerification.requireSafe(path)
                val fixed = normalized.take(2).joinToString("/")
                val special = fixed in setOf("grounds/dialogue.json", "context/host-catalog.json")
                if (special)
                    require(relative == fixed && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        "Noncanonical Story context: $relative"
                    }
                val library = first in setOf("dialogues", "texts")
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    if (library || depth == 1)
                        Files.newDirectoryStream(path).use { stream ->
                            stream.forEach { visit(it, depth + 1) }
                        }
                } else if (library || special) {
                    require(parts.size > 1 && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        "Story source must be regular: $relative"
                    }
                    require(!library || relative.endsWith(".json")) {
                        "Story source must be JSON: $relative"
                    }
                    require(paths.put(relative, path) == null)
                }
            }
            Files.newDirectoryStream(root).use { stream -> stream.forEach { visit(it, 1) } }
            if (paths.isEmpty()) return Absent
            val definitions = paths.keys.filter { it.startsWith("dialogues/") }
            val texts = paths.keys.filter { it.startsWith("texts/") }
            require(
                definitions.size in 1..1024 &&
                    texts.size in 1..64 &&
                    "grounds/dialogue.json" in paths &&
                    "context/host-catalog.json" in paths
            ) {
                "Incomplete Story sidecars"
            }
            requirePortable(paths.keys)
            texts.forEach {
                val name = it.removePrefix("texts/").removeSuffix(".json")
                require('/' !in name) { "Nested Story locale: $it" }
                val locale = Locale.Builder().setLanguageTag(name).build()
                require(
                    '/' !in name && locale.language.isNotEmpty() && locale.toLanguageTag() == name
                ) {
                    "Noncanonical Story locale: $it"
                }
            }
            require(
                paths
                    .filterKeys { it != "grounds/dialogue.json" }
                    .values
                    .sumOf { Files.size(it) } <= 16L * MIB
            ) {
                "Story catalogue exceeds 16 MiB"
            }
            var total = 0L
            val bytes =
                paths.toSortedMap().mapValues { (name, path) ->
                    val data =
                        LobbyDialogueFileSnapshot.file(
                            path,
                            if (name.startsWith("texts/")) 4 * MIB else MIB,
                        )
                    if (name != "grounds/dialogue.json") total += data.size
                    require(total <= 16L * MIB) { "Story catalogue exceeds 16 MiB" }
                    try {
                        LobbyDialogueRuntimeVerification.strictJson(data)
                    } catch (failure: Exception) {
                        throw IllegalArgumentException("Invalid Story JSON: $name", failure)
                    }
                    data
                }
            return Present(root, bytes)
        }

        private fun requirePortable(paths: Set<String>) {
            val aliases = hashSetOf<String>()
            val ancestors = hashMapOf<String, String>()
            val devices = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\\$|CONOUT\\$)")
            for (path in paths) {
                require(
                    path.toByteArray().size <= 1024 &&
                        Normalizer.isNormalized(path, Normalizer.Form.NFC)
                )
                val parts = path.split('/')
                require(
                    parts.all { part ->
                        part.isNotEmpty() &&
                            !part.endsWith('.') &&
                            !part.endsWith(' ') &&
                            part.toByteArray().size <= 255 &&
                            part.none { it.code < 32 || it in "<>:\\|?*\"" } &&
                            !devices.matches(part.substringBefore('.').trimEnd(' '))
                    }
                ) {
                    "Nonportable Story path: $path"
                }
                require(aliases.add(path.lowercase(Locale.ROOT))) { "Ambiguous Story path: $path" }
                for (i in 1 until parts.size) {
                    val parent = parts.take(i).joinToString("/")
                    val old = ancestors.putIfAbsent(parent.lowercase(Locale.ROOT), parent)
                    require(old == null || old == parent) { "Ambiguous Story directory: $path" }
                }
            }
            require(aliases.none { it in ancestors })
        }
    }
}
