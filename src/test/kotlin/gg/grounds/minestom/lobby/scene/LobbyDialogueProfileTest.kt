package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LobbyDialogueProfileTest {
    @TempDir lateinit var directory: Path
    private val body = AssetKey("test:body")
    private val wave = LocalId("wave")
    private val start = LobbySceneCatalogs.START_DIALOGUE
    private val dialogue = LocalId("dialogue")
    private val speaker = LocalId("speaker")
    private val assets =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
            mapOf(body to AssetDefinition(body, AssetKind.NPC_BODY, setOf(wave), null, emptyMap())),
        )
    private val parameters =
        listOf(dialogue, speaker).associateWith {
            ActionParameter(it, ActionParameterType.STRING, true, null, NoConstraints)
        }

    private fun catalog(parameters: Map<LocalId, ActionParameter>) =
        ActionCatalog(
            CatalogId("test:actions"),
            "1",
            mapOf(start to ActionDefinition(start, "Start", "Start dialogue", parameters)),
        )

    private fun profile(
        installed: Boolean = true,
        gestures: Map<String, LocalId> = emptyMap(),
        role: String = "guide",
        renderer: Boolean = true,
        registration: LobbyDialogueHostRegistration = guideRegistration(),
        roleBody: AssetKey = body,
        parameters: Map<LocalId, ActionParameter> = this.parameters,
    ) =
        LobbyDialogueProfile(
            "test:profile",
            "1",
            "test-generation",
            SceneActionRegistry {
                if (installed)
                    SceneActionHandler {
                        error("Profile preparation must not execute start actions")
                    }
                else null
            },
            assets,
            listOf(catalog(parameters)),
            object : AnimatedSceneRenderers {
                override fun rendererFor(asset: AssetKey, kind: AssetKind) =
                    if (renderer)
                        SceneAssetRendererFactory { error("Export must not create renderers") }
                    else null

                override fun supportsAnimation(asset: AssetKey, animation: LocalId?) = true
            },
            mapOf(start to LobbyDialogueStartAction(dialogue, speaker)),
            mapOf(role to LobbyDialogueRole(roleBody, gestures)),
            registration,
        )

    private fun guideRegistration() =
        LobbyDialogueHostRegistration.create(
            LobbyGuideContent.DIALOGUE_ID,
            object : gg.grounds.lobby.LobbyNavigatorService {
                override fun open(
                    player: net.minestom.server.entity.Player
                ): java.util.concurrent.CompletionStage<Boolean> =
                    fail("Profile preparation must not open navigator")
            },
            object : gg.grounds.lobby.LobbyQueueService {
                override fun toggleQueue(
                    player: net.minestom.server.entity.Player,
                    modeId: String,
                ): java.util.concurrent.CompletionStage<Boolean> =
                    fail("Profile preparation must not join a queue")

                override fun openDuels(
                    player: net.minestom.server.entity.Player
                ): java.util.concurrent.CompletionStage<Boolean> =
                    fail("Profile preparation must not open duels")
            },
        )

    @Test
    fun `shared lobby registry and catalog export installed dialogue action`() {
        val registry =
            LobbySceneActions(
                null,
                object : ScenePlayerPolicy {
                    override fun isEligible(player: net.minestom.server.entity.Player) = true

                    override fun hasPermission(
                        player: net.minestom.server.entity.Player,
                        permission: String,
                    ) = false
                },
                dialogueStarter =
                    LobbySceneDialogueStarter { _, _, _ ->
                        error("Profile preparation must not execute start actions")
                    },
            )
        val profile =
            LobbyDialogueProfile(
                "test:profile",
                "1",
                "test-generation",
                registry,
                assets,
                listOf(LobbySceneCatalogs.DIALOGUE),
                object : AnimatedSceneRenderers {
                    override fun rendererFor(asset: AssetKey, kind: AssetKind) =
                        SceneAssetRendererFactory {
                            error("No renderer creation during export")
                        }

                    override fun supportsAnimation(asset: AssetKey, animation: LocalId?) = true
                },
                mapOf(
                    start to
                        LobbyDialogueStartAction(
                            LobbySceneCatalogs.DIALOGUE_ID,
                            LobbySceneCatalogs.SPEAKER,
                        )
                ),
                mapOf("guide" to LobbyDialogueRole(body, emptyMap())),
                guideRegistration(),
            )
        val root = JsonParser.parseString(String(profile.profileBytes, Charsets.UTF_8)).asJsonObject
        assertEquals("4", root["actionCatalogs"].asJsonArray[0].asJsonObject["version"].asString)
        assertTrue(root["startActions"].asJsonObject.has("grounds:lobby/start_dialogue"))
    }

    @Test
    fun `export is deterministic and embedded host bytes are identical`() {
        val profile = profile()
        assertArrayEquals(profile.profileBytes, profile().profileBytes)
        val root = JsonParser.parseString(String(profile.profileBytes, Charsets.UTF_8)).asJsonObject
        assertArrayEquals(
            profile.hostCatalogBytes,
            root["hostCatalogJson"].asString.toByteArray(Charsets.UTF_8),
        )
        assertEquals("test:assets", root["assetCatalog"].asJsonObject["id"].asString)
        assertEquals(
            setOf(
                "schemaVersion",
                "id",
                "version",
                "hostCatalogJson",
                "assetCatalog",
                "actionCatalogs",
                "startActions",
                "npcAssets",
            ),
            root.keySet(),
        )
        assertTrue(
            root["npcAssets"]
                .asJsonObject["test:body"]
                .asJsonObject["gestures"]
                .asJsonObject
                .isEmpty
        )
        assertEquals(64, profile.profileSha256.length)
        val copy = profile.profileBytes
        copy[0] = 0
        assertNotEquals(0, profile.profileBytes[0].toInt())
        profile.writeTo(directory)
        assertArrayEquals(
            profile.profileBytes,
            Files.readAllBytes(directory.resolve("profile.json")),
        )
        assertArrayEquals(
            profile.hostCatalogBytes,
            Files.readAllBytes(directory.resolve("context/host-catalog.json")),
        )
    }

    @Test
    fun `missing installed handler rejects export`() {
        assertThrows(IllegalArgumentException::class.java) { profile(installed = false) }
    }

    @Test
    fun `renderer support does not advertise dialogue gestures`() {
        val host =
            JsonParser.parseString(String(profile().hostCatalogBytes, Charsets.UTF_8)).asJsonObject
        assertTrue(host["gestures"].asJsonArray.isEmpty)
        assertTrue(host["capabilities"].asJsonArray.isEmpty)
        assertThrows(IllegalArgumentException::class.java) {
            profile(gestures = mapOf("test:wave" to wave))
        }
    }

    @Test
    fun `unsupported speaker roles and missing renderers reject preparation`() {
        assertThrows(IllegalArgumentException::class.java) { profile(role = "merchant") }
        assertThrows(IllegalArgumentException::class.java) { profile(renderer = false) }
        assertThrows(IllegalArgumentException::class.java) {
            profile(roleBody = AssetKey("test:absent"))
        }
    }

    @Test
    fun `guide profile exports both real menu provider schemas`() {
        val host =
            JsonParser.parseString(String(profile().hostCatalogBytes, Charsets.UTF_8)).asJsonObject
        assertEquals(
            setOf("grounds:lobby/open_navigator", "grounds:lobby/open_duel_menu"),
            host["actions"].asJsonObject.keySet(),
        )
        host["actions"].asJsonObject.entrySet().forEach { (_, spec) ->
            assertEquals(JsonParser.parseString("{\"arguments\":{},\"durable\":false}"), spec)
        }
        assertTrue(host["conditions"].asJsonObject.isEmpty)
        assertFalse(host["durableOutcomes"].asBoolean)
    }

    @Test
    fun `missing guide installation rejects profile while stage advertises no providers`() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                registration = LobbyDialogueHostRegistration.create(LobbyGuideContent.DIALOGUE_ID)
            )
        }
        val stage =
            profile(
                registration =
                    LobbyDialogueHostRegistration.create(StageDialogueContent.DIALOGUE_ID)
            )
        val host =
            JsonParser.parseString(String(stage.hostCatalogBytes, Charsets.UTF_8)).asJsonObject
        assertTrue(host["actions"].asJsonObject.isEmpty)
        assertTrue(host["conditions"].asJsonObject.isEmpty)
        assertTrue(host["gestures"].asJsonArray.isEmpty)
        assertTrue(host["capabilities"].asJsonArray.isEmpty)
    }

    @Test
    fun `start action schema must be exactly two required strings`() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(parameters = parameters - speaker)
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                parameters =
                    parameters + (speaker to parameters.getValue(speaker).copy(required = false))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                parameters =
                    parameters +
                        (speaker to
                            parameters.getValue(speaker).copy(type = ActionParameterType.LONG))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                parameters =
                    parameters +
                        (speaker to
                            parameters
                                .getValue(speaker)
                                .copy(defaultValue = StringArgument("guide")))
            )
        }
    }
}
