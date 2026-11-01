package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import net.kyori.adventure.text.Component

/** Explicit map migration; the runtime never synthesizes these NPCs when a scene is missing. */
internal fun migrateQueueNpcScene(
    scene: SceneDocument,
    assets: AssetCatalog,
    body: AssetKey,
    idleAnimation: LocalId? = null,
    greetingAnimation: LocalId? = null,
    appearances: Map<String, QueueNpcAppearance> = emptyMap(),
): SceneDocument {
    require(scene.catalogs.assets.id == assets.id) { "Cannot migrate a different asset namespace" }
    require(LobbySceneCatalogs.resolve(scene.catalogs.actions) != null) {
        "Unsupported existing action catalog"
    }
    val added =
        QUEUE_NPC_PLACEMENTS.map { placement ->
            val appearance =
                appearances[placement.mode ?: "duels"]
                    ?: QueueNpcAppearance(body, idleAnimation, greetingAnimation)
            val body = appearance.body
            val idleAnimation = appearance.idle
            val greetingAnimation = appearance.greeting
            val definition =
                requireNotNull(assets.assets[body]) {
                    "NPC model is absent from the selected asset catalog: $body"
                }
            require(definition.kind == AssetKind.NPC_BODY) { "Queue NPC body must be NPC_BODY" }
            if (definition.animations.isNotEmpty()) {
                require(idleAnimation != null && greetingAnimation != null) {
                    "Select the model's shipped idle and greeting clips explicitly"
                }
            }
            require(idleAnimation == null || idleAnimation in definition.animations) {
                "Idle animation is not shipped by this model"
            }
            require(greetingAnimation == null || greetingAnimation in definition.animations) {
                "Greeting animation is not shipped by this model"
            }
            val bounds =
                requireNotNull(definition.defaultBounds) { "NPC model requires explicit bounds" }
            val id = LocalId("queue-${placement.mode ?: "duels"}")
            val target = ElementTarget(id, null)
            Npc(
                id = id,
                group = null,
                transform =
                    Transform(
                        Vec3(placement.x, 140.0, placement.z),
                        // Villager pitch moved its head; a model root pitch tilts its entire body.
                        EulerRotation(-placement.yaw, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                body = body,
                label = Component.text(placement.label),
                labelOffset = Vec3(0.0, bounds.center.y + bounds.size.y / 2 + 0.3, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = idleAnimation,
                interactionBounds = bounds,
                proximity = if (greetingAnimation != null) ProximitySensor(4.0, 5.0) else null,
                bindings =
                    listOf(
                        TriggerBinding(
                            SceneTrigger.HOVER_ENTER,
                            emptyList(),
                            0,
                            0,
                            listOf(SetViewerHighlightAction(target, true, 0)),
                        ),
                        TriggerBinding(
                            SceneTrigger.HOVER_LEAVE,
                            emptyList(),
                            0,
                            0,
                            listOf(SetViewerHighlightAction(target, false, 0)),
                        ),
                        TriggerBinding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(HandCondition(SceneHand.MAIN)),
                            500,
                            0,
                            listOf(
                                if (placement.mode == null)
                                    ApplicationAction(LobbySceneCatalogs.OPEN_DUEL_MENU, emptyMap())
                                else
                                    ApplicationAction(
                                        LobbySceneCatalogs.TOGGLE_QUEUE,
                                        mapOf(
                                            LobbySceneCatalogs.MODE to
                                                StringArgument(placement.mode)
                                        ),
                                    )
                            ),
                        ),
                    ) +
                        if (greetingAnimation != null)
                            listOf(
                                TriggerBinding(
                                    SceneTrigger.PROXIMITY_ENTER,
                                    emptyList(),
                                    appearance.greetingCooldown,
                                    0,
                                    listOf(StartAnimationAction(target, greetingAnimation)),
                                )
                            )
                        else emptyList(),
            )
        }
    val existing = scene.elements.associateBy { it.id }
    added.forEach { npc ->
        require(existing[npc.id] == null || existing[npc.id] == npc) {
            "Scene already has a conflicting element: ${npc.id.value}"
        }
    }
    val migrated =
        SceneDocument(
            scene.schemaVersion,
            scene.id,
            scene.metadata,
            SceneCatalogReferences(
                CatalogReference(assets.id, assets.version),
                CatalogReference(LobbySceneCatalogs.CURRENT.id, LobbySceneCatalogs.CURRENT.version),
            ),
            scene.groups,
            scene.elements + added.filterNot { it.id in existing },
        )
    val validation = SceneValidation.validateCatalogs(migrated, assets, LobbySceneCatalogs.CURRENT)
    require(validation.isValid) { "Migrated scene failed validation: ${validation.problems}" }
    return migrated
}

internal data class QueueNpcAppearance(
    val body: AssetKey,
    val idle: LocalId?,
    val greeting: LocalId?,
    val greetingCooldown: Long = 5000,
)

internal fun migrateGroundsQueueNpcScene(
    scene: SceneDocument,
    assets: AssetCatalog,
): SceneDocument {
    fun appearance(path: String, greeting: String = "wave") =
        QueueNpcAppearance(AssetKey("grounds:npc/$path"), LocalId("idle"), LocalId(greeting), 15000)
    val choices =
        mapOf(
            "duels" to appearance("guildfolk/forge_master", "hello"),
            "bedwars-2x1" to appearance("craftmasters/shipman"),
            "bedwars-squads" to appearance("craftmasters/shipman"),
            "keystone-2x1" to appearance("townfolk/builder"),
            "getdown" to appearance("townfolk/ragpicker"),
            "survivalgames-solo" to appearance("guildfolk/crystal_prospector", "hello"),
        )
    return migrateQueueNpcScene(
        scene,
        assets,
        choices.getValue("duels").body,
        appearances = choices,
    )
}

private data class QueueNpcPlacement(
    val mode: String?,
    val label: String,
    val x: Double,
    val z: Double,
    val yaw: Double,
)

// Exact positions and horizontal facing from Hendrik's QueueNpcs (plugin-lobby PR 102).
private val QUEUE_NPC_PLACEMENTS =
    listOf(
        QueueNpcPlacement(null, "Duels", -7.5, 73.5, -9.89),
        QueueNpcPlacement("bedwars-2x1", "BedWars 2x1", -6.5, 71.5, -6.74),
        QueueNpcPlacement("bedwars-squads", "BedWars Squads", -4.5, 70.5, -2.24),
        QueueNpcPlacement("keystone-2x1", "Keystone 2x1", -2.5, 70.5, 2.56),
        QueueNpcPlacement("getdown", "GetDown", -0.5, 71.5, 7.21),
        QueueNpcPlacement("survivalgames-solo", "SurvivalGames", 0.5, 73.5, 10.06),
    )

internal fun writeQueueNpcScene(
    input: Path,
    output: Path,
    body: AssetKey?,
    idleAnimation: LocalId? = null,
    greetingAnimation: LocalId? = null,
): Path {
    require(Files.isRegularFile(input, NOFOLLOW_LINKS)) { "Input must be a regular scene file" }
    val bytes =
        Files.newInputStream(input, READ, NOFOLLOW_LINKS).use {
            it.readNBytes(16 * 1024 * 1024 + 1)
        }
    require(bytes.size <= 16 * 1024 * 1024) { "Scene exceeds 16 MiB" }
    val decoded = SceneJson.decode(bytes)
    require(decoded is SceneDecodeResult.Success) { "Invalid input scene: $decoded" }
    val encoded =
        SceneJson.encode(
            if (body == null)
                migrateGroundsQueueNpcScene(decoded.scene, GroundsAssetCatalog.catalog)
            else
                migrateQueueNpcScene(
                    decoded.scene,
                    GroundsAssetCatalog.catalog,
                    body,
                    idleAnimation,
                    greetingAnimation,
                )
        )
    check(encoded is SceneEncodeResult.Success) { "Could not encode scene: $encoded" }
    Files.write(output, encoded.bytes, CREATE_NEW, WRITE)
    return output
}

fun main(arguments: Array<String>) {
    require(arguments.size in 3..5) {
        "Expected input-scene.json new-output-scene.json npc-body-asset-key|auto [idle-clip|-] [greeting-clip|-]"
    }
    println(
        "Wrote migrated scene: " +
            writeQueueNpcScene(
                Path.of(arguments[0]),
                Path.of(arguments[1]),
                if (arguments[2] == "auto") null else AssetKey(arguments[2]),
                arguments.getOrNull(3)?.takeUnless { it == "-" }?.let(::LocalId),
                arguments.getOrNull(4)?.takeUnless { it == "-" }?.let(::LocalId),
            )
    )
}
