package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.*
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GroundsNpcModelSourceTest {
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
        index: String =
            """{"schemaVersion":1,"models":[{"asset":"test:bot","resource":"$path"}]}""",
        model: String = json,
    ) =
        GroundsNpcModelSource.load(assets) { resource ->
            when (resource) {
                "/gg/grounds/resourcepacks/npc/index.json" ->
                    ByteArrayInputStream(index.toByteArray())
                path -> ByteArrayInputStream(model.toByteArray())
                else -> null
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
        val entry = """{"asset":"test:bot","resource":"$path"}"""
        assertThrows(IllegalArgumentException::class.java) {
            load("""{"schemaVersion":1,"models":[$entry,$entry]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            load(
                """{"schemaVersion":1,"models":[{"asset":"test:bot","resource":"/gg/grounds/resourcepacks/npc/../secret.json"}]}"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            load(model = json.replace("test:bot", "test:other"))
        }
    }
}
