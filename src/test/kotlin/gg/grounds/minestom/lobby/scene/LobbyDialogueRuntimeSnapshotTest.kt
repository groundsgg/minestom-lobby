package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.minestom.lobby.LoadedLobbyMap
import gg.grounds.minestom.lobby.LobbyMapSource
import gg.grounds.modules.core.DefaultServiceRegistry
import gg.grounds.scene.format.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import net.minestom.server.MinecraftServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyDialogueRuntimeSnapshotTest {
    @TempDir lateinit var directory: Path
    private val assets =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
            mapOf(
                guideKey to
                    AssetDefinition(
                        guideKey,
                        AssetKind.NPC_BODY,
                        emptySet(),
                        unitBounds,
                        emptyMap(),
                    ),
                markerKey to
                    AssetDefinition(markerKey, AssetKind.PROP, emptySet(), unitBounds, emptyMap()),
            ),
        )

    private fun content(): Path {
        val model =
            """{"schemaVersion":1,"asset":"grounds:editor/guide","bounds":{"center":[0.5,0.5,0.5],"size":[1,1,1]},"bones":[],"parts":[{"bone":null,"model":"grounds:npc_parts/test/cube","center":[0.5,0.5,0.5],"pivot":[0,0,0],"rotation":[0,0,0]}],"animations":{},"sounds":{}}"""
        val payload =
            """{"bundleSchema":1,"clientFiles":{},"compilerVersion":"test","models":[{"id":"grounds:editor/guide","resource":"model.json","sha256":"${LobbyDialogueDistributionBinding.hash(model.toByteArray())}"}],"soundEvents":{},"target":"26.3"}"""
        val contract = com.google.gson.JsonParser.parseString(payload).asJsonObject
        contract.addProperty(
            "contentId",
            LobbyDialogueDistributionBinding.hash(payload.toByteArray()),
        )
        val path = directory.resolve("content.zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            mapOf("bundle.json" to contract.toString(), "model.json" to model).forEach {
                (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `truncated central directory with intact local payloads cannot load`() {
        val path = content()
        val bytes = Files.readAllBytes(path)
        val centralOffset =
            java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .getInt(bytes.size - 6)
        Files.write(path, bytes.copyOf(centralOffset))
        assertThrows(Exception::class.java) {
            gg.grounds.npc.core.io.content.NpcContentLoader.load(path)
        }
        assertThrows(Exception::class.java) { GroundsNpcModelSource.load(assets, path) }
    }

    @Test
    fun `central metadata and membership must agree with streamed local records`() {
        listOf(10, 16, 20, 24, 46).forEach { field ->
            val path = content()
            val bytes = Files.readAllBytes(path)
            val centralOffset =
                java.nio.ByteBuffer.wrap(bytes)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .getInt(bytes.size - 6)
            bytes[centralOffset + field] = (bytes[centralOffset + field].toInt() xor 1).toByte()
            Files.write(path, bytes)
            assertThrows(Exception::class.java) {
                gg.grounds.npc.core.io.content.NpcContentLoader.load(path)
            }
            assertThrows(
                Exception::class.java,
                { GroundsNpcModelSource.load(assets, path) },
                "directory field=$field",
            )
        }
    }

    private fun prepared(
        models: Map<AssetKey, gg.grounds.npc.core.model.NpcModel>
    ): PreparedLobbyScene {
        val authored = lobbySceneFixture(assets, Vec3(0.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0))
        val npc = authored.elements.filterIsInstance<Npc>().single()
        val scene =
            authored.with(
                catalogs =
                    authored.catalogs.copy(
                        actions =
                            CatalogReference(
                                LobbySceneCatalogs.DIALOGUE.id,
                                LobbySceneCatalogs.DIALOGUE.version,
                            )
                    ),
                elements =
                    listOf(
                        npc.with(
                            bindings =
                                listOf(
                                    TriggerBinding(
                                        SceneTrigger.RIGHT_CLICK,
                                        listOf(HandCondition(SceneHand.MAIN)),
                                        0,
                                        0,
                                        listOf(
                                            ApplicationAction(
                                                LobbySceneCatalogs.START_DIALOGUE,
                                                mapOf(
                                                    LobbySceneCatalogs.DIALOGUE_ID to
                                                        StringArgument(
                                                            StageDialogueContent.DIALOGUE_ID
                                                        ),
                                                    LobbySceneCatalogs.SPEAKER to
                                                        StringArgument("guide"),
                                                ),
                                            )
                                        ),
                                    )
                                )
                        )
                    ),
            )
        Files.write(
            directory.resolve("scene.json"),
            (SceneJson.encode(scene) as SceneEncodeResult.Success).bytes,
        )
        return requireNotNull(
            LobbySceneLoader(assets, LobbySceneRenderers(assets, models))
                .load(LoadedLobbyMap(directory, LobbyMapSource.Local(directory)))
        )
    }

    @Test
    fun `real renderer snapshot survives loader and dialogue labels without reconstructing content`() {
        val zip = content()
        val models = GroundsNpcModelSource.load(assets, zip)
        val selected = prepared(models)
        RendererFixture().use { fixture ->
            val composition =
                LobbySceneComposition.create(selected, fixture.instance, DefaultServiceRegistry())
            try {
                val pin = LobbyDialogueDistributionBinding.pin(directory.resolve("scene.json"))
                val captured =
                    LobbyDialogueRuntimeVerification.requireRuntimeSnapshot(composition, pin, false)
                assertSame(models, captured.models)
                assertEquals(LobbyDialogueDistributionBinding.pin(zip), captured.sourcePin)
                Files.writeString(zip, "replaced after renderer creation")
                assertNotEquals(LobbyDialogueDistributionBinding.pin(zip), captured.sourcePin)
                assertEquals("grounds:editor/guide", captured.models.getValue(guideKey).id)
                assertThrows(IllegalArgumentException::class.java) {
                    LobbyDialogueRuntimeVerification.requireRuntimeSnapshot(composition, pin, true)
                }
                Files.writeString(directory.resolve("scene.json"), "changed")
                assertThrows(IllegalArgumentException::class.java) {
                    LobbyDialogueRuntimeVerification.requireRuntimeSnapshot(
                        composition,
                        LobbyDialogueDistributionBinding.pin(directory.resolve("scene.json")),
                        false,
                    )
                }
            } finally {
                composition.dialogues?.closeAsync()?.toCompletableFuture()?.join()
            }
        }
    }

    @Test
    fun `copied models and changed effective scene cannot inherit snapshot provenance`() {
        val models = GroundsNpcModelSource.load(assets, content())
        assertNotNull(GroundsNpcModelSource.snapshot(models))
        assertNull(GroundsNpcModelSource.snapshot(models.toMap()))
        val selected = prepared(models)
        val changed =
            selected.copy(scene = selected.scene.with(elements = selected.scene.elements.toList()))
        RendererFixture().use { fixture ->
            listOf(changed, prepared(models.toMap())).forEach { prepared ->
                val composition =
                    LobbySceneComposition.create(
                        prepared,
                        fixture.instance,
                        DefaultServiceRegistry(),
                    )
                try {
                    assertThrows(IllegalArgumentException::class.java) {
                        LobbyDialogueRuntimeVerification.requireRuntimeSnapshot(
                            composition,
                            LobbyDialogueDistributionBinding.pin(directory.resolve("scene.json")),
                            false,
                        )
                    }
                } finally {
                    composition.dialogues?.closeAsync()?.toCompletableFuture()?.join()
                }
            }
        }
    }

    @Test
    fun `ordinary archive loading needs no writable temporary directory`() {
        val fixtureId = gg.grounds.npc.core.io.content.NpcContentLoader.load(content()).contentId
        val bindings = directory.resolve("fixture-classpath")
        val resource = bindings.resolve("gg/grounds/minestom/lobby/npc-content-id.txt")
        Files.createDirectories(resource.parent)
        Files.writeString(resource, fixtureId + "\n")
        // This unconfigured-startup fixture owns both its content and matching legacy binding.
        // Real private build resources later on the classpath must not bind synthetic test models.
        val classpath =
            bindings.toString() +
                java.io.File.pathSeparator +
                requireNotNull(System.getProperty("lobby.test.runtimeClasspath"))
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.io.tmpdir=" + directory.resolve("missing-temp"),
                    "-cp",
                    classpath,
                    LobbyContentNoTempProbe::class.java.name,
                    directory.toString(),
                )
                .redirectErrorStream(true)
                .start()
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("CONTENT_LOADED_NO_TEMP"), output)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal fun probeNoTemp() {
        System.setProperty("grounds.npc.content", content().toString())
        val models = GroundsNpcModelSource.load(assets)
        check(models.getValue(guideKey).id == guideKey.value)
        println("CONTENT_LOADED_NO_TEMP")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun initialize() {
            System.setProperty("minestom.shutdown-on-signal", "false")
            MinecraftServer.init()
        }
    }
}

internal object LobbyContentNoTempProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        LobbyDialogueRuntimeSnapshotTest()
            .apply { directory = Path.of(arguments.single()) }
            .probeNoTemp()
    }
}
