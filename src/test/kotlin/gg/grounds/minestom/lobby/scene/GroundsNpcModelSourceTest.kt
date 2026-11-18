package gg.grounds.minestom.lobby.scene

import com.google.gson.JsonParser
import gg.grounds.scene.format.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GroundsNpcModelSourceTest {
    @TempDir lateinit var temp: Path
    private val key = AssetKey("test:bot")
    private val path = "/gg/grounds/resourcepacks/npc/test.json"
    private val json =
        """{"schemaVersion":1,"asset":"test:bot","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[],"parts":[{"bone":null,"model":"grounds:npc_parts/test/cube","center":[0,1,0],"pivot":[0,1,0],"rotation":[0,0,0]}],"animations":{},"sounds":{}}"""
    private val assets =
        AssetCatalog(
            CatalogId("test:catalog"),
            "1",
            CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
            mapOf(
                key to
                    AssetDefinition(
                        key,
                        AssetKind.NPC_BODY,
                        emptySet(),
                        LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                        emptyMap(),
                    )
            ),
        )

    private fun load(
        model: String = json,
        duplicate: Boolean = false,
        unsafe: Boolean = false,
        target: String = "26.3",
    ): Map<AssetKey, gg.grounds.npc.core.model.NpcModel> {
        val root = Files.createTempDirectory(temp, "content-")
        fun hash(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        val resource = if (unsafe) "../secret.json" else "model.json"
        Files.writeString(root.resolve("model.json"), model)
        val entry =
            """{"id":"test:bot","resource":"$resource","sha256":"${hash(model.toByteArray())}"}"""
        val payload =
            """{"bundleSchema":1,"clientFiles":{},"compilerVersion":"test","models":[$entry${if(duplicate) ",$entry" else ""}],"soundEvents":{},"target":"$target"}"""
        val contract = JsonParser.parseString(payload).asJsonObject
        contract.addProperty("contentId", hash(payload.toByteArray()))
        Files.writeString(root.resolve("bundle.json"), contract.toString())
        return GroundsNpcModelSource.load(assets, root)
    }

    @Test
    fun `no content is neutral but explicitly configured missing invalid or wrong target content fails`() {
        assertTrue(GroundsNpcModelSource.load(assets, null).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { load(target = "26.2") }
        assertThrows(Exception::class.java) {
            GroundsNpcModelSource.load(assets, temp.resolve("missing.zip"))
        }
        Files.writeString(temp.resolve("bad.zip"), "broken")
        assertThrows(Exception::class.java) {
            GroundsNpcModelSource.load(assets, temp.resolve("bad.zip"))
        }
    }

    @Test
    fun `Grounds refuses item meshes outside its compiled NPC part namespace`() {
        assertThrows(IllegalArgumentException::class.java) {
            load(model = json.replace("grounds:npc_parts/test/cube", "minecraft:stick"))
        }
    }

    @Test
    fun `host loads numeric definitions through neutral core and preserves exact catalogue contract`() {
        assertEquals("test:bot", load().getValue(key).id)
        assertThrows(IllegalArgumentException::class.java) {
            load(model = json.replace("[1,2,1]", "[2,2,1]"))
        }
    }

    @Test
    fun `unsafe paths duplicate models and mismatched asset identities fail loading`() {
        assertThrows(Exception::class.java) { load(duplicate = true) }
        assertThrows(Exception::class.java) { load(unsafe = true) }
        assertThrows(Exception::class.java) { load(model = json.replace("test:bot", "test:other")) }
    }
}
