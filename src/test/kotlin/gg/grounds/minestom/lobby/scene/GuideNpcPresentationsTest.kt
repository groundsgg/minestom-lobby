package gg.grounds.minestom.lobby.scene

import gg.grounds.npc.minestom.presentation.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.npc.minestom.PersonalNpcRenderedAssetHandle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class GuideNpcPresentationsTest {
    @Test
    fun `only exact guide root captures capability and lifetime owns private cleanup`() {
        RendererFixture().use { fixture ->
            val base = fixture.create().get()
            val players = mutableListOf<Player>()
            var cancelled = 0
            val pending = CompletableFuture<NpcPresentationResult>()
            val personal =
                object : PersonalNpcRenderedAssetHandle, RenderedAssetHandle by base {
                    override fun preparePresentation(
                        options: NpcPresentationOptions
                    ): NpcPresentationStart {
                        players += options.player
                        return object : NpcPresentationStart {
                            override fun ready() = pending

                            override fun cancel() =
                                CompletableFuture.completedFuture<Void>(null).also { cancelled++ }
                        }
                    }
                }
            val delivery = CompletableFuture<RenderedAssetHandle>()
            val registry = GuideNpcPresentations()
            val factory =
                registry
                    .renderers(
                        SceneAssetRendererRegistry { _, _ ->
                            object : ScopedSceneAssetRendererFactory {
                                override fun create(context: SceneAssetRenderContext) =
                                    error("Scoped factory required")

                                override fun createScoped(
                                    context: SceneAssetRenderContext,
                                    lifetime: SceneRendererLifetime,
                                ) = delivery
                            }
                        }
                    )
                    .rendererFor(guideKey, AssetKind.NPC_BODY) as ScopedSceneAssetRendererFactory
            val a = fixture.player()
            val b = fixture.player()
            val options = NpcPresentationOptions.builder(a).build()
            for ((id, part) in listOf("other" to null, "spawn-guide" to LocalId("part"))) {
                factory.createScoped(
                    SceneAssetRenderContext(
                        fixture.instance,
                        LocalId(id),
                        part,
                        guideKey,
                        sceneTransform(),
                    ),
                    Lifetime(),
                )
            }
            delivery.complete(personal)
            assertNull(registry.prepare(LocalId("spawn-guide"), options))
            val lifetime = Lifetime()
            factory
                .createScoped(
                    SceneAssetRenderContext(
                        fixture.instance,
                        LocalId("spawn-guide"),
                        null,
                        guideKey,
                        sceneTransform(),
                    ),
                    lifetime,
                )
                .toCompletableFuture()
                .get()
            assertNotNull(registry.prepare(LocalId("spawn-guide"), options))
            assertNotNull(
                registry.prepare(LocalId("spawn-guide"), NpcPresentationOptions.builder(b).build())
            )
            assertSame(a, players[0])
            assertSame(b, players[1])
            lifetime.invalidate()
            lifetime.cleanup!!.toCompletableFuture().get()
            assertEquals(2, cancelled)
            assertNull(registry.prepare(LocalId("spawn-guide"), options))
        }
    }

    @Test
    fun `late renderer delivery after invalidation cannot register a guide`() {
        RendererFixture().use { fixture ->
            val registry = GuideNpcPresentations()
            val delivery = CompletableFuture<RenderedAssetHandle>()
            val lifetime = Lifetime()
            val base = fixture.create().get()
            val personal =
                object : PersonalNpcRenderedAssetHandle, RenderedAssetHandle by base {
                    override fun preparePresentation(
                        options: NpcPresentationOptions
                    ): NpcPresentationStart = error("Stale renderer used")
                }
            val factory =
                registry
                    .renderers(
                        SceneAssetRendererRegistry { _, _ ->
                            object : ScopedSceneAssetRendererFactory {
                                override fun create(context: SceneAssetRenderContext) = delivery

                                override fun createScoped(
                                    context: SceneAssetRenderContext,
                                    lifetime: SceneRendererLifetime,
                                ) = delivery
                            }
                        }
                    )
                    .rendererFor(guideKey, AssetKind.NPC_BODY) as ScopedSceneAssetRendererFactory
            factory.createScoped(
                SceneAssetRenderContext(
                    fixture.instance,
                    LocalId("spawn-guide"),
                    null,
                    guideKey,
                    sceneTransform(),
                ),
                lifetime,
            )
            lifetime.invalidate()
            delivery.complete(personal)
            assertNull(
                registry.prepare(
                    LocalId("spawn-guide"),
                    NpcPresentationOptions.builder(fixture.player()).build(),
                )
            )
            assertTrue(lifetime.cleanup!!.toCompletableFuture().isDone)
        }
    }

    @Test
    fun `speaking viewer gets private NPC and voice while neighbor keeps shared NPC`() {
        val key = AssetKey("test:bot")
        val model =
            decodeNpcModel(
                """{"schemaVersion":1,"asset":"test:bot","bounds":{"center":[0,1,0],"size":[1,2,1]},"bones":[{"id":"head","name":"head","parent":null,"origin":[0,1.5,0],"rotation":[0,0,0]}],"parts":[{"bone":"head","model":"grounds:npc_parts/test/cube","center":[0,1.8,0],"pivot":[0,1.8,0],"rotation":[0,0,0]}],"animations":{"idle":{"loop":"loop","length":1,"tracks":{}}},"sounds":{}}"""
            )
        val assets =
            AssetCatalog(
                CatalogId("test:assets"),
                "1",
                CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
                mapOf(
                    key to
                        AssetDefinition(
                            key,
                            AssetKind.NPC_BODY,
                            setOf(LocalId("idle")),
                            LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                            emptyMap(),
                        )
                ),
            )
        RendererFixture().use { fixture ->
            val viewer = fixture.player(net.minestom.server.coordinate.Pos(1.0, 1.0, 1.0))
            val neighbor = fixture.player(net.minestom.server.coordinate.Pos(2.0, 1.0, 1.0))
            val scope = Lifetime()
            val registry = GuideNpcPresentations()
            val factory =
                registry
                    .renderers(LobbySceneRenderers(assets, mapOf(key to model)))
                    .rendererFor(key, AssetKind.NPC_BODY) as ScopedSceneAssetRendererFactory
            fun tickUntil(done: () -> Boolean) {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (!done() && System.nanoTime() < deadline) fixture.instance.tick(0)
                assertTrue(done())
            }
            val creating =
                factory
                    .createScoped(
                        SceneAssetRenderContext(
                            fixture.instance,
                            LocalId("spawn-guide"),
                            null,
                            key,
                            sceneTransform(),
                        ),
                        scope,
                    )
                    .toCompletableFuture()
            tickUntil { creating.isDone }
            val parent = creating.get()
            assertNotNull((parent as HeadLookRenderedAssetHandle).headLookOrigin)
            val shared =
                fixture.instance.entities.single {
                    it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
                }
            tickUntil { viewer in shared.viewers && neighbor in shared.viewers }
            val output =
                GuideSpeechOutput(
                    { true },
                    { false },
                    { guard ->
                        registry.prepare(
                            LocalId("spawn-guide"),
                            NpcPresentationOptions.builder(viewer).publicationGuard(guard).build(),
                        )
                    },
                    { pitch ->
                        viewer.playSound(
                            net.kyori.adventure.sound.Sound.sound(
                                net.kyori.adventure.key.Key.key(LobbyDialogues.GUIDE_SPEECH),
                                net.kyori.adventure.sound.Sound.Source.VOICE,
                                .4f,
                                pitch,
                            ),
                            net.kyori.adventure.sound.Sound.Emitter.self(),
                        )
                    },
                    {},
                )
            output.reveal("a")
            tickUntil {
                fixture.instance.entities.count {
                    it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
                } == 2 && viewer !in shared.viewers
            }
            val personal =
                fixture.instance.entities.single {
                    it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY &&
                        it !== shared
                }
            assertTrue(neighbor in shared.viewers)
            assertTrue(viewer in personal.viewers)
            assertFalse(neighbor in personal.viewers)
            fun sounds(player: TestPlayer) =
                player.connection.packets.count {
                    it.javaClass.simpleName.endsWith("SoundEffectPacket")
                }
            assertEquals(1, sounds(viewer))
            assertEquals(0, sounds(neighbor))
            output.close()
            tickUntil { personal !in fixture.instance.entities }
            assertTrue(neighbor in shared.viewers)
            scope.invalidate()
            (parent as AsyncRenderedAssetHandle).closeAsync().toCompletableFuture().get()
        }
    }

    @Test
    fun `two guide bindings select exact parents and old invalidation preserves current generations`() {
        RendererFixture().use { fixture ->
            val first = LocalId("first-guide")
            val second = LocalId("second-guide")
            val registry = GuideNpcPresentations(setOf(first, second))
            val base = fixture.create().get()
            val requested = mutableListOf<String>()
            var generation = "old"
            val factory =
                registry
                    .renderers(
                        SceneAssetRendererRegistry { _, _ ->
                            object : ScopedSceneAssetRendererFactory {
                                override fun create(context: SceneAssetRenderContext) =
                                    error("Scope required")

                                override fun createScoped(
                                    context: SceneAssetRenderContext,
                                    lifetime: SceneRendererLifetime,
                                ): CompletionStage<RenderedAssetHandle> {
                                    val name = context.elementId.value + ":" + generation
                                    return CompletableFuture.completedFuture(
                                        object :
                                            PersonalNpcRenderedAssetHandle,
                                            RenderedAssetHandle by base {
                                            override fun preparePresentation(
                                                options: NpcPresentationOptions
                                            ): NpcPresentationStart {
                                                requested += name
                                                return object : NpcPresentationStart {
                                                    override fun ready() =
                                                        CompletableFuture<NpcPresentationResult>()

                                                    override fun cancel() =
                                                        CompletableFuture.completedFuture<Void>(
                                                            null
                                                        )
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    )
                    .rendererFor(guideKey, AssetKind.NPC_BODY) as ScopedSceneAssetRendererFactory
            fun capture(id: LocalId, lifetime: Lifetime) =
                factory
                    .createScoped(
                        SceneAssetRenderContext(
                            fixture.instance,
                            id,
                            null,
                            guideKey,
                            sceneTransform(),
                        ),
                        lifetime,
                    )
                    .toCompletableFuture()
                    .get()
            val old = Lifetime()
            val current = Lifetime()
            val other = Lifetime()
            capture(first, old)
            generation = "current"
            capture(first, current)
            capture(second, other)
            old.invalidate()
            val options = NpcPresentationOptions.builder(fixture.player()).build()
            assertNotNull(registry.prepare(first, options))
            assertNotNull(registry.prepare(second, options))
            assertEquals(listOf("first-guide:current", "second-guide:current"), requested)
            current.invalidate()
            assertNull(registry.prepare(first, options))
            assertNotNull(registry.prepare(second, options))
            assertEquals("second-guide:current", requested.last())
            other.invalidate()
        }
    }

    private class Lifetime : SceneRendererLifetime {
        var current = true
        val callbacks = mutableListOf<Runnable>()
        var cleanup: CompletionStage<Void>? = null

        override fun isCurrent() = current

        override fun runIfCurrent(operation: Runnable): Boolean {
            if (!current) return false
            operation.run()
            return true
        }

        override fun onInvalidated(callback: Runnable): AutoCloseable {
            if (current) callbacks.add(callback) else callback.run()
            return AutoCloseable {}
        }

        override fun reportFailure(failure: Throwable) = throw AssertionError(failure)

        override fun trackCleanup(cleanup: CompletionStage<Void>) {
            this.cleanup = cleanup
        }

        fun invalidate() {
            current = false
            callbacks.toList().forEach(Runnable::run)
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun init() {
            MinecraftServer.init()
        }
    }
}
