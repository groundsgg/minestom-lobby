package gg.grounds.minestom.lobby.scene

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.MetadataDef
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class DisplayTransformTest {
    @Test
    fun `prepared affine mappers retain independent roots and preserve sheared signed collapsed geometry`() {
        fun scene(x: Double) =
            SceneRenderTransform(
                transform(position = Vec3(x, 0.0, 0.0), scale = Vec3(2.0, 1.0, 1.0)),
                transform(position = Vec3(1.0, 0.0, 0.0), rotation = EulerRotation(45.0, 0.0, 0.0)),
            )
        val first = DisplayTransform.prepareModel(scene(10.0))
        val other = DisplayTransform.prepareModel(scene(30.0))
        RendererFixture().use { fixture ->
            fixture.create().get(5, TimeUnit.SECONDS).use {
                val meta =
                    fixture.display.entityMeta
                        as net.minestom.server.entity.metadata.display.AbstractDisplayMeta
                for ((mapper, root) in listOf(first to 10.0, other to 30.0, first to 10.0)) {
                    for ((step, scale) in
                        listOf(Vec3(-2.0, 3.0, 0.5), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, -3.0))
                            .withIndex()) {
                        val offset = Vec3(step.toDouble(), 2.0 * step, -step.toDouble())
                        mapper(NpcMatrix.translation(offset) * NpcMatrix.scale(scale))
                            .apply(meta, Vec3(root, 0.0, 0.0))
                        for (x in listOf(0.0, 1.0)) for (y in listOf(0.0, 1.0)) for (z in
                            listOf(0.0, 1.0)) {
                            val right = vecMul(quat(meta.rightRotation), doubleArrayOf(x, y, z))
                            val actual =
                                vecMul(
                                    quat(meta.leftRotation),
                                    doubleArrayOf(
                                        right[0] * meta.scale.x(),
                                        right[1] * meta.scale.y(),
                                        right[2] * meta.scale.z(),
                                    ),
                                )
                            val px = offset.x + x * scale.x
                            val py = offset.y + y * scale.y
                            val pz = offset.z + z * scale.z
                            val c = kotlin.math.sqrt(0.5)
                            assertNear(
                                root + 2.0 * (1.0 + c * (px + pz)),
                                root + meta.translation.x() + actual[0],
                                "prepared x",
                            )
                            assertNear(py, meta.translation.y() + actual[1], "prepared y")
                            assertNear(
                                c * (pz - px),
                                meta.translation.z() + actual[2],
                                "prepared z",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `subfloat translation and scale changes do not restart client interpolation`() {
        RendererFixture().use { fixture ->
            fixture.create(sceneTransform()).get(5, TimeUnit.SECONDS).use {
                val meta =
                    fixture.display.entityMeta
                        as net.minestom.server.entity.metadata.display.AbstractDisplayMeta
                fun pose(delta: Double) =
                    NpcMatrix.translation(Vec3(1.0 + delta, 2.0, 3.0)) *
                        NpcMatrix.scale(Vec3(1.0, 2.0 + delta, 3.0))

                assertTrue(DisplayTransform.fromModel(sceneTransform(), pose(0.0)).apply(meta))
                // These distinct doubles serialize to the same float components as the first pose.
                assertFalse(DisplayTransform.fromModel(sceneTransform(), pose(1e-10)).apply(meta))
                assertTrue(DisplayTransform.fromModel(sceneTransform(), pose(1e-5)).apply(meta))
                assertTrue(DisplayTransform.fromModel(sceneTransform(), pose(0.0)).apply(meta))
            }
        }
    }

    @Test
    fun `model display metadata preserves reflected and collapsed animated axes`() {
        val rotation = NpcMatrix.rotation(Vec3(27.0, 43.0, -19.0))
        val scales =
            listOf(
                Vec3(-1.0, -1.0, -1.0),
                Vec3(-2.0, 3.0, 0.5),
                Vec3(1.0, 0.0, 0.0),
                Vec3(0.0, 1.0, 0.0),
                Vec3(0.0, 0.0, 1.0),
                Vec3(0.0, 1.0, 1.0),
                Vec3(1.0, 0.0, 1.0),
                Vec3(1.0, 1.0, 0.0),
                Vec3(0.0, 0.0, 0.0),
            )
        for (scale in scales) {
            val model = rotation * NpcMatrix.scale(scale)
            RendererFixture().use { fixture ->
                fixture.create(sceneTransform()).get(5, TimeUnit.SECONDS).use {
                    val meta =
                        fixture.display.entityMeta
                            as net.minestom.server.entity.metadata.display.AbstractDisplayMeta
                    DisplayTransform.fromModel(sceneTransform(), model).apply(meta)
                    for (axis in 0..2) {
                        val basis = DoubleArray(3) { if (it == axis) 1.0 else 0.0 }
                        val rotated = vecMul(quat(meta.rightRotation), basis)
                        val s = meta.scale
                        val actual =
                            vecMul(
                                quat(meta.leftRotation),
                                doubleArrayOf(
                                    rotated[0] * s.x(),
                                    rotated[1] * s.y(),
                                    rotated[2] * s.z(),
                                ),
                            )
                        for (row in 0..2) assertNear(
                            model.values[row * 4 + axis],
                            actual[row],
                            "$scale axis$axis row$row",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `maps every unit-cube corner through identity transform and bounds`() {
        assertCorners(
            transform = sceneTransform(),
            bounds = LocalBounds(Vec3(4.0, 5.0, 6.0), Vec3(2.0, 4.0, 6.0)),
            expected =
                listOf(
                    Vec3(3.0, 3.0, 3.0),
                    Vec3(5.0, 3.0, 3.0),
                    Vec3(3.0, 7.0, 3.0),
                    Vec3(5.0, 7.0, 3.0),
                    Vec3(3.0, 3.0, 9.0),
                    Vec3(5.0, 3.0, 9.0),
                    Vec3(3.0, 7.0, 9.0),
                    Vec3(5.0, 7.0, 9.0),
                ),
        )
    }

    @Test
    fun `maps every unit-cube corner through a ninety-degree yaw`() {
        assertCorners(
            transform = sceneTransform(root = transform(rotation = EulerRotation(90.0, 0.0, 0.0))),
            bounds = LocalBounds(Vec3(1.0, 0.0, 2.0), Vec3(2.0, 1.0, 4.0)),
            expected =
                listOf(
                    Vec3(0.0, -0.5, 0.0),
                    Vec3(0.0, -0.5, -2.0),
                    Vec3(0.0, 0.5, 0.0),
                    Vec3(0.0, 0.5, -2.0),
                    Vec3(4.0, -0.5, 0.0),
                    Vec3(4.0, -0.5, -2.0),
                    Vec3(4.0, 0.5, 0.0),
                    Vec3(4.0, 0.5, -2.0),
                ),
        )
    }

    @Test
    fun `preserves shear from nonuniform root scale before local yaw`() {
        assertCorners(
            transform =
                SceneRenderTransform(
                    transform(position = Vec3(10.0, 0.0, 0.0), scale = Vec3(2.0, 1.0, 1.0)),
                    transform(
                        position = Vec3(1.0, 0.0, 0.0),
                        rotation = EulerRotation(45.0, 0.0, 0.0),
                    ),
                ),
            bounds = LocalBounds(Vec3(0.5, 0.5, 0.5), Vec3(1.0, 1.0, 1.0)),
            expected =
                listOf(
                    Vec3(12.0, 0.0, 0.0),
                    Vec3(13.4142135624, 0.0, -0.7071067812),
                    Vec3(12.0, 1.0, 0.0),
                    Vec3(13.4142135624, 1.0, -0.7071067812),
                    Vec3(13.4142135624, 0.0, 0.7071067812),
                    Vec3(14.8284271247, 0.0, 0.0),
                    Vec3(13.4142135624, 1.0, 0.7071067812),
                    Vec3(14.8284271247, 1.0, 0.0),
                ),
        )
    }

    private fun assertCorners(
        transform: SceneRenderTransform,
        bounds: LocalBounds,
        expected: List<Vec3>,
    ) {
        val actual =
            RendererFixture(bounds).use { fixture ->
                fixture.create(transform).get(5, TimeUnit.SECONDS).use {
                    val root = transform.root.position
                    val player = fixture.player(Pos(root.x, root.y, root.z))
                    val id = fixture.display.entityId
                    val anchor = player.connection.spawns(id).single().position()
                    val entries =
                        player.connection.packets
                            .filterIsInstance<EntityMetaDataPacket>()
                            .filter { it.entityId() == id }
                            .flatMap { it.entries().entries }
                            .associate { it.key to it.value }
                    // Interpret exactly the client-visible TRSR entries, never private
                    // decomposition fields.
                    fun value(entry: MetadataDef.Entry<*>) =
                        entries[entry.index()]?.value() ?: entry.defaultValue()
                    val t = value(MetadataDef.Display.TRANSLATION) as Point
                    val scale = value(MetadataDef.Display.SCALE) as Point
                    val left = value(MetadataDef.Display.ROTATION_LEFT) as FloatArray
                    val right = value(MetadataDef.Display.ROTATION_RIGHT) as FloatArray
                    listOf(0.0, 1.0).flatMap { z ->
                        listOf(0.0, 1.0).flatMap { y ->
                            listOf(0.0, 1.0).map { x ->
                                val v =
                                    vecMul(
                                        quat(left),
                                        vecMul(
                                            doubleArrayOf(
                                                scale.x(),
                                                0.0,
                                                0.0,
                                                0.0,
                                                scale.y(),
                                                0.0,
                                                0.0,
                                                0.0,
                                                scale.z(),
                                            ),
                                            vecMul(quat(right), doubleArrayOf(x, y, z)),
                                        ),
                                    )
                                Vec3(
                                    anchor.x() + t.x() + v[0],
                                    anchor.y() + t.y() + v[1],
                                    anchor.z() + t.z() + v[2],
                                )
                            }
                        }
                    }
                }
            }
        expected.zip(actual).forEachIndexed { index, (want, got) ->
            assertNear(want.x, got.x, "corner $index x")
            assertNear(want.y, got.y, "corner $index y")
            assertNear(want.z, got.z, "corner $index z")
        }
    }

    private fun transform(
        position: Vec3 = Vec3(0.0, 0.0, 0.0),
        rotation: EulerRotation = EulerRotation(0.0, 0.0, 0.0),
        scale: Vec3 = Vec3(1.0, 1.0, 1.0),
    ) = Transform(position, rotation, scale)

    private fun sceneTransform(root: Transform = transform()) = SceneRenderTransform(root, null)

    private fun assertNear(expected: Double, actual: Double, label: String) {
        check(abs(expected - actual) < 0.0001) { "$label: expected $expected, got $actual" }
    }

    private fun vecMul(a: DoubleArray, b: DoubleArray) =
        DoubleArray(3) { r -> (0..2).sumOf { a[r * 3 + it] * b[it] } }

    private fun quat(q: FloatArray): DoubleArray {
        val x = q[0].toDouble()
        val y = q[1].toDouble()
        val z = q[2].toDouble()
        val w = q[3].toDouble()
        return doubleArrayOf(
            1 - 2 * y * y - 2 * z * z,
            2 * x * y - 2 * z * w,
            2 * x * z + 2 * y * w,
            2 * x * y + 2 * z * w,
            1 - 2 * x * x - 2 * z * z,
            2 * y * z - 2 * x * w,
            2 * x * z - 2 * y * w,
            2 * y * z + 2 * x * w,
            1 - 2 * x * x - 2 * y * y,
        )
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun boot() {
            MinecraftServer.init()
        }
    }
}
