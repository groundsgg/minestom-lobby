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
    navigator: gg.grounds.lobby.LobbyNavigatorService? = null,
    queues: gg.grounds.lobby.LobbyQueueService? = null,
    private val assets: AssetBundle = StageDialogueAssets.load(),
    private val proxyPackEnabled: Boolean = StageDialogueAssets.isPackaged(),
    private val theme: DialogueTheme = defaultTheme(),
    private val locale: (Player) -> String = {
        gg.grounds.minigame.minestom.SidebarLocale.localeOf(it).toLanguageTag()
    },
    private val reducedMotion: (Player) -> Boolean = { false },
    private val observeLocale: (Player, Runnable) -> AutoCloseable =
        gg.grounds.minigame.minestom.SidebarLocale::observe,
    private val admission: LobbyDialogueAdmission = LobbyDialogueAdmission(false),
    internal val metadataContract: LobbyDialogueHostContract? = null,
    internal val authoredContract: LobbyDialogueHostContract? = null,
    private val generation: String = "lobby-dialogue-" + UUID.randomUUID(),
    actualRegistrations: Map<String, LobbyDialogueHostRegistration>? = null,
    private val authoredEntries: Map<String, LobbyAuthoredDialogueCatalogue.Entry> = emptyMap(),
) : LobbySceneDialogueStarter {
    private val lock = Any()
    @Volatile private var closed = false

    private class Session(val player: Player, val binding: LobbyDialogueBinding) {
        var ticket: MinestomDialogueStart? = null
        var actionSessionId: UUID? = null
        var handle: gg.grounds.dialogue.core.runtime.DialogueHandle? = null
        var subscription: AutoCloseable? = null
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val connection = player.playerConnection
        var speech: GuideSpeechListener? = null
        var language = "en"
        var revision = 0L
        val pendingMenus =
            java.util.concurrent.ConcurrentHashMap.newKeySet<CompletableFuture<Boolean>>()
        val actions =
            mutableMapOf<
                gg.grounds.dialogue.core.port.ActionHandler.ActionRef,
                CompletionStage<gg.grounds.dialogue.core.port.ActionHandler.ActionResult>,
            >()
    }

    private val cancelledMenus =
        java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Boolean>>()
    private val sessions = IdentityHashMap<Player, Session>()
    // Diagnostic snapshots retain no ticket, subscription, action or player reference.
    private val ended =
        java.util.WeakHashMap<Player, gg.grounds.dialogue.minestom.render.PresentationStatus>()
    private val voiceBank = GuideVoiceBank.load(assets.entries()[GuideVoiceBank.PATH])
    private val guideNpcs =
        GuideNpcPresentations(bindings.filter { isGuide(it) }.map { it.elementId }.toSet())

    internal fun captureGuideRenderers(
        delegate: gg.grounds.scene.minestom.SceneAssetRendererRegistry
    ) = guideNpcs.renderers(delegate)

    internal val registrations =
        java.util.Collections.unmodifiableMap(
            actualRegistrations
                ?: bindings
                    .map { it.dialogue }
                    .distinct()
                    .associateWith {
                        if (metadataContract == null)
                            LobbyDialogueHostRegistration.create(it, navigator, queues)
                        else
                            LobbyDialogueHostRegistration.create(
                                metadataContract,
                                it,
                                navigator,
                                queues,
                            )
                    }
        )
    private val contents =
        if (authoredContract != null) authoredEntries.mapValues { it.value.validated }
        else
            (if (metadataContract == null) registrations else emptyMap()).mapValues {
                (dialogue, registration) ->
                when (dialogue) {
                    StageDialogueContent.DIALOGUE_ID ->
                        StageDialogueContent.validated(generation, registration)
                    LobbyGuideContent.DIALOGUE_ID ->
                        LobbyGuideContent.validated(generation, registration)
                    else -> error("Unsupported lobby dialogue $dialogue")
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
                sessions.remove(event.player)?.let { session ->
                    release(session)
                    session.ticket?.cancel(
                        gg.grounds.dialogue.core.runtime.DialogueEndReason.DISCONNECTED
                    )
                }
            }
            cancelReleasedMenus()
        }
        events.addListener(net.minestom.server.event.player.PlayerTickEvent::class.java) { event ->
            synchronized(lock) {
                sessions[event.player]
                    ?.takeUnless { current(it) }
                    ?.let {
                        release(it)
                        it.ticket?.cancel(
                            gg.grounds.dialogue.core.runtime.DialogueEndReason.DISCONNECTED
                        )
                    }
            }
            cancelReleasedMenus()
        }
        events.addListener(
            net.minestom.server.event.instance.RemoveEntityFromInstanceEvent::class.java
        ) { event ->
            if (event.instance === instance) {
                synchronized(lock) {
                    (event.entity as? Player)?.let { player ->
                        sessions[player]?.let {
                            release(it)
                            it.ticket?.cancel(
                                gg.grounds.dialogue.core.runtime.DialogueEndReason.DISCONNECTED
                            )
                        }
                    }
                }
                cancelReleasedMenus()
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
            if (accepted && state.ready && proxyPackEnabled)
                sessions[player]?.let { it.ticket?.updatePreferences(preferences(it)) }
        }
    }

    internal fun presentation(player: Player) =
        synchronized(lock) { sessions[player]?.ticket?.status() ?: ended[player] }

    internal fun npcDialogueViewers(elementId: LocalId? = null): Set<Player> =
        synchronized(lock) {
            if (closed || sessions.isEmpty()) emptySet()
            else
                sessions.entries.mapNotNullTo(mutableSetOf()) { (player, session) ->
                    player.takeIf {
                        it.isOnline &&
                            it.instance === instance &&
                            session.ticket?.status()?.effectiveSurface == "npc" &&
                            (elementId == null || session.binding.elementId == elementId)
                    }
                }
        }

    override fun start(
        context: SceneActionContext,
        dialogue: String,
        speaker: String,
    ): CompletionStage<SceneActionResult> =
        try {
            synchronized(lock) {
                if (metadataContract != null || !admission.allowed)
                    return rejected("Lobby dialogue admission denied")
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
                sessions.values
                    .toList()
                    .filter {
                        it.player !== context.player && it.player.uuid == context.player.uuid
                    }
                    .forEach {
                        release(it)
                        it.ticket?.cancel(
                            gg.grounds.dialogue.core.runtime.DialogueEndReason.SUPERSEDED
                        )
                    }
                val previous = sessions[context.player]
                if (previous != null && previous.active.get())
                    return rejected("A dialogue is already active")
                previous?.let(::release)
                val session = Session(context.player, binding)
                session.language = normalize(locale(context.player))
                ended.remove(context.player)
                sessions[context.player] = session
                val entry = authoredEntries[dialogue]
                val texts =
                    if (dialogue == LobbyGuideContent.DIALOGUE_ID) LobbyGuideContent.texts
                    else StageDialogueContent.texts
                val services =
                    DialogueServices(
                        generation,
                        emptyMap(),
                        registrations.getValue(dialogue).bindActions { menuHandler(session, it) },
                        null,
                        entry?.resolver ?: TextResolver.catalogue(texts, "en"),
                        registrations.getValue(dialogue).capabilities,
                    )
                val speechListener =
                    if (isGuide(binding) && voiceBank != null) speechListener(session)
                    else MinestomDialogueSpeechListener.NO_OP
                val ticket =
                    try {
                        host.begin(
                            MinestomDialogueRequest(
                                UUID.randomUUID().toString(),
                                context.player,
                                0,
                                contents.getValue(dialogue),
                                services,
                                emptyMap(),
                                entry?.locale(session.language, 0)
                                    ?: LocaleContext(session.language, 0, "en"),
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
                            )
                        )
                    } catch (failure: Throwable) {
                        release(session)
                        throw failure
                    }
                session.ticket = ticket
                if (!current(session)) {
                    ticket.cancel(gg.grounds.dialogue.core.runtime.DialogueEndReason.DISCONNECTED)
                }
                session.subscription =
                    observeLocale(context.player, Runnable { refreshLocale(session) })
                // Re-read after subscribing so a preference changed during preparation cannot be
                // lost.
                refreshLocale(session)
                ticket
                    .result()
                    .whenComplete { _, failure -> if (failure != null) release(session) }
                    .thenApply { result ->
                        synchronized(lock) {
                            when (result) {
                                is DialogueStartResult.Started -> {
                                    if (current(session)) {
                                        session.handle = result.handle
                                        check(
                                            session.actionSessionId == null ||
                                                session.actionSessionId ==
                                                    result.handle.snapshot().sessionId()
                                        )
                                        result.handle.completion().whenComplete { _, _ ->
                                            release(session)
                                        }
                                    } else
                                        ticket.cancel(
                                            gg.grounds.dialogue.core.runtime.DialogueEndReason
                                                .DISCONNECTED
                                        )
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
                    .whenComplete { _, _ -> cancelReleasedMenus() }
            }
        } finally {
            cancelReleasedMenus()
        }

    private fun current(session: Session): Boolean =
        synchronized(lock) {
            !closed &&
                session.active.get() &&
                sessions[session.player] === session &&
                session.player.playerConnection === session.connection &&
                session.player.isOnline &&
                session.player.instance === instance
        }

    private fun refreshLocale(session: Session) =
        try {
            synchronized(lock) {
                if (!current(session)) {
                    release(session)
                    session.ticket?.cancel(
                        gg.grounds.dialogue.core.runtime.DialogueEndReason.DISCONNECTED
                    )
                    return@synchronized
                }
                val language = normalize(locale(session.player))
                if (language != session.language) {
                    session.language = language
                    session.speech?.invalidate(language)
                    session.ticket?.refreshLocale(
                        authoredEntries[session.binding.dialogue]?.locale(
                            language,
                            ++session.revision,
                        ) ?: LocaleContext(language, ++session.revision, "en")
                    )
                }
            }
        } finally {
            cancelReleasedMenus()
        }

    private fun release(session: Session) =
        try {
            synchronized(lock) {
                session.active.set(false)
                if (sessions[session.player] === session) {
                    sessions.remove(session.player)
                    session.ticket?.status()?.let {
                        ended[session.player] = it.copy(diagnostic = "CLOSED")
                    }
                }
                session.speech?.close()
                session.speech = null
                session.subscription?.close()
                session.subscription = null
                session.actions.clear()
                cancelledMenus.addAll(session.pendingMenus)
                session.pendingMenus.clear()
            }
        } finally {
            cancelReleasedMenus()
        }

    /** Provider cancellation can acquire its service lock; never do it under the owner lock. */
    private fun cancelReleasedMenus() {
        if (Thread.holdsLock(lock)) return
        while (true) (cancelledMenus.poll() ?: return).cancel(false)
    }

    private fun preferences(session: Session): PresentationPreferences =
        if (!isGuide(session.binding)) NPC_PREFERENCES
        else guidePreferences(reducedMotion(session.player), voiceBank != null)

    private fun speechListener(session: Session): MinestomDialogueSpeechListener =
        GuideSpeechListener(
                session.player,
                instance,
                session.binding,
                {
                    !closed &&
                        session.active.get() &&
                        session.player.playerConnection === session.connection &&
                        session.player.instance === instance
                },
                { reducedMotion(session.player) },
                { guard ->
                    guideNpcs.prepare(
                        session.binding.elementId,
                        gg.grounds.npc.minestom.presentation.NpcPresentationOptions.builder(
                                session.player
                            )
                            .publicationGuard(guard)
                            .build(),
                    )
                },
                checkNotNull(voiceBank),
                NPC_PREFERENCES.graphemeDelay(),
            )
            .also { session.speech = it }

    private fun menuHandler(
        session: Session,
        provider: LobbyDialogueHostRegistration.ActionProvider,
    ): gg.grounds.dialogue.core.port.ActionHandler =
        object : gg.grounds.dialogue.core.port.ActionHandler {
            override fun execute(
                request: gg.grounds.dialogue.core.port.ActionHandler.ActionRequest
            ): CompletionStage<gg.grounds.dialogue.core.port.ActionHandler.ActionResult> =
                try {
                    synchronized(lock) {
                        val ref = request.reference()
                        val handle = session.handle
                        if (
                            ref == null ||
                                !current(session) ||
                                request.key() != provider.key ||
                                request.arguments().isNotEmpty() ||
                                request.durable() ||
                                ref.audienceId != session.player.uuid.toString() ||
                                ref.hostGeneration != 0L ||
                                (handle != null &&
                                    ref.sessionId != handle.snapshot().sessionId()) ||
                                (session.actionSessionId != null &&
                                    ref.sessionId != session.actionSessionId)
                        )
                            return CompletableFuture.completedFuture(
                                gg.grounds.dialogue.core.port.ActionHandler.ActionResult.REJECTED
                            )
                        session.actionSessionId = ref.sessionId
                        session.actions[ref]?.let {
                            return it
                        }
                        val opened =
                            try {
                                provider.open(session.player)
                            } catch (_: Exception) {
                                null
                            }
                        val original = opened?.toCompletableFuture()
                        val outcome =
                            CompletableFuture<
                                gg.grounds.dialogue.core.port.ActionHandler.ActionResult
                            >()
                        if (original == null) {
                            outcome.complete(
                                gg.grounds.dialogue.core.port.ActionHandler.ActionResult.UNAVAILABLE
                            )
                        } else {
                            if (current(session)) session.pendingMenus.add(original)
                            else cancelledMenus.add(original)
                            original
                                .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                .whenComplete { accepted, failure ->
                                    session.pendingMenus.remove(original)
                                    // A service completes under its own lock. Renew delivery on the
                                    // host
                                    // scheduler rather than entering the owner or runtime from that
                                    // lock.
                                    instance.scheduler().scheduleNextTick {
                                        synchronized(lock) {
                                            val result =
                                                if (
                                                    !current(session) ||
                                                        session.actionSessionId != ref.sessionId
                                                )
                                                    gg.grounds.dialogue.core.port.ActionHandler
                                                        .ActionResult
                                                        .REJECTED
                                                else if (failure == null && accepted == true)
                                                    gg.grounds.dialogue.core.port.ActionHandler
                                                        .ActionResult
                                                        .SUCCESS
                                                else
                                                    gg.grounds.dialogue.core.port.ActionHandler
                                                        .ActionResult
                                                        .UNAVAILABLE
                                            outcome.complete(result)
                                        }
                                        cancelReleasedMenus()
                                    }
                                }
                        }
                        // Requests only occur once per node visit. Keep the retry cache bounded.
                        if (session.actions.size >= 64)
                            session.actions.remove(session.actions.keys.first())
                        session.actions[ref] = outcome
                        outcome
                    }
                } finally {
                    cancelReleasedMenus()
                }

            override fun reconcile(
                reference: gg.grounds.dialogue.core.port.ActionHandler.ActionRef
            ) =
                CompletableFuture.completedFuture(
                    gg.grounds.dialogue.core.port.ActionHandler.ActionStatus.UNAVAILABLE
                )
        }

    fun closeAsync(): CompletionStage<Void> =
        try {
            synchronized(lock) {
                closed = true
                net.minestom.server.MinecraftServer.getGlobalEventHandler().removeChild(events)
                sessions.values.toList().forEach(::release)
                sessions.clear()
                ended.clear()
                host.closeAsync()
            }
        } finally {
            cancelReleasedMenus()
        }

    private fun rejected(reason: String): CompletionStage<SceneActionResult> =
        CompletableFuture.completedFuture(SceneActionResult.Rejected(reason))

    private fun isGuide(binding: LobbyDialogueBinding) =
        binding.dialogue == LobbyGuideContent.DIALOGUE_ID ||
            authoredContract == LobbyDialogueHostContract.GUIDE

    private fun normalize(tag: String): String {
        val canonical = Locale.forLanguageTag(tag.replace('_', '-')).toLanguageTag()
        return if (authoredContract != null) canonical
        else if (Locale.forLanguageTag(canonical).language == "de") "de" else "en"
    }

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

        internal fun guidePreferences(reducedMotion: Boolean, voiceAvailable: Boolean = true) =
            PresentationPreferences(
                NPC_PREFERENCES.surfaceKey(),
                NPC_PREFERENCES.graphemeDelay(),
                reducedMotion,
                NPC_PREFERENCES.effects() + ("typingSound" to (!voiceAvailable).toString()),
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
)
