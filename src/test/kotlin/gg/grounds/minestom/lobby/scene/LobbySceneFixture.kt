package gg.grounds.minestom.lobby.scene

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import net.kyori.adventure.text.Component

internal fun lobbySceneFixture(
    assets: AssetCatalog,
    npcPosition: Vec3,
    markerPosition: Vec3,
): SceneDocument {
    val npcId = LocalId("navigator")
    val target = ElementTarget(npcId, null)
    val body = AssetKey("grounds:editor/guide")
    fun at(position: Vec3) = Transform(position, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0))
    val npc =
        Npc(
            id = npcId,
            group = null,
            transform = at(npcPosition),
            body = body,
            label = Component.text("Lobby-Navigator"),
            labelOffset = Vec3(0.0, 2.1, 0.0),
            look = LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds = LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6)),
            proximity = null,
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
                        listOf(ApplicationAction(LobbySceneCatalogs.OPEN_NAVIGATOR, emptyMap())),
                    ),
                ),
        )
    return SceneDocument(
        1,
        SceneId("grounds:lobby-stage"),
        SceneMetadata("Lobby Stage walkthrough", null, emptySet()),
        SceneCatalogReferences(
            CatalogReference(assets.id, assets.version),
            CatalogReference(LobbySceneCatalogs.CURRENT.id, LobbySceneCatalogs.CURRENT.version),
        ),
        emptyList(),
        listOf(
            npc,
            Prop(
                LocalId("marker"),
                null,
                at(markerPosition),
                asset = AssetKey("grounds:editor/marker"),
                initialAnimation = null,
            ),
        ),
    )
}

internal fun SceneDocument.with(
    catalogs: SceneCatalogReferences = this.catalogs,
    elements: List<SceneElement> = this.elements,
) = SceneDocument(schemaVersion, id, metadata, catalogs, groups, elements)

internal fun Npc.with(bindings: List<TriggerBinding> = this.bindings) =
    Npc(
        id,
        group,
        transform,
        visible,
        activation,
        body,
        label,
        labelOffset,
        look,
        initialAnimation,
        interactionBounds,
        proximity,
        bindings,
    )

internal fun generateLobbySceneFixture(
    projectDirectory: Path,
    filename: String,
    npcPosition: Vec3,
    markerPosition: Vec3,
): Path {
    require(filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.json"))) {
        "Use a new JSON filename, not a path"
    }
    val scene = lobbySceneFixture(GroundsAssetCatalog.catalog, npcPosition, markerPosition)
    val validation =
        SceneValidation.validateCatalogs(
            scene,
            GroundsAssetCatalog.catalog,
            LobbySceneCatalogs.CURRENT,
        )
    check(validation.isValid) { "Fixture failed catalog validation: ${validation.problems}" }
    val encoded = SceneJson.encode(scene)
    check(encoded is SceneEncodeResult.Success) { "Fixture failed encoding: $encoded" }
    // Only test/build output is ever created; no map, spawn, or live sidecar is read or modified.
    val build = projectDirectory.resolve("build")
    val fixtures = build.resolve("fixtures")
    for (directory in listOf(build, fixtures)) {
        try {
            Files.createDirectory(directory)
        } catch (_: FileAlreadyExistsException) {}
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            "Fixture output directory must not be a symlink: $directory"
        }
    }
    val destination = fixtures.resolve(filename)
    Files.write(destination, encoded.bytes, CREATE_NEW, WRITE)
    return destination
}

fun main(arguments: Array<String>) {
    require(arguments.size == 3) { "Expected filename.json npcX,npcY,npcZ markerX,markerY,markerZ" }
    fun position(value: String): Vec3 {
        val parts = value.split(',')
        require(parts.size == 3) { "Every position requires three explicit coordinates" }
        return Vec3(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
    }
    val output =
        generateLobbySceneFixture(
            Path.of("").toAbsolutePath(),
            arguments[0],
            position(arguments[1]),
            position(arguments[2]),
        )
    println("Generated test fixture: $output")
}
