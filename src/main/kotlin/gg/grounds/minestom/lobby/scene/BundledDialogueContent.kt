package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.json.DialogueCatalogLoader
import gg.grounds.dialogue.json.DialogueJsonCodec
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** One immutable packaged project, validated against host-owned authority before admission. */
internal class BundledDialogueContent
private constructor(private val project: String, private val snapshot: ValidatedDialogue) {
    val texts = snapshot.texts()

    fun validated(generation: String): ValidatedDialogue =
        validated(snapshot.catalog().withGeneration(generation))

    fun validated(host: HostCatalog): ValidatedDialogue {
        val result =
            DialogueValidator()
                .validate(
                    snapshot.definition(),
                    texts,
                    host,
                    limits,
                )
        return requireNotNull(result.validated()) {
            "$project: runtime validation failed: ${result.problems()}"
        }
    }

    companion object {
        private val limits = ValidationLimits.defaults()
        private const val PACKAGE_BYTES = 16_777_216
        private const val MAX_DEFINITIONS = 1024
        private const val MAX_ENTRIES = 4096
        private val portableSegment = Regex("[a-zA-Z0-9_.-]+")

        fun load(project: String, dialogueId: String, host: HostCatalog): BundledDialogueContent {
            val path = "/gg/grounds/minestom/lobby/dialogue-content/$project.zip"
            val input =
                requireNotNull(BundledDialogueContent::class.java.getResourceAsStream(path)) {
                    "$project: missing packaged resource $path"
                }
            return read(project, dialogueId, host, input)
        }

        fun read(
            project: String,
            dialogueId: String,
            host: HostCatalog,
            input: InputStream,
        ): BundledDialogueContent {
            try {
                val definitions = linkedMapOf<String, ByteArray>()
                val texts = linkedMapOf<String, ByteArray>()
                var contextBytes: ByteArray? = null
                val names = hashSetOf<String>()
                var total = 0
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val path = entry.name
                        require(names.size < MAX_ENTRIES) {
                            "$project: ARCHIVE_ENTRY_LIMIT at $path"
                        }
                        require(names.add(path)) { "$project: DUPLICATE_ENTRY at $path" }
                        val segments = path.removeSuffix("/").split('/')
                        require(
                            segments.all { it != "." && it != ".." && portableSegment.matches(it) }
                        ) {
                            "$project: UNSAFE_ENTRY at $path"
                        }
                        if (entry.isDirectory) {
                            require(
                                segments.first() in setOf("dialogues", "texts", "context") &&
                                    (segments.size == 1 || segments.first() == "dialogues")
                            ) {
                                "$project: UNSUPPORTED_ENTRY at $path"
                            }
                            require(zip.read() == -1) { "$project: DIRECTORY_PAYLOAD at $path" }
                            continue
                        }
                        val maximum =
                            when {
                                path == "context/host-catalog.json" -> limits.textPackageBytes
                                segments.first() == "dialogues" &&
                                    segments.size >= 2 &&
                                    path.endsWith(".json") -> {
                                    require(definitions.size < MAX_DEFINITIONS) {
                                        "$project: CATALOGUE_LIMIT at $path"
                                    }
                                    limits.definitionBytes
                                }
                                segments.first() == "texts" &&
                                    segments.size == 2 &&
                                    path.endsWith(".json") -> {
                                    require(texts.size < limits.locales) {
                                        "$project: LOCALE_LIMIT at $path"
                                    }
                                    limits.textFileBytes
                                }
                                else ->
                                    throw IllegalArgumentException(
                                        "$project: UNSUPPORTED_ENTRY at $path"
                                    )
                            }
                        val bytes = zip.readNBytes(minOf(maximum, PACKAGE_BYTES - total) + 1)
                        require(
                            bytes.size <= maximum && total.toLong() + bytes.size <= PACKAGE_BYTES
                        ) {
                            "$project: CONTENT_LIMIT at $path"
                        }
                        total += bytes.size
                        when {
                            path == "context/host-catalog.json" -> contextBytes = bytes
                            segments.first() == "dialogues" -> definitions[path] = bytes
                            else -> {
                                val tag = segments.last().removeSuffix(".json")
                                // The SDK is the authority for canonical locale tags and text
                                // shapes.
                                val localeProblems =
                                    gg.grounds.dialogue.core.text
                                        .TextCatalog(mapOf(tag to emptyMap()))
                                        .structuralProblems(limits)
                                require(localeProblems.isEmpty()) {
                                    "$project: $path: $localeProblems"
                                }
                                texts[tag] = bytes
                            }
                        }
                    }
                }
                val context =
                    requireNotNull(contextBytes) { "$project: missing context/host-catalog.json" }
                val prepared = DialogueJsonCodec().prepareContext(texts, context)
                val authorContext =
                    requireNotNull(prepared.context()) { "$project: ${prepared.problems()}" }
                require(authorContext.catalog().withGeneration(host.generation()) == host) {
                    "$project: HOST_CONTRACT_MISMATCH at context/host-catalog.json (generation excluded)"
                }
                val result =
                    DialogueCatalogLoader()
                        .reload(DialogueCatalogLoader.ContentSources(definitions, texts, context))
                val candidate =
                    requireNotNull(result.candidate()) { "$project: ${result.problems()}" }
                require(candidate.entries().keys == setOf(dialogueId)) {
                    "$project: PROJECT_MEMBERSHIP at dialogues (expected=$dialogueId, actual=${candidate.entries().keys})"
                }
                val parsed = candidate.entries().getValue(dialogueId)
                val trusted =
                    DialogueValidator().validate(parsed.definition(), parsed.texts(), host, limits)
                val validated =
                    requireNotNull(trusted.validated()) { "$project: ${trusted.problems()}" }
                preflight(project, validated)
                return BundledDialogueContent(project, validated)
            } catch (failure: IOException) {
                throw IllegalArgumentException(
                    "$project: failed to read packaged dialogue ZIP",
                    failure,
                )
            }
        }

        private fun preflight(project: String, validated: ValidatedDialogue) {
            val definition = validated.definition()
            val texts = validated.texts()
            val defaultTag = definition.locales().defaultTag()
            val resolver = TextResolver.catalogue(texts, defaultTag)
            for (tag in definition.locales().supported()) {
                val locale = LocaleContext(tag, 0, defaultTag)
                val keys = linkedSetOf<String>()
                for (message in definition.nodes().values.filterIsInstance<Message>()) {
                    keys.add(message.text())
                    message.choices().forEach { keys.add(it.text) }
                    val speaker = "speaker.${message.speaker()}"
                    if (
                        linkedSetOf(tag, Locale.forLanguageTag(tag).language, defaultTag).any {
                            texts.locales()[it]?.containsKey(speaker) == true
                        }
                    )
                        keys.add(speaker)
                }
                for (key in keys) {
                    try {
                        resolver
                            .resolve(TextResolver.TextRequest(key, emptyMap(), locale))
                            .toCompletableFuture()
                            .join()
                    } catch (failure: RuntimeException) {
                        val cause = failure.cause ?: failure
                        throw IllegalArgumentException(
                            "$project: texts/$tag.json key=$key: ${cause.message}",
                            cause,
                        )
                    }
                }
            }
        }

        private fun HostCatalog.withGeneration(generation: String) =
            HostCatalog(
                generation,
                speakers(),
                conditions(),
                actions(),
                gestures(),
                capabilities(),
                durableOutcomes(),
            )
    }
}
