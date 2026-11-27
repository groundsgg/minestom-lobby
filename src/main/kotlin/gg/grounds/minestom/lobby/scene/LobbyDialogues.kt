package gg.grounds.minestom.lobby.scene

import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.minestom.*
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.input.InputProfile
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.UiLabelSource
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneHand
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.minestom.SceneActionContext
import gg.grounds.scene.minestom.SceneActionResult
import gg.grounds.scene.minestom.SceneRuntimeIdentity
import java.time.Duration
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.common.PostEffectsPacket

/** Owns one host and admission gate for every dialogue NPC in this world. */
internal class LobbyDialogues(
    private val instance: Instance,
    private val identity: SceneRuntimeIdentity,
    private val bindings: List<LobbyDialogueBinding>,
    private val navigator: gg.grounds.lobby.LobbyNavigatorService? = null,
    private val queues: gg.grounds.lobby.LobbyQueueService? = null,
    private val assets: AssetBundle = StageDialogueAssets.load(),
    private val proxyPackEnabled: Boolean = StageDialogueAssets.isPackaged(),
    private val theme: DialogueTheme = defaultTheme(),
    private val locale: (Player) -> String = {
        gg.grounds.minigame.minestom.SidebarLocale.localeOf(it).toLanguageTag()
    },
    private val reducedMotion: (Player) -> Boolean = { false },
    private val observeLocale: (Player, Runnable) -> AutoCloseable =
        gg.grounds.minigame.minestom.SidebarLocale::observe,
) : LobbySceneDialogueStarter {
    private val lock = Any()
    @Volatile private var closed = false

    private class Session(val player: Player, val binding: LobbyDialogueBinding) {
        lateinit var ticket: MinestomDialogueStart
        var handle: gg.grounds.dialogue.core.runtime.DialogueHandle? = null
        var subscription: AutoCloseable? = null
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val connection = player.playerConnection
        var speech: GuideSpeechListener? = null
        var language = "en"
        var revision = 0L
        val actions =
            mutableMapOf<
                gg.grounds.dialogue.core.port.ActionHandler.ActionRef,
                CompletionStage<gg.grounds.dialogue.core.port.ActionHandler.ActionResult>,
            >()
    }

    @Volatile private var nameplatePackViewers = emptySet<Player>()

    internal fun nameplatePackViewers(): Set<Player> = nameplatePackViewers

    internal fun customNameplate(
        text: net.kyori.adventure.text.Component
    ): net.kyori.adventure.text.Component? {
        if (!proxyPackEnabled) return null
        val name =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(text)
        return try {
            gg.grounds.dialogue.minestom.render
                .MinecraftWidgetEncoder()
                .encodeSpeakerNameplate(name, "und", theme, assets)
        } catch (_: gg.grounds.dialogue.presentation.DialoguePresenter.FallbackRequired) {
            null
        } catch (_: gg.grounds.dialogue.core.runtime.DialogueFailure) {
            null
        }
    }

    private val sessions = IdentityHashMap<Player, Session>()
    private val voiceBank = GuideVoiceBank.load(assets.entries()[GuideVoiceBank.PATH])
    private val generation = "lobby-dialogue-" + UUID.randomUUID()
    private val contents =
        bindings
            .map { it.dialogue }
            .distinct()
            .associateWith {
                when (it) {
                    StageLobbyDialogue.DIALOGUE_ID -> StageDialogueContent.validated(generation)
                    LobbyGuideContent.DIALOGUE_ID -> LobbyGuideContent.validated(generation)
                    else -> error("Unsupported lobby dialogue $it")
                }
            }
    private val events =
        net.minestom.server.event.EventNode.all("lobby-dialogues-" + UUID.randomUUID())
    private val host =
        MinestomDialogueHost(
            MinestomDialogueOptions(
                RuntimeOptions.Budgets(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                ),
                MinecraftPackProfile.grounds263(),
                assets,
                // NPC rendering owns display entities; HUD sinks stay inactive.
                LocalOutputChannels(
                    { _, _ -> CompletableFuture.completedFuture(null) },
                    true,
                    { player, keys ->
                        player.sendPacket(PostEffectsPacket(keys.toList()))
                        CompletableFuture.completedFuture(null)
                    },
                ),
                UiLabelSource.defaults(),
                Duration.ofMillis(600),
            ),
            theme,
        )

    init {
        events.addListener(net.minestom.server.event.player.PlayerDisconnectEvent::class.java) {
            event ->
            synchronized(lock) {
                nameplatePackViewers = nameplatePackViewers - event.player
                sessions.remove(event.player)?.let { session ->
                    release(session)
                    session.ticket.cancel(
                        gg.grounds.dialogue.core.runtime.DialogueEndReason.DISCONNECTED
                    )
                }
            }
        }
        net.minestom.server.MinecraftServer.getGlobalEventHandler().addChild(events)
    }

    fun confirmPack(player: Player, state: DialoguePackReadiness.State) {
        synchronized(lock) {
            if (closed || !player.isOnline) return
            val accepted =
                host.confirmProxyPack(
                    player,
                    0,
                    state.pin,
                    state.revision,
                    state.ready && proxyPackEnabled,
                )
            if (accepted) {
                if (state.ready && proxyPackEnabled && state.pin == assets.pin()) {
                    nameplatePackViewers = nameplatePackViewers + player
                    sessions[player]?.let { it.ticket.updatePreferences(preferences(it)) }
                } else {
                    nameplatePackViewers = nameplatePackViewers - player
                }
            }
        }
    }

    internal fun presentation(player: Player) = sessions[player]?.ticket?.status()

    internal fun npcDialogueViewers(elementId: LocalId? = null): Set<Player> =
        synchronized(lock) {
            if (closed || sessions.isEmpty()) emptySet()
            else
                sessions.entries.mapNotNullTo(mutableSetOf()) { (player, session) ->
                    player.takeIf {
                        it.isOnline &&
                            it.instance === instance &&
                            session.ticket.status().effectiveSurface == "npc" &&
                            (elementId == null || session.binding.elementId == elementId)
                    }
                }
        }

    override fun start(
        context: SceneActionContext,
        dialogue: String,
        speaker: String,
    ): CompletionStage<SceneActionResult> =
        synchronized(lock) {
            val binding =
                bindings.singleOrNull {
                    it.elementId == context.elementId &&
                        it.dialogue == dialogue &&
                        it.speaker == speaker
                }
            if (
                closed ||
                    binding == null ||
                    context.identity != identity ||
                    context.player.instance !== instance ||
                    !context.player.isOnline ||
                    context.trigger != SceneTrigger.RIGHT_CLICK ||
                    context.hand != SceneHand.MAIN
            )
                return rejected("Lobby dialogue binding rejected")
            val previous = sessions[context.player]
            if (previous != null && previous.ticket.status().diagnostic != "CLOSED")
                return rejected("A dialogue is already active")
            previous?.let(::release)
            val session = Session(context.player, binding)
            session.language = normalize(locale(context.player))
            val texts =
                if (dialogue == LobbyGuideContent.DIALOGUE_ID) LobbyGuideContent.texts
                else StageDialogueContent.texts
            val services =
                DialogueServices(
                    generation,
                    emptyMap(),
                    if (dialogue == LobbyGuideContent.DIALOGUE_ID)
                        mapOf(
                            LobbyGuideContent.NAVIGATOR_ACTION to
                                menuHandler(session, LobbyGuideContent.NAVIGATOR_ACTION),
                            LobbyGuideContent.DUELS_ACTION to
                                menuHandler(session, LobbyGuideContent.DUELS_ACTION),
                        )
                    else emptyMap(),
                    null,
                    TextResolver.catalogue(texts, "en"),
                    emptySet(),
                )
            val speechListener =
                if (dialogue == LobbyGuideContent.DIALOGUE_ID && voiceBank != null)
                    speechListener(session)
                else MinestomDialogueSpeechListener.NO_OP
            val ticket =
                host.begin(
                    MinestomDialogueRequest(
                        UUID.randomUUID().toString(),
                        context.player,
                        0,
                        contents.getValue(dialogue),
                        services,
                        emptyMap(),
                        LocaleContext(session.language, 0, "en"),
                        preferences(session),
                        mapOf(
                            speaker to
                                MinestomSpeakerBinding(
                                    generation,
                                    instance,
                                    {
                                        MinestomSpeakerBinding.AnchorSnapshot(
                                            binding.position,
                                            binding.position.yaw(),
                                            binding.position.add(0.0, 1.6, 0.0),
                                            !closed,
                                        )
                                    },
                                )
                        ),
                        null,
                        InputProfile.defaults(),
                        0,
                        speechListener,
                        { binding.replacementLabel?.hideFor(context.player) },
                        { height, duration ->
                            binding.replacementLabel?.let { label ->
                                val rise = binding.position.y() + height - label.position.y()
                                label.riseFor(context.player, rise, duration)
                            }
                        },
                    )
                )
            session.ticket = ticket
            sessions[context.player] = session
            session.subscription =
                observeLocale(context.player, Runnable { refreshLocale(session) })
            // Re-read after subscribing so a preference changed during preparation cannot be lost.
            refreshLocale(session)
            ticket
                .result()
                .whenComplete { _, failure -> if (failure != null) release(session) }
                .thenApply { result ->
                    synchronized(lock) {
                        when (result) {
                            is DialogueStartResult.Started -> {
                                session.handle = result.handle
                                result.handle.completion().whenComplete { _, _ -> release(session) }
                                SceneActionResult.Success
                            }
                            is DialogueStartResult.Rejected -> {
                                release(session)
                                SceneActionResult.Rejected(
                                    "Dialogue start rejected: ${result.code()}"
                                )
                            }
                        }
                    }
                }
        }

    private fun current(session: Session) =
        !closed &&
            sessions[session.player] === session &&
            session.player.isOnline &&
            session.player.instance === instance

    private fun refreshLocale(session: Session) =
        synchronized(lock) {
            if (!current(session)) {
                session.subscription?.close()
                session.subscription = null
                return@synchronized
            }
            val language = normalize(locale(session.player))
            if (language != session.language) {
                session.language = language
                session.speech?.invalidate(language)
                session.ticket.refreshLocale(LocaleContext(language, ++session.revision, "en"))
            }
        }

    private fun release(session: Session) =
        synchronized(lock) {
            session.active.set(false)
            session.speech?.close()
            session.speech = null
            session.subscription?.close()
            session.subscription = null
            session.actions.clear()
        }

    private fun preferences(session: Session): PresentationPreferences {
        val motionReduced = reducedMotion(session.player)
        val base =
            if (session.binding.dialogue != LobbyGuideContent.DIALOGUE_ID) NPC_PREFERENCES
            else guidePreferences(motionReduced)
        val position = session.binding.replacementLabel?.position ?: return base
        val startHeight = position.y() - session.binding.position.y()
        return PresentationPreferences(
            base.surfaceKey(),
            base.graphemeDelay(),
            motionReduced,
            base.effects() + ("npcSpeakerLabelStartHeight" to startHeight.toString()),
        )
    }

    private fun speechListener(session: Session): MinestomDialogueSpeechListener =
        GuideSpeechListener(
                session.player,
                instance,
                session.binding,
                {
                    !closed &&
                        session.active.get() &&
                        session.player.playerConnection === session.connection
                },
                // Keep the scene model and its client entity IDs throughout the conversation.
                { reducedMotion(session.player) },
                null,
                checkNotNull(voiceBank),
                NPC_PREFERENCES.graphemeDelay(),
            )
            .also { session.speech = it }

    private fun menuHandler(
        session: Session,
        key: String,
    ): gg.grounds.dialogue.core.port.ActionHandler =
        object : gg.grounds.dialogue.core.port.ActionHandler {
            override fun execute(
                request: gg.grounds.dialogue.core.port.ActionHandler.ActionRequest
            ): CompletionStage<gg.grounds.dialogue.core.port.ActionHandler.ActionResult> =
                synchronized(lock) {
                    val ref = request.reference()
                    val handle = session.handle
                    if (
                        ref == null ||
                            !current(session) ||
                            handle == null ||
                            request.key() != key ||
                            request.arguments().isNotEmpty() ||
                            request.durable() ||
                            ref.audienceId != session.player.uuid.toString() ||
                            ref.hostGeneration != 0L ||
                            ref.sessionId != handle.snapshot().sessionId()
                    )
                        return CompletableFuture.completedFuture(
                            gg.grounds.dialogue.core.port.ActionHandler.ActionResult.REJECTED
                        )
                    session.actions[ref]?.let {
                        return it
                    }
                    val opened =
                        try {
                            if (key == LobbyGuideContent.NAVIGATOR_ACTION)
                                navigator?.open(session.player)
                            else queues?.openDuels(session.player)
                        } catch (_: Exception) {
                            null
                        }
                    val outcome =
                        opened
                            ?.toCompletableFuture()
                            ?.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            ?.handle { accepted, failure ->
                                if (failure == null && accepted == true)
                                    gg.grounds.dialogue.core.port.ActionHandler.ActionResult.SUCCESS
                                else
                                    gg.grounds.dialogue.core.port.ActionHandler.ActionResult
                                        .UNAVAILABLE
                            }
                            ?: CompletableFuture.completedFuture(
                                gg.grounds.dialogue.core.port.ActionHandler.ActionResult.UNAVAILABLE
                            )
                    // Requests only occur once per node visit. Keep the retry cache bounded.
                    if (session.actions.size >= 64)
                        session.actions.remove(session.actions.keys.first())
                    session.actions[ref] = outcome
                    outcome
                }

            override fun reconcile(
                reference: gg.grounds.dialogue.core.port.ActionHandler.ActionRef
            ) =
                CompletableFuture.completedFuture(
                    gg.grounds.dialogue.core.port.ActionHandler.ActionStatus.UNAVAILABLE
                )
        }

    fun closeAsync(): CompletionStage<Void> =
        synchronized(lock) {
            closed = true
            net.minestom.server.MinecraftServer.getGlobalEventHandler().removeChild(events)
            sessions.values.forEach(::release)
            sessions.clear()
            nameplatePackViewers = emptySet()
            host.closeAsync()
        }

    private fun rejected(reason: String): CompletionStage<SceneActionResult> =
        CompletableFuture.completedFuture(SceneActionResult.Rejected(reason))

    private fun normalize(tag: String) =
        if (Locale.forLanguageTag(tag.replace('_', '-')).language == "de") "de" else "en"

    companion object {
        fun defaultTheme() =
            DialogueTheme(
                "grounds-npc-v1",
                mapOf(
                    "text" to 0xF3F0EA,
                    "selected" to 0xE7B84B,
                    "muted" to 0xC1B9AA,
                    "hint" to 0xC1B9AA,
                ),
                1.08,
                Duration.ZERO,
            )

        internal const val GUIDE_SPEECH = "dialogue:guide/speech"

        internal fun guidePreferences(reducedMotion: Boolean) =
            PresentationPreferences(
                NPC_PREFERENCES.surfaceKey(),
                NPC_PREFERENCES.graphemeDelay(),
                reducedMotion,
                NPC_PREFERENCES.effects(),
            )

        private val NPC_PREFERENCES =
            PresentationPreferences(
                "npc",
                Duration.ofMillis(35),
                true,
                mapOf(
                    "npcVerticalOffset" to "0.35",
                    "vignette" to "true",
                    "npcBackdrop" to "true",
                    "npcEffectsFadeMs" to "250",
                    "typingSound" to "true",
                    "choiceSounds" to "true",
                ),
            )
    }
}

internal data class LobbyDialogueBinding(
    val elementId: LocalId,
    val position: Pos,
    val dialogue: String,
    val speaker: String,
    val replacementLabel: StageDialogueLabel? = null,
    val standaloneLabel: StageDialogueLabel? = null,
)
