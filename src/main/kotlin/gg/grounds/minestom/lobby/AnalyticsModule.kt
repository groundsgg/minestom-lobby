package gg.grounds.minestom.lobby

import gg.grounds.analytics.minestom.GroundsAnalytics
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext

/**
 * Publishes what players do on this lobby to the analytics pipeline: arrivals, deaths, chat volume
 * and commands, stamped with `minigame_id=lobby`.
 *
 * Until this existed only the Velocity proxy wrote events, so everything that happens *on a server*
 * — who was on a lobby and when, and therefore Prism's Presence view — was missing. The library is
 * not a runtime provider (nothing discovers it), which is why this is a module with a call site
 * rather than a line in [selectedRuntimeProviderIds].
 *
 * `install` runs once Minestom is initialised, so the global event handler exists; the library
 * hangs everything on one child node, and [stop] removes it.
 */
internal class AnalyticsModule(private val minigameId: String) : GroundsModule {
    private var analytics: GroundsAnalytics? = null

    override val id: String = "grounds.lobby.analytics"

    override fun install(ctx: GroundsServerContext) {
        analytics = GroundsAnalytics.install(minigameId)
    }

    override fun stop() {
        analytics?.uninstall()
        analytics = null
    }
}

/**
 * `ANALYTICS_ENABLED=false` is the library's own switch (its default is on); reading it here as
 * well keeps the module out of the server entirely, so a test or a dev run makes no NATS
 * connection.
 */
internal fun analyticsEnabled(env: Map<String, String> = System.getenv()): Boolean =
    !env["ANALYTICS_ENABLED"].orEmpty().trim().equals("false", ignoreCase = true)
