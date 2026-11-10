package gg.grounds.minestom.lobby

import gg.grounds.lod.runtime.LodDeliveryStats
import gg.grounds.lod.runtime.LodRuntimeStats
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/** Retained for this adapter's lifetime; exports through the existing runtime endpoint. */
internal class LobbyLodMetrics(
    private val registry: MeterRegistry,
    private val runtime: () -> LodRuntimeStats,
    private val delivery: () -> LodDeliveryStats,
) : AutoCloseable {
    private val meters =
        listOf(
            FunctionCounter.builder("grounds.lod.delivery.authorized.body.bytes", this) {
                    it.runtime().authorizedPayloadBodyBytes.toDouble()
                }
                .description(
                    "Application body bytes authorized before final cancellation; not completed delivery"
                )
                .register(registry),
            FunctionCounter.builder("grounds.lod.delivery.serialized.body.bytes", this) {
                    it.delivery().serializedTerrainBodyBytes.toDouble()
                }
                .tag("kind", "terrain")
                .description(
                    "Completed voxel application bodies; excludes framing, TLS and acknowledgement"
                )
                .register(registry),
            FunctionCounter.builder("grounds.lod.delivery.serialized.body.bytes", this) {
                    it.delivery().serializedControlBodyBytes.toDouble()
                }
                .tag("kind", "control")
                .description(
                    "Completed LSS control application bodies; excludes channel registration and framing"
                )
                .register(registry),
            FunctionCounter.builder("grounds.lod.delivery.serialized.frames", this) {
                    it.delivery().serializedFrames.toDouble()
                }
                .register(registry),
            FunctionCounter.builder("grounds.lod.delivery.discarded.frames", this) {
                    it.runtime().discardedFrames.toDouble()
                }
                .description("Rejected writer attempts, including retries")
                .register(registry),
            Gauge.builder("grounds.lod.delivery.queued.frames", this) {
                    it.runtime().queuedFrames.toDouble()
                }
                .register(registry),
        )

    override fun close() {
        meters.forEach { registry.remove(it) }
    }
}
