package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.resourcepacks.npc.NpcModels
import gg.grounds.scene.format.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** Authored demo data, exported into a map sidecar; never synthesized at server startup. */
internal fun stageNpcDemoScene(input: SceneDocument, assets: AssetCatalog): SceneDocument {
    val queued = migrateGroundsQueueNpcScene(input, assets)
    val models = NpcModels.load(assets)
    val group = LocalId("npc-demo")
    data class Station(val id: String, val model: String, val title: String, val hint: String)
    val stations =
        listOf(
            Station(
                "fixed",
                "guildfolk/forge_master",
                "Fester Blick",
                "Ich behalte meine Blickrichtung",
            ),
            Station(
                "yaw",
                "townfolk/farmer",
                "Blickverfolgung horizontal",
                "Gehe seitlich an mir vorbei",
            ),
            Station(
                "tracking",
                "townfolk/fisherman",
                "Blickverfolgung räumlich",
                "Ich folge dem nächsten Spieler",
            ),
            Station(
                "hover",
                "townfolk/ragpicker",
                "Persönliches Highlight",
                "Ansehen: Umriss · Wegsehen: aus",
            ),
            Station(
                "scale",
                "craftmasters/ballistician",
                "Größe nur für dich",
                "Ansehen: wächst · Wegsehen: zurück",
            ),
            Station(
                "hands",
                "craftmasters/dismantler",
                "Links, rechts und Hände",
                "Links/Rechts: klicken · F: Nebenhand-Test",
            ),
            Station(
                "sneak",
                "townfolk/stonemason",
                "Schleichen als Bedingung",
                "Rechtsklick normal und mit Shift",
            ),
            Station(
                "proximity",
                "guildfolk/wandering_merchant",
                "Annäherung und Abschied",
                "Komm näher, gehe dann 5 Blöcke weg",
            ),
            Station(
                "animation",
                "guildfolk/verdant_spellscribe",
                "Originalanimationen",
                "Rechts: Idle2 · Links: Pause\nShift-Rechts: Idle",
            ),
            Station(
                "sound",
                "craftmasters/shipman",
                "Animation mit Originalton",
                "Rechts: Winken mit Ton\nLinks: Sound-Action",
            ),
            Station(
                "messages",
                "guildfolk/arcane_artificer",
                "Rückmeldungen und Klickschutz",
                "Rechts: 2 Sekunden Klickpause\nLinks: Klickserie bündeln (350 ms)",
            ),
            Station(
                "conditions",
                "guildfolk/crystal_prospector",
                "Spielmodus und Berechtigung",
                "Rechts: Bedingungen prüfen\nEditoren: /npctest mode",
            ),
            Station(
                "solid",
                "townfolk/builder",
                "Kollision: SOLID",
                "Versuche durch mich zu gehen",
            ),
            Station(
                "bounce",
                "craftmasters/gunner_mechanic",
                "Kollision: BOUNCE",
                "Berühre mich: 1,5 Blöcke Rückstoß",
            ),
            Station(
                "none",
                "craftmasters/jeweler",
                "Kollision: NONE",
                "Du kannst durch mich hindurchgehen",
            ),
        )
    fun message(text: String) = SendMessageAction(Component.text(text, NamedTextColor.AQUA))
    fun bar(text: String) = SendActionBarAction(Component.text(text, NamedTextColor.YELLOW))
    fun binding(
        trigger: SceneTrigger,
        actions: List<SceneAction>,
        conditions: List<SceneCondition> = emptyList(),
        cooldown: Long = 500,
        debounce: Long = 0,
    ) = TriggerBinding(trigger, conditions, cooldown, debounce, actions)
    val demos =
        stations.mapIndexed { index, station ->
            val id = LocalId("demo-${station.id}")
            val target = ElementTarget(id, null)
            val body = AssetKey("grounds:npc/${station.model}")
            val model = requireNotNull(models[body]) { "Demo model missing: $body" }
            val greeting = LocalId(if ("wave" in model.animations) "wave" else "hello")
            val bounds = model.bounds
            val bindings =
                mutableListOf(
                    binding(
                        SceneTrigger.HOVER_ENTER,
                        listOf(SetViewerHighlightAction(target, true, 0)),
                        cooldown = 0,
                    ),
                    binding(
                        SceneTrigger.HOVER_LEAVE,
                        listOf(SetViewerHighlightAction(target, false, 0)),
                        cooldown = 0,
                    ),
                )
            when (station.id) {
                "scale" -> {
                    bindings +=
                        binding(
                            SceneTrigger.HOVER_ENTER,
                            listOf(SetViewerScaleAction(target, 1.35, 400)),
                            cooldown = 0,
                        )
                    bindings +=
                        binding(
                            SceneTrigger.HOVER_LEAVE,
                            listOf(SetViewerScaleAction(target, 1.0, 400)),
                            cooldown = 0,
                        )
                }
                "hands" -> {
                    bindings +=
                        binding(SceneTrigger.LEFT_CLICK, listOf(message("Linksklick erkannt.")))
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(message("Rechtsklick mit Haupthand erkannt.")),
                            listOf(HandCondition(SceneHand.MAIN)),
                        )
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(message("Nebenhand-Bedingung erkannt (F-Test).")),
                            listOf(HandCondition(SceneHand.OFF)),
                        )
                }
                "sneak" -> {
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(
                                message("Bedingung erfüllt: Du schleichst."),
                                StartAnimationAction(target, greeting),
                            ),
                            listOf(HandCondition(SceneHand.MAIN), SneakingCondition(true)),
                        )
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(message("Bedingung erfüllt: Du stehst normal.")),
                            listOf(HandCondition(SceneHand.MAIN), SneakingCondition(false)),
                        )
                }
                "proximity" -> {
                    bindings +=
                        binding(
                            SceneTrigger.PROXIMITY_ENTER,
                            listOf(
                                StartAnimationAction(target, greeting),
                                bar("Annäherung erkannt – hallo!"),
                            ),
                            cooldown = 15000,
                        )
                    bindings +=
                        binding(
                            SceneTrigger.PROXIMITY_LEAVE,
                            listOf(bar("Austritt erkannt – bis bald!")),
                            cooldown = 1000,
                        )
                }
                "animation" -> {
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(StartAnimationAction(target, LocalId("idle2"))),
                            listOf(HandCondition(SceneHand.MAIN), SneakingCondition(false)),
                        )
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(StartAnimationAction(target, LocalId("idle"))),
                            listOf(HandCondition(SceneHand.MAIN), SneakingCondition(true)),
                        )
                    bindings +=
                        binding(
                            SceneTrigger.LEFT_CLICK,
                            listOf(
                                StopAnimationAction(target, null),
                                message("Animation angehalten. Shift-Rechts startet Idle."),
                            ),
                        )
                }
                "sound" -> {
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(StartAnimationAction(target, greeting)),
                            listOf(HandCondition(SceneHand.MAIN)),
                            cooldown = 4000,
                        )
                    val sound =
                        requireNotNull(model.sounds[greeting.value]) {
                            "Sound station requires shipped greeting audio"
                        }
                    bindings +=
                        binding(
                            SceneTrigger.LEFT_CLICK,
                            listOf(
                                PlaySoundAction(AssetKey(sound.key), sound.volume, sound.pitch),
                                message("Explizite Sound-Action abgespielt."),
                            ),
                            cooldown = 4000,
                        )
                }
                "messages" -> {
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(
                                message(
                                    "Chat-Aktion: Klick angenommen. Weitere Klicks sind 2 Sekunden gesperrt."
                                ),
                                bar("Actionbar-Aktion bestätigt"),
                                ShowTitleAction(
                                    Component.text("NPC-Demo"),
                                    Component.text("Titel-Aktion bestätigt"),
                                    200,
                                    1500,
                                    300,
                                ),
                                EmitParticleAction(
                                    target,
                                    AssetKey("grounds:scene/happy_villager"),
                                    16,
                                    Vec3(.5, 1.0, .5),
                                    .1,
                                ),
                            ),
                            listOf(HandCondition(SceneHand.MAIN)),
                            cooldown = 2000,
                            debounce = 0,
                        )
                    bindings +=
                        binding(
                            SceneTrigger.LEFT_CLICK,
                            listOf(
                                message("Klickserie gebündelt: 350 ms ohne weiteren Linksklick.")
                            ),
                            cooldown = 0,
                            debounce = 350,
                        )
                }
                "conditions" -> {
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(
                                message(
                                    "Klick erkannt. Zusätzliche Meldungen zeigen erfüllte Bedingungen."
                                )
                            ),
                            listOf(HandCondition(SceneHand.MAIN)),
                        )
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(message("Berechtigung bestätigt: grounds.scene.edit")),
                            listOf(
                                HandCondition(SceneHand.MAIN),
                                PermissionCondition("grounds.scene.edit"),
                            ),
                        )
                    for (mode in
                        listOf(SceneGameMode.ADVENTURE, SceneGameMode.CREATIVE)) bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(message("Spielmodus-Bedingung bestätigt: ${mode.name}")),
                            listOf(HandCondition(SceneHand.MAIN), GameModeCondition(mode)),
                        )
                }
                else ->
                    bindings +=
                        binding(
                            SceneTrigger.RIGHT_CLICK,
                            listOf(message("${station.title}: ${station.hint}")),
                            listOf(HandCondition(SceneHand.MAIN)),
                        )
            }
            Npc(
                id,
                group,
                Transform(
                    Vec3(-23.0 + index % 5 * 8, 173.0, -9.0 + index / 5 * 10),
                    EulerRotation(0.0, 0.0, 0.0),
                    Vec3(1.0, 1.0, 1.0),
                ),
                activation =
                    if (station.id == "fixed") ActivationPolicy.ALWAYS
                    else ActivationPolicy.AUTOMATIC,
                body = body,
                label =
                    Component.text(station.title, NamedTextColor.GOLD)
                        .appendNewline()
                        .append(Component.text(station.hint, NamedTextColor.GRAY)),
                labelOffset = Vec3(0.0, bounds.center.y + bounds.size.y / 2 + .4, 0.0),
                look =
                    when (station.id) {
                        "yaw" -> LookBehavior.TrackNearest(7.0, true, 90.0)
                        "tracking",
                        "scale" -> LookBehavior.TrackNearest(7.0, false, 90.0)
                        else -> LookBehavior.Fixed
                    },
                initialAnimation = LocalId("idle"),
                interactionBounds = bounds,
                proximity = if (station.id == "proximity") ProximitySensor(3.0, 5.0) else null,
                bindings = bindings,
                collision =
                    when (station.id) {
                        "solid" -> NpcCollision(NpcCollisionMode.SOLID)
                        "bounce" -> NpcCollision(NpcCollisionMode.BOUNCE, 6.0, 1.5)
                        else -> NpcCollision()
                    },
            )
        }
    val sensorId = LocalId("sensor-demo-entry")
    val sensor =
        Npc(
            sensorId,
            group,
            Transform(Vec3(-7.0, 173.0, 17.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            visible = false,
            body = AssetKey("grounds:editor/guide"),
            label = null,
            labelOffset = Vec3(0.0, 0.0, 0.0),
            look = LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
            proximity = ProximitySensor(2.0, 3.0),
            bindings =
                listOf(
                    binding(
                        SceneTrigger.PROXIMITY_ENTER,
                        listOf(
                            ShowTitleAction(
                                Component.text("NPC-Testplattform"),
                                Component.text("15 Modelle · /spawn zurück"),
                                200,
                                2000,
                                300,
                            ),
                            message(
                                "Unsichtbarer Annäherungssensor bestätigt. Labels erklären die Stationen. /spawn bringt dich zurück."
                            ),
                        ),
                        cooldown = 15000,
                    )
                ),
        )
    val added = demos + sensor
    val existing = queued.elements.associateBy { it.id }
    added.forEach {
        require(existing[it.id] == null || existing[it.id] == it) {
            "Conflicting demo element: ${it.id}"
        }
    }
    val demoGroup = SceneGroup(group, "NPC-Funktionsdemo")
    val existingGroup = queued.groups.singleOrNull { it.id == group }
    require(existingGroup == null || existingGroup == demoGroup) { "Conflicting demo group" }
    return SceneDocument(
            queued.schemaVersion,
            queued.id,
            queued.metadata,
            queued.catalogs,
            queued.groups + listOf(demoGroup).filter { existingGroup == null },
            queued.elements + added.filterNot { it.id in existing },
        )
        .also {
            val validation =
                SceneValidation.validateCatalogs(
                    it,
                    assets,
                    gg.grounds.lobby.scene.LobbySceneCatalogs.CURRENT,
                )
            require(validation.isValid) { "Demo failed validation: ${validation.problems}" }
        }
}

/** Explicit offline map authoring entry point. Never overwrites a sidecar. */
fun main(arguments: Array<String>) {
    require(arguments.size == 2) { "Expected existing-or-absent scene input and NEW scene output" }
    val input = Path.of(arguments[0])
    val output = Path.of(arguments[1])
    val assets = GroundsAssetCatalog.catalog
    val original =
        if (Files.exists(input)) {
            val result = SceneJson.decode(Files.readAllBytes(input))
            require(result is SceneDecodeResult.Success) { "Existing map scene is invalid" }
            result.scene
        } else
            SceneDocument(
                1,
                SceneId("grounds:lobby-mainlobby"),
                SceneMetadata(
                    "Stage-Lobby mit NPC-Funktionsdemo",
                    null,
                    setOf("stage", "npc-demo"),
                ),
                SceneCatalogReferences(
                    CatalogReference(assets.id, assets.version),
                    CatalogReference(
                        LobbySceneCatalogs.CURRENT.id,
                        LobbySceneCatalogs.CURRENT.version,
                    ),
                ),
                emptyList(),
                emptyList(),
            )
    val scene = stageNpcDemoScene(original, assets)
    val encoded = SceneJson.encode(scene)
    require(encoded is SceneEncodeResult.Success) { "Demo encoding failed" }
    Files.write(output, encoded.bytes, CREATE_NEW)
    println("Generated ${scene.elements.size} scene elements with asset catalog ${assets.version}")
}
