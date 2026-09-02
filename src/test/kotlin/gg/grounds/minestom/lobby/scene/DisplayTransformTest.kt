package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform
import kotlin.math.abs
import net.minestom.server.entity.metadata.display.BlockDisplayMeta
import org.junit.jupiter.api.Test

class DisplayTransformTest {
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
        val display = HighlightableBlockDisplay()
        display.editEntityMeta(BlockDisplayMeta::class.java) {
            DisplayTransform.from(transform, bounds).apply(it)
        }
        val meta = display.entityMeta as BlockDisplayMeta
        val t = meta.translation
        val actual =
            listOf(0.0, 1.0).flatMap { z ->
                listOf(0.0, 1.0).flatMap { y ->
                    listOf(0.0, 1.0).map { x ->
                        val v =
                            vecMul(
                                quat(meta.leftRotation),
                                vecMul(
                                    doubleArrayOf(
                                        meta.scale.x(),
                                        0.0,
                                        0.0,
                                        0.0,
                                        meta.scale.y(),
                                        0.0,
                                        0.0,
                                        0.0,
                                        meta.scale.z(),
                                    ),
                                    vecMul(quat(meta.rightRotation), doubleArrayOf(x, y, z)),
                                ),
                            )
                        Vec3(t.x() + v[0], t.y() + v[1], t.z() + v[2])
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

    private fun matMul(a: DoubleArray, b: DoubleArray) =
        DoubleArray(9) { i -> (0..2).sumOf { a[i / 3 * 3 + it] * b[it * 3 + i % 3] } }

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
}
