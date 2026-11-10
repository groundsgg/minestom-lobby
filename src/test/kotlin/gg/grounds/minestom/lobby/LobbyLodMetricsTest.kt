package gg.grounds.minestom.lobby

import gg.grounds.lod.runtime.LodDeliveryStats
import gg.grounds.lod.runtime.LodRuntimeStats
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.*
import org.junit.jupiter.api.Test

class LobbyLodMetricsTest {
    @Test
    fun `existing registry exposes completion separately and removes retired adapter meters`() {
        val registry = SimpleMeterRegistry()
        try {
            var runtime = LodRuntimeStats(1000, 1, 1, false)
            var delivery = LodDeliveryStats(0, 0, 0)
            LobbyLodMetrics(registry, { runtime }, { delivery }).use {
                assertEquals(
                    1000.0,
                    registry
                        .get("grounds.lod.delivery.authorized.body.bytes")
                        .functionCounter()
                        .count(),
                )
                assertEquals(
                    0.0,
                    registry
                        .get("grounds.lod.delivery.serialized.body.bytes")
                        .tag("kind", "terrain")
                        .functionCounter()
                        .count(),
                )
                runtime = runtime.copy(queuedFrames = 0)
                delivery = LodDeliveryStats(50, 5, 2)
                assertEquals(
                    50.0,
                    registry
                        .get("grounds.lod.delivery.serialized.body.bytes")
                        .tag("kind", "terrain")
                        .functionCounter()
                        .count(),
                )
                assertEquals(
                    5.0,
                    registry
                        .get("grounds.lod.delivery.serialized.body.bytes")
                        .tag("kind", "control")
                        .functionCounter()
                        .count(),
                )
                assertTrue(
                    registry.meters
                        .flatMap { it.id.tags }
                        .all { it.key == "kind" && it.value in setOf("terrain", "control") }
                )
            }
            assertTrue(
                registry.meters.isEmpty(),
                "old adapter must not be retained or reused on restart",
            )
            LobbyLodMetrics(
                    registry,
                    { LodRuntimeStats(0, 0, 0, false) },
                    { LodDeliveryStats(0, 0, 0) },
                )
                .use {
                    assertEquals(
                        0.0,
                        registry
                            .get("grounds.lod.delivery.authorized.body.bytes")
                            .functionCounter()
                            .count(),
                    )
                }
        } finally {
            registry.close()
        }
    }
}
