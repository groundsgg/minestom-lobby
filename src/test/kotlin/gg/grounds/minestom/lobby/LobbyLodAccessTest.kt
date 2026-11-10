package gg.grounds.minestom.lobby

import gg.grounds.permissions.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class LobbyLodAccessTest {
    private val now = Instant.parse("2026-11-10T08:00:00Z")
    private val player = UUID.randomUUID()
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun snapshot(allowed: Boolean = true, expired: Boolean = false) =
        PermissionSnapshot(
            playerId = player,
            policyVersion = 1,
            issuedAt = now.minusSeconds(60),
            refreshAfter = now.minusSeconds(30),
            expiresAt = if (expired) now.minusSeconds(1) else now.plusSeconds(60),
            allowPatterns =
                if (allowed)
                    listOf(
                        PermissionGrant(
                            effect = PermissionEffect.ALLOW,
                            pattern = "grounds.lod.receive",
                            scope = PermissionScope(PermissionScopeKind.GLOBAL),
                            source = PermissionGrantSource.ROLE,
                        )
                    )
                else emptyList(),
            denyPatterns = emptyList(),
            roleKeys = setOf("premium"),
            roleMetadata = emptyList(),
        )

    @Test
    fun `global receive permission works on independent backend snapshots and follows revocation`() {
        val a = InMemoryPermissionSnapshots(mapOf(player to snapshot()))
        val b = InMemoryPermissionSnapshots(mapOf(player to snapshot()))
        val first =
            SnapshotPermissions(
                a,
                PermissionCheckScope(serverType = "lobby", server = "a", environment = "stage"),
                clock,
            )
        val second =
            SnapshotPermissions(
                b,
                PermissionCheckScope(serverType = "lobby", server = "b", environment = "stage"),
                clock,
            )
        assertTrue(LobbyLodAccess.allowed(first, player))
        assertTrue(LobbyLodAccess.allowed(second, player))
        a.put(snapshot(allowed = false))
        b.put(snapshot(allowed = false))
        assertFalse(LobbyLodAccess.allowed(first, player))
        assertFalse(LobbyLodAccess.allowed(second, player))
        a.put(snapshot())
        assertTrue(LobbyLodAccess.allowed(first, player))
    }

    @Test
    fun `a premium role name alone missing provider snapshot and expired access do not allow LSS`() {
        assertFalse(LobbyLodAccess.allowed(null, player))
        assertFalse(LobbyLodAccess.allowed(SnapshotPermissions(emptyMap(), clock = clock), player))
        assertFalse(
            LobbyLodAccess.allowed(
                SnapshotPermissions(mapOf(player to snapshot(allowed = false)), clock = clock),
                player,
            )
        )
        assertFalse(
            LobbyLodAccess.allowed(
                SnapshotPermissions(mapOf(player to snapshot(expired = true)), clock = clock),
                player,
            )
        )
    }

    @Test
    fun `receive permission is discoverable by the deployed catalog collector`() {
        val collected =
            gg.grounds.permissions.catalog
                .PermissionManifestCollector()
                .collect(
                    listOf(
                        gg.grounds.permissions.catalog.ManifestOrigin(
                            "lobby-runtime",
                            "provider-version",
                            javaClass.classLoader,
                        )
                    )
                )
        val app =
            assertNotNull(
                collected.manifests.singleOrNull { it.manifest.source == "minestom-lobby" }
            )
        assertTrue(app.sourceVersion.isNotBlank())
        assertFalse(app.sourceVersion.contains('$'))
        assertNotEquals("provider-version", app.sourceVersion)
        assertNotEquals(
            "local-SNAPSHOT",
            app.sourceVersion,
            "catalog provenance must retain the release source version in a normal container build",
        )
        val permission = app.manifest.permissions.single { it.key == "grounds.lod.receive" }
        assertEquals(
            setOf("GLOBAL", "SERVER_TYPE", "SERVER"),
            permission.supportedScopes.map { it.name }.toSet(),
        )
        assertTrue(collected.failures.isEmpty(), collected.failures.toString())
    }

    @Test
    fun `permission lookup errors fail closed for the optional download`() {
        val failed =
            object : Permissions {
                override fun hasPermission(playerId: UUID, permission: String): Boolean =
                    error("unavailable snapshot")

                override fun hasPermission(
                    playerId: UUID,
                    permission: String,
                    scope: PermissionCheckScope,
                ): Boolean = error("unavailable snapshot")

                override fun permissionDecision(
                    playerId: UUID,
                    permission: String,
                ): PermissionDecision = error("unavailable snapshot")

                override fun permissionDecision(
                    playerId: UUID,
                    permission: String,
                    scope: PermissionCheckScope,
                ): PermissionDecision = error("unavailable snapshot")

                override fun snapshot(playerId: UUID): PermissionSnapshot? = null
            }
        assertFalse(LobbyLodAccess.allowed(failed, player))
    }
}
