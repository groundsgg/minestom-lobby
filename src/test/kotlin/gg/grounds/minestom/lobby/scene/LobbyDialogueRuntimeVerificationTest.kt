package gg.grounds.minestom.lobby.scene

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LobbyDialogueRuntimeVerificationTest {
    @Test
    fun `absent configuration never evaluates runtime inputs`() {
        assertEquals(
            LobbyDialogueRuntimeVerification.Unconfigured,
            LobbyDialogueRuntimeVerification.verify(emptyMap(), null, null, false),
        )
    }

    @Test
    fun `partial blank malformed and relative configurations deny without runtime inputs`() {
        listOf(
                mapOf("GROUNDS_DIALOGUE_DISTRIBUTION" to "/missing"),
                mapOf("GROUNDS_DIALOGUE_BINDING_SHA256" to "a".repeat(64)),
                mapOf(
                    "GROUNDS_DIALOGUE_DISTRIBUTION" to "",
                    "GROUNDS_DIALOGUE_BINDING_SHA256" to "",
                ),
                mapOf(
                    "GROUNDS_DIALOGUE_DISTRIBUTION" to "relative",
                    "GROUNDS_DIALOGUE_BINDING_SHA256" to "a".repeat(64),
                ),
            )
            .forEach { environment ->
                assertEquals(
                    LobbyDialogueRuntimeVerification.Denied("configuration_invalid"),
                    LobbyDialogueRuntimeVerification.verify(environment, null, null, false),
                )
            }
    }

    @org.junit.jupiter.api.io.TempDir lateinit var directory: java.nio.file.Path

    private fun distribution(): Map<String, String> {
        java.nio.file.Files.createDirectories(directory.resolve("dialogue/context"))
        val manifest = com.google.gson.JsonObject()
        val generation = LobbyDialogueDistributionBinding.generation(manifest)
        val (profile, host) = dialogueProfileFixture(generation)
        val pins =
            LobbyDialogueDistributionBinding.files.mapValues { (role, file) ->
                val value =
                    when (role) {
                        "profile" -> profile
                        "hostCatalog" -> host
                        "scene" -> "{}".toByteArray()
                        else -> "test-$role".toByteArray()
                    }
                val path = java.nio.file.Files.write(directory.resolve(file), value)
                LobbyDialogueDistributionBinding.pin(path, role == "addon")
            }
        val bytes =
            LobbyDialogueDistributionBinding.json(
                mapOf(
                    "schemaVersion" to 1,
                    "id" to "grounds:lobby-guide",
                    "version" to generation,
                    "generation" to generation,
                    "manifest" to manifest,
                    "files" to
                        pins.mapValues { (role, pin) ->
                            pin + ("file" to LobbyDialogueDistributionBinding.files.getValue(role))
                        },
                )
            )
        java.nio.file.Files.write(directory.resolve("dialogue/binding.json"), bytes)
        return mapOf(
            "GROUNDS_DIALOGUE_DISTRIBUTION" to directory.toString(),
            "GROUNDS_DIALOGUE_BINDING_SHA256" to LobbyDialogueDistributionBinding.hash(bytes),
        )
    }

    @Test
    fun `self consistent distribution cannot admit a foreign executable classpath`() {
        assertEquals(
            LobbyDialogueRuntimeVerification.Denied("runtime_provenance_invalid"),
            LobbyDialogueRuntimeVerification.verify(distribution(), null, null, false),
        )
    }

    @Test
    fun `every pinned artifact mutation denies before consulting runtime`() {
        LobbyDialogueDistributionBinding.files.forEach { (_, file) ->
            val environment = distribution()
            java.nio.file.Files.writeString(directory.resolve(file), "tampered")
            assertEquals(
                LobbyDialogueRuntimeVerification.Denied("distribution_invalid"),
                LobbyDialogueRuntimeVerification.verify(environment, null, null, false),
                file,
            )
        }
    }

    @Test
    fun `binding requires external anchor and exact schema types paths and pins`() {
        val cases =
            listOf<(com.google.gson.JsonObject) -> Unit>(
                { it.addProperty("schemaVersion", "1") },
                { it.addProperty("schemaVersion", 1.0) },
                { it.addProperty("unexpected", true) },
                { it.getAsJsonObject("files").remove("addon") },
                {
                    it.getAsJsonObject("files")
                        .getAsJsonObject("scene")
                        .addProperty("file", "../scene.json")
                },
                { it.getAsJsonObject("files").getAsJsonObject("scene").addProperty("size", "2") },
                {
                    it.getAsJsonObject("files")
                        .getAsJsonObject("scene")
                        .addProperty("sha256", "A".repeat(64))
                },
                { it.addProperty("generation", "a".repeat(64)) },
            )
        cases.forEach { mutate ->
            val environment = distribution().toMutableMap()
            val binding = directory.resolve("dialogue/binding.json")
            val receipt =
                com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(binding))
                    .asJsonObject
            mutate(receipt)
            val bytes = LobbyDialogueDistributionBinding.json(receipt)
            java.nio.file.Files.write(binding, bytes)
            environment["GROUNDS_DIALOGUE_BINDING_SHA256"] =
                LobbyDialogueDistributionBinding.hash(bytes)
            assertEquals(
                LobbyDialogueRuntimeVerification.Denied("distribution_invalid"),
                LobbyDialogueRuntimeVerification.verify(environment, null, null, false),
            )
        }
        val environment = distribution()
        java.nio.file.Files.writeString(directory.resolve("dialogue/binding.json"), "{}")
        assertEquals(
            LobbyDialogueRuntimeVerification.Denied("distribution_invalid"),
            LobbyDialogueRuntimeVerification.verify(environment, null, null, false),
        )
    }

    @Test
    fun `strict JSON rejects duplicate nested keys malformed UTF8 trailing values and excessive nesting`() {
        val invalid =
            listOf(
                "{\"x\":1,\"x\":2}".toByteArray(),
                "{\"x\":{\"y\":1,\"y\":2}}".toByteArray(),
                byteArrayOf(123, 34, -61, 40, 34, 58, 49, 125),
                "{} {}".toByteArray(),
                "{unquoted:1}".toByteArray(),
                "{\"x\":NaN}".toByteArray(),
                ("[".repeat(66) + "0" + "]".repeat(66)).toByteArray(),
                ByteArray(16 * 1024 * 1024 + 1) { 32 },
            )
        invalid.forEach { bytes ->
            assertThrows(Exception::class.java) {
                LobbyDialogueRuntimeVerification.strictJson(bytes)
            }
        }
        assertEquals(
            "{\"a\":[1,true,null,\"text\"]}",
            LobbyDialogueDistributionBinding.canonical(
                LobbyDialogueRuntimeVerification.strictJson(
                    "{\"a\":[1,true,null,\"text\"]}".toByteArray()
                )
            ),
        )
    }

    @Test
    fun `symlink parent and fixed file substitutions are rejected`() {
        val environment = distribution()
        val target = directory.resolve("dialogue/profile.json")
        val saved = java.nio.file.Files.move(target, directory.resolve("saved.json"))
        java.nio.file.Files.createSymbolicLink(target, saved)
        assertEquals(
            LobbyDialogueRuntimeVerification.Denied("distribution_invalid"),
            LobbyDialogueRuntimeVerification.verify(environment, null, null, false),
        )
        java.nio.file.Files.delete(target)
        java.nio.file.Files.move(saved, target)
        val linked = directory.resolveSibling(directory.fileName.toString() + "-link")
        try {
            java.nio.file.Files.createSymbolicLink(linked, directory)
            assertEquals(
                LobbyDialogueRuntimeVerification.Denied("distribution_invalid"),
                LobbyDialogueRuntimeVerification.verify(
                    environment + ("GROUNDS_DIALOGUE_DISTRIBUTION" to linked.toString()),
                    null,
                    null,
                    false,
                ),
            )
        } finally {
            java.nio.file.Files.deleteIfExists(linked)
        }
    }

    @Test
    fun `foreign plain classpath resources cannot qualify as embedded jar binding`() {
        assertThrows(Exception::class.java) {
            LobbyDialogueRuntimeVerification.requireResource(
                directory.resolve("foreign.jar"),
                "gg/grounds/resourcepacks/catalog/catalog-version.txt",
            )
        }
    }
}
