package gg.grounds.minestom.lobby

import gg.grounds.i18n.LocaleResolver
import gg.grounds.i18n.MessageKey
import gg.grounds.i18n.Translations
import gg.grounds.minigame.minestom.SidebarLocale
import java.util.Locale
import net.minestom.server.entity.Player

internal enum class LobbyMessage(override val id: String) : MessageKey {
    NOT_READY("lobby.not-ready"),
    SHUTTING_DOWN("lobby.shutting-down"),
    PLAYERS_ONLY("command.players-only"),
    DEMO_ENTER("demo.enter"),
    DEMO_MODE("demo.mode"),
    DEMO_MODE_DENIED("demo.mode-denied"),
    MODE_CREATIVE("mode.creative"),
    MODE_ADVENTURE("mode.adventure"),
}

internal object LobbyMessages {
    val text =
        Translations.forBundle(
            "gg.grounds.lobby.runtime_messages",
            javaClass.classLoader,
            localeResolver =
                LocaleResolver { audience ->
                    (audience as? Player)?.let(SidebarLocale::localeOf) ?: Locale.ENGLISH
                },
        )
}
