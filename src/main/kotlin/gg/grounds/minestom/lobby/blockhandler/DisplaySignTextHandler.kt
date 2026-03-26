package gg.grounds.minestom.lobby.blockhandler

import net.kyori.adventure.key.Key
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.tag.Tag

/**
 * This handler is responsible for telling Minestom what NBT Tags to send.
 * Otherwise, signs have nothing written on it.
 */
// This SignBlockHandler is an object and not a class because it does not store any defining properties.
// In case it needs any variables from the main method, this object should be turned into a class.
object DisplaySignTextHandler : BlockHandler {

    /**
     * This key identifies this block handler.
     * Block handlers can be saved to disk, so this key is required to load this BlockHandler again.
     * This key is not responsible for identifying for what blocks this handler is valid.
     */
    override fun getKey() = Key.key("minestom:sign")

    /**
     * This defines the NBT tags which are sent to the client.
     * They are taken from https://minecraft.wiki/w/Sign#Block_data and ONLY count from >= 1.20.
     */
    override fun getBlockEntityTags(): Collection<Tag<*>> {
        return listOf(
            Tag.NBT("front_text"),
            Tag.NBT("back_text"),
            Tag.Boolean("is_waxed")
        )
    }
}