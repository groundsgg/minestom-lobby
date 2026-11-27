package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import gg.grounds.dialogue.core.validation.HostCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BundledDialogueContentTest {
    private val projects = listOf("lobby-guide", "stage-mechanic")

    @Test
    fun `both standard projects preserve original pins and bind trusted runtime generations`() {
        for (project in projects) {
            val content = read(project, entries(project))
            val validated = content.validated("runtime-generation")
            val golden =
                javaClass.getResource("/dialogue-migration/$project.sha256")!!.readText().trim()
            assertEquals(
                golden,
                validated.contentPin().sha256,
                "$project validated dialogue pin; review authored changes before updating dialogue-migration/$project.sha256",
            )
            assertEquals("runtime-generation", validated.catalog().generation())
            assertEquals(host(project), content.validated("test").catalog())
            val facade =
                if (project == "lobby-guide") LobbyGuideContent.validated("facade")
                else StageDialogueContent.validated("facade")
            assertEquals(golden, facade.contentPin().sha256)
        }
    }

    @Test
    fun `every participating source path and byte is packaged without author documents`() {
        for (project in projects) {
            val root = Path.of("dialogue-content", project)
            val sources =
                Files.walk(root).use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) }
                        .filter {
                            val name = root.relativize(it).toString().replace('\\', '/')
                            (name.startsWith("dialogues/") || name.startsWith("texts/")) &&
                                name.endsWith(".json") || name == "context/host-catalog.json"
                        }
                        .toList()
                        .associate {
                            root.relativize(it).toString().replace('\\', '/') to it.readBytes()
                        }
                }
            val packaged = entries(project)
            assertEquals(sources.keys, packaged.keys, project)
            sources.forEach { (path, bytes) ->
                assertArrayEquals(bytes, packaged[path], "$project/$path")
            }
        }
    }

    @Test
    fun `invalid SDK content cannot activate either project`() {
        for (project in projects) {
            rejects(project, "TEXT_MISSING") { files ->
                val de =
                    JsonParser.parseString(files.getValue("texts/de.json").decodeToString())
                        .asJsonObject
                de.remove("welcome")
                files["texts/de.json"] = de.toString().toByteArray()
            }
            rejects(project, "TEXT_MISSING") { it.remove("texts/de.json") }
            rejects(project, "JSON_DUPLICATE_KEY") {
                it["texts/en.json"] = "{\"welcome\":\"one\",\"welcome\":\"two\"}".toByteArray()
            }
            rejects(project, "DIALOGUE_DUPLICATE") { files ->
                files["dialogues/duplicate.json"] = files.getValue(graphPath(project))
            }
            rejects(project, "REFERENCE_UNKNOWN") { files ->
                val graph = graph(files, project)
                graph
                    .getAsJsonObject("nodes")
                    .getAsJsonObject("start")
                    .getAsJsonArray("choices")[0]
                    .asJsonObject
                    .addProperty("to", "missing")
                files[graphPath(project)] = graph.toString().toByteArray()
            }
            rejects(project, "TEXT_ARGUMENT_MISSING") { files ->
                files["texts/de.json"] =
                    files.getValue("texts/de.json").decodeToString().let {
                        val json = JsonParser.parseString(it).asJsonObject
                        json.addProperty("welcome", "Hello {player}")
                        json.toString().toByteArray()
                    }
            }
            rejects(project, "TEXT_ARGUMENT_MISSING") { files ->
                val json =
                    JsonParser.parseString(files.getValue("texts/en.json").decodeToString())
                        .asJsonObject
                json.addProperty("speaker.guide", "{player}")
                files["texts/en.json"] = json.toString().toByteArray()
            }
        }
    }

    @Test
    fun `authored host declarations cannot grant authority or change trusted fields`() {
        for (project in projects) {
            for (field in
                listOf(
                    "speakers",
                    "conditions",
                    "actions",
                    "gestures",
                    "capabilities",
                    "durableOutcomes",
                )) {
                rejects(project, "HOST_CONTRACT_MISMATCH") { files ->
                    val json =
                        JsonParser.parseString(
                                files.getValue("context/host-catalog.json").decodeToString()
                            )
                            .asJsonObject
                    when (field) {
                        "conditions",
                        "actions" ->
                            json
                                .getAsJsonObject(field)
                                .add(
                                    "forged",
                                    JsonParser.parseString("{\"arguments\":{},\"durable\":false}"),
                                )
                        "durableOutcomes" -> json.addProperty(field, true)
                        else -> json.getAsJsonArray(field).add("forged")
                    }
                    if (field == "actions") {
                        val graph = graph(files, project)
                        graph
                            .getAsJsonObject("nodes")
                            .add(
                                "forged_action",
                                JsonParser.parseString(
                                    "{\"type\":\"action\",\"action\":\"forged\",\"onSuccess\":\"end\",\"onRejected\":\"end\",\"onUnavailable\":\"end\",\"onUncertain\":\"end\"}"
                                ),
                            )
                        files[graphPath(project)] = graph.toString().toByteArray()
                    }
                    files["context/host-catalog.json"] = json.toString().toByteArray()
                }
            }
        }
    }

    @Test
    fun `provider signatures and durability are part of trusted authority`() {
        for (field in listOf("arguments", "durable")) {
            rejects("lobby-guide", "HOST_CONTRACT_MISMATCH") { files ->
                val json =
                    JsonParser.parseString(
                            files.getValue("context/host-catalog.json").decodeToString()
                        )
                        .asJsonObject
                val provider =
                    json
                        .getAsJsonObject("actions")
                        .getAsJsonObject(LobbyGuideContent.NAVIGATOR_ACTION)
                if (field == "durable") provider.addProperty("durable", true)
                else
                    provider
                        .getAsJsonObject("arguments")
                        .add(
                            "invented",
                            JsonParser.parseString(
                                "{\"type\":\"text\",\"required\":true,\"nullable\":false}"
                            ),
                        )
                files["context/host-catalog.json"] = json.toString().toByteArray()
            }
        }
    }

    @Test
    fun `duplicate choice IDs unknown providers and unexpected project members fail admission`() {
        for (project in projects) {
            rejects(project, "CHOICE_DUPLICATE") { files ->
                val json = graph(files, project)
                val choices =
                    json.getAsJsonObject("nodes").getAsJsonObject("start").getAsJsonArray("choices")
                choices[1]
                    .asJsonObject
                    .addProperty("id", choices[0].asJsonObject.get("id").asString)
                files[graphPath(project)] = json.toString().toByteArray()
            }
            rejects(project, "PROVIDER_UNKNOWN") { files ->
                val json = graph(files, project)
                json
                    .getAsJsonObject("nodes")
                    .add(
                        "invented",
                        JsonParser.parseString(
                            "{\"type\":\"action\",\"action\":\"invented\",\"onSuccess\":\"end\",\"onRejected\":\"end\",\"onUnavailable\":\"end\",\"onUncertain\":\"end\"}"
                        ),
                    )
                files[graphPath(project)] = json.toString().toByteArray()
            }
            rejects(project, "PROJECT_MEMBERSHIP") { files ->
                val json = graph(files, project)
                json.addProperty("id", "unexpected:dialogue")
                files[graphPath(project)] = json.toString().toByteArray()
            }
            rejects(project, "CATALOGUE_LIMIT") { files ->
                files.keys.filter { it.startsWith("dialogues/") }.forEach { files.remove(it) }
            }
        }
    }

    @Test
    fun `aggregate uncompressed bytes are bounded before JSON parsing`() {
        rejects("lobby-guide", "CONTENT_LIMIT") { files ->
            for (tag in listOf("fr", "es", "it", "pt", "nl")) files["texts/$tag.json"] =
                ByteArray(4_194_304) { 32 }
        }
    }

    @Test
    fun `archive admission rejects unsafe duplicate nested and oversized entries`() {
        val project = "lobby-guide"
        for (path in
            listOf(
                "../escape.json",
                "texts/../en.json",
                "texts\\en.json",
                "/absolute.json",
                "texts/nested/en.json",
                "unexpected.json",
            )) {
            rejects(project, path) { it[path] = "{}".toByteArray() }
        }
        rejects(project, "LOCALE_INVALID") { it["texts/de_DE.json"] = "{}".toByteArray() }
        rejects(project, "CONTENT_LIMIT") { it[graphPath(project)] = ByteArray(1_048_577) }
        rejects(project, "context/host-catalog.json") { it.remove("context/host-catalog.json") }
        val duplicated =
            archive(entries(project) + ("texts/xx.json" to "{}".toByteArray())).let { bytes ->
                // ZIP writer disallows duplicates; equal-length rename in local and central
                // records.
                bytes
                    .toString(Charsets.ISO_8859_1)
                    .replace("texts/xx.json", "texts/en.json")
                    .toByteArray(Charsets.ISO_8859_1)
            }
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                BundledDialogueContent.read(
                    project,
                    id(project),
                    host(project),
                    ByteArrayInputStream(duplicated),
                )
            }
        assertTrue(failure.message!!.contains("DUPLICATE_ENTRY"), failure.message)
    }

    @Test
    fun `discovered additional locale and escaped braces need no Kotlin locale list`() {
        for (project in projects) {
            val files = entries(project)
            val graph = graph(files, project)
            val supported = graph.getAsJsonObject("locales").getAsJsonArray("supported")
            val existing = supported.map { it.asString }
            val additionalTag = Locale.getISOLanguages().first { it !in existing }
            val expected = existing + additionalTag
            supported.add(additionalTag)
            files[graphPath(project)] = graph.toString().toByteArray()
            val translated =
                JsonParser.parseString(files.getValue("texts/en.json").decodeToString())
                    .asJsonObject
            translated.addProperty("welcome", "Literal {{player}}")
            files["texts/$additionalTag.json"] = translated.toString().toByteArray()
            val content = read(project, files)
            assertTrue(content.texts.locales().keys.containsAll(expected))
            assertEquals(expected, content.validated("new").definition().locales().supported())
        }
    }

    private fun rejects(
        project: String,
        diagnostic: String,
        edit: (MutableMap<String, ByteArray>) -> Unit,
    ) {
        val files = entries(project)
        edit(files)
        val failure = assertThrows(IllegalArgumentException::class.java) { read(project, files) }
        assertTrue(failure.message!!.contains(project), failure.message)
        assertTrue(failure.message!!.contains(diagnostic), failure.message)
    }

    private fun read(project: String, files: Map<String, ByteArray>) =
        BundledDialogueContent.read(
            project,
            id(project),
            host(project),
            ByteArrayInputStream(archive(files)),
        )

    private fun id(project: String) =
        if (project == "lobby-guide") LobbyGuideContent.DIALOGUE_ID
        else StageDialogueContent.DIALOGUE_ID

    private fun host(project: String) =
        HostCatalog(
            "test",
            setOf("guide"),
            emptyMap(),
            if (project == "lobby-guide")
                mapOf(
                    LobbyGuideContent.NAVIGATOR_ACTION to
                        HostCatalog.ProviderSpec(emptyMap(), false),
                    LobbyGuideContent.DUELS_ACTION to HostCatalog.ProviderSpec(emptyMap(), false),
                )
            else emptyMap(),
            emptySet(),
            emptySet(),
            false,
        )

    private fun graphPath(project: String) =
        "dialogues/${if (project == "lobby-guide") "guide" else "mechanic"}.json"

    private fun graph(files: Map<String, ByteArray>, project: String) =
        JsonParser.parseString(files.getValue(graphPath(project)).decodeToString()).asJsonObject

    private fun entries(project: String): MutableMap<String, ByteArray> {
        val resource =
            javaClass.getResourceAsStream(
                "/gg/grounds/minestom/lobby/dialogue-content/$project.zip"
            )
        assertNotNull(resource, "Missing packaged content: $project")
        return ZipInputStream(resource!!).use { zip ->
            buildMap {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) put(entry.name, zip.readBytes())
                    }
                }
                .toMutableMap()
        }
    }

    private fun archive(files: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZipOutputStream(output).use { zip ->
                    files.forEach { (path, bytes) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
            .toByteArray()
}
