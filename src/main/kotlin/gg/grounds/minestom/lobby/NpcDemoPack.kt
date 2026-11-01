package gg.grounds.minestom.lobby

import java.net.URI
import java.util.UUID
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player

/** Separate pack ID and additive request preserve the proxy's existing Grounds base pack. */
internal class NpcDemoPack(url: String, sha1: String) {
    val id: UUID
    val request: ResourcePackRequest

    init {
        val uri = URI.create(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) {
            "NPC addon needs an HTTPS URL"
        }
        require(sha1.matches(Regex("[0-9a-f]{40}"))) { "NPC addon needs an exact SHA1" }
        id = UUID.nameUUIDFromBytes("grounds:npc-demo:$sha1".toByteArray(Charsets.UTF_8))
        request =
            ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(id, uri, sha1))
                .replace(false)
                .required(true)
                .prompt(
                    Component.text("Modelle, Animationen und Sounds für die Grounds NPC-Testlobby")
                )
                .build()
    }

    fun offer(player: Player) {
        player.sendResourcePacks(request)
    }

    companion object {
        fun configured(environment: Map<String, String> = System.getenv()) =
            NpcDemoPack(
                requireNotNull(environment["GROUNDS_NPC_ADDON_URL"]) {
                    "NPC demo addon URL missing"
                },
                requireNotNull(environment["GROUNDS_NPC_ADDON_SHA1"]) {
                    "NPC demo addon digest missing"
                },
            )
    }
}
