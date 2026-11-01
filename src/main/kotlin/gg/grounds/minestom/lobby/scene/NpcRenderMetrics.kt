package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.AssetKey
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal enum class NpcRenderPhase {
    POSE,
    TRANSFORM,
    VISIBILITY,
    DELIVERY,
}

internal enum class NpcMetadataKind {
    DELTA,
    SNAPSHOT,
    VISUAL,
}

/** Model keys come only from the finite compiled renderer catalogue, never viewer/NPC IDs. */
internal class NpcRenderMetrics(private val registry: MeterRegistry?) {
    private val models = ConcurrentHashMap<AssetKey, NpcModelMetrics>()

    fun forModel(asset: AssetKey): NpcModelMetrics? {
        val registry = registry ?: return null
        return models.computeIfAbsent(asset) { NpcModelMetrics(registry, it.value) }
    }
}

internal class NpcModelMetrics(registry: MeterRegistry, model: String) {
    private val work =
        NpcRenderPhase.entries.map { phase ->
            Timer.builder("grounds.npc.work.duration")
                .description(
                    "NPC render phase work; visibility includes lifecycle resynchronization"
                )
                .tags("model", model, "phase", phase.name.lowercase())
                .serviceLevelObjectives(*BOUNDS)
                .register(registry)
        }
    private val packets =
        counters(
            registry,
            model,
            "grounds.npc.metadata.packets",
            "Custom NPC metadata dispatches; excludes Minestom spawn packets",
        )
    private val fields =
        counters(
            registry,
            model,
            "grounds.npc.metadata.fields",
            "Metadata fields in custom NPC dispatches",
        )

    fun finish(phase: NpcRenderPhase, started: Long) {
        work[phase.ordinal].record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
    }

    fun metadata(kind: NpcMetadataKind, size: Int) {
        packets[kind.ordinal].increment()
        fields[kind.ordinal].increment(size.toDouble())
    }

    private fun counters(
        registry: MeterRegistry,
        model: String,
        name: String,
        description: String,
    ) =
        NpcMetadataKind.entries.map { kind ->
            Counter.builder(name)
                .description(description)
                .tags("model", model, "kind", kind.name.lowercase())
                .register(registry)
        }

    companion object {
        private val BOUNDS =
            longArrayOf(
                    10_000,
                    50_000,
                    100_000,
                    250_000,
                    500_000,
                    1_000_000,
                    2_500_000,
                    5_000_000,
                    10_000_000,
                    25_000_000,
                    50_000_000,
                    100_000_000,
                    500_000_000,
                )
                .map(Duration::ofNanos)
                .toTypedArray()
    }
}
