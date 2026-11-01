package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.AssetKey
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcRenderMetricsTest {
    @Test
    fun `model metrics publish bounded histograms and packet field counts on the existing registry`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        try {
            val metrics = NpcRenderMetrics(registry)
            val model = metrics.forModel(AssetKey("grounds:npc/test/metrics"))!!
            model.finish(NpcRenderPhase.POSE, System.nanoTime() - 1_000_000)
            model.metadata(NpcMetadataKind.DELTA, 3)
            assertEquals(
                1.0,
                registry.get("grounds.npc.metadata.packets").tag("kind", "delta").counter().count(),
            )
            assertEquals(
                3.0,
                registry.get("grounds.npc.metadata.fields").tag("kind", "delta").counter().count(),
            )
            val before = registry.meters.size
            metrics.forModel(AssetKey("grounds:npc/test/metrics"))
            assertEquals(before, registry.meters.size)
            assertTrue(before <= 10)
            val scrape = registry.scrape()
            assertTrue(scrape.contains("grounds_npc_work_duration_seconds_bucket"))
            assertTrue(scrape.contains("phase=\"pose\""))
            assertTrue(scrape.contains("grounds_npc_metadata_packets_total"))
        } finally {
            registry.close()
        }
    }

    @Test
    fun `metrics are optional when runtime metrics are disabled`() {
        assertNull(NpcRenderMetrics(null).forModel(AssetKey("grounds:npc/test/metrics")))
    }
}
