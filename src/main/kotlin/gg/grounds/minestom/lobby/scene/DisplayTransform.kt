package gg.grounds.minestom.lobby.scene

import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta

/** Converts scene's ordered affine transform into Minecraft display TRSR metadata. */
internal class DisplayTransform
private constructor(
    private val translation: Vec3,
    private val left: DoubleArray,
    private val scale: DoubleArray,
    private val right: DoubleArray,
) {
    fun apply(meta: AbstractDisplayMeta) {
        meta.setTranslation(Vec(translation.x, translation.y, translation.z))
        meta.setLeftRotation(quaternion(left))
        meta.setScale(Vec(scale[0], scale[1], scale[2]))
        meta.setRightRotation(quaternion(right))
    }

    internal fun unitCubeCorners(): List<Vec3> =
        listOf(0.0, 1.0).flatMap { z ->
            listOf(0.0, 1.0).flatMap { y ->
                listOf(0.0, 1.0).map { x ->
                    // Reconstruct exactly the display client's L * S * R path, rather than
                    // retaining
                    // the source affine matrix as a test-only oracle.
                    val v =
                        Matrix3.fromQuaternion(quaternion(left)) *
                            Matrix3.diagonal(scale) *
                            Matrix3.fromQuaternion(quaternion(right)) *
                            doubleArrayOf(x, y, z)
                    Vec3(translation.x + v[0], translation.y + v[1], translation.z + v[2])
                }
            }
        }

    private fun quaternion(rotation: DoubleArray): FloatArray {
        val trace = rotation[0] + rotation[4] + rotation[8]
        val (x, y, z, w) =
            if (trace > 0.0) {
                val s = sqrt(trace + 1.0) * 2.0
                arrayOf(
                    (rotation[7] - rotation[5]) / s,
                    (rotation[2] - rotation[6]) / s,
                    (rotation[3] - rotation[1]) / s,
                    s / 4.0,
                )
            } else if (rotation[0] > rotation[4] && rotation[0] > rotation[8]) {
                val s = sqrt(1.0 + rotation[0] - rotation[4] - rotation[8]) * 2.0
                arrayOf(
                    s / 4.0,
                    (rotation[1] + rotation[3]) / s,
                    (rotation[2] + rotation[6]) / s,
                    (rotation[7] - rotation[5]) / s,
                )
            } else if (rotation[4] > rotation[8]) {
                val s = sqrt(1.0 + rotation[4] - rotation[0] - rotation[8]) * 2.0
                arrayOf(
                    (rotation[1] + rotation[3]) / s,
                    s / 4.0,
                    (rotation[5] + rotation[7]) / s,
                    (rotation[2] - rotation[6]) / s,
                )
            } else {
                val s = sqrt(1.0 + rotation[8] - rotation[0] - rotation[4]) * 2.0
                arrayOf(
                    (rotation[2] + rotation[6]) / s,
                    (rotation[5] + rotation[7]) / s,
                    s / 4.0,
                    (rotation[3] - rotation[1]) / s,
                )
            }
        return floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    }

    companion object {
        fun from(transform: SceneRenderTransform, bounds: LocalBounds): DisplayTransform {
            val root = affine(transform.root)
            val local = transform.local?.let(::affine) ?: Affine.IDENTITY
            val boundsAffine =
                Affine(
                    Vec3(
                        bounds.center.x - bounds.size.x / 2,
                        bounds.center.y - bounds.size.y / 2,
                        bounds.center.z - bounds.size.z / 2,
                    ),
                    Matrix3.scale(bounds.size),
                )
            val affine = root * local * boundsAffine
            val decomposition = decompose(affine.matrix)
            return DisplayTransform(
                affine.translation,
                decomposition.left,
                decomposition.scale,
                decomposition.right,
            )
        }

        private fun affine(transform: Transform): Affine =
            Affine(transform.position, Matrix3.rotation(transform) * Matrix3.scale(transform.scale))

        private fun decompose(matrix: Matrix3): Decomposition {
            val symmetric = matrix.transpose() * matrix
            val (eigenvectors, eigenvalues) = symmetric.eigen()
            val scale = DoubleArray(3) { sqrt(max(0.0, eigenvalues[it])) }
            val right = eigenvectors.transpose()
            val left =
                matrix *
                    eigenvectors *
                    Matrix3.diagonal(scale.map { if (it == 0.0) 0.0 else 1.0 / it }.toDoubleArray())
            return Decomposition(left.orthonormalized(), scale, right.orthonormalized())
        }
    }
}

private data class Decomposition(
    val left: DoubleArray,
    val scale: DoubleArray,
    val right: DoubleArray,
)

private data class Affine(val translation: Vec3, val matrix: Matrix3) {
    operator fun times(other: Affine) =
        Affine(
            translation +
                matrix *
                    doubleArrayOf(other.translation.x, other.translation.y, other.translation.z),
            matrix * other.matrix,
        )

    companion object {
        val IDENTITY = Affine(Vec3(0.0, 0.0, 0.0), Matrix3.IDENTITY)
    }
}

private operator fun Vec3.plus(other: DoubleArray) = Vec3(x + other[0], y + other[1], z + other[2])

private class Matrix3(private val values: DoubleArray) {
    operator fun times(other: Matrix3) =
        Matrix3(
            DoubleArray(9) { i ->
                val row = i / 3
                val col = i % 3
                (0..2).sumOf { values[row * 3 + it] * other.values[it * 3 + col] }
            }
        )

    operator fun times(vector: DoubleArray) =
        DoubleArray(3) { row -> (0..2).sumOf { values[row * 3 + it] * vector[it] } }

    fun transpose() =
        Matrix3(
            doubleArrayOf(
                values[0],
                values[3],
                values[6],
                values[1],
                values[4],
                values[7],
                values[2],
                values[5],
                values[8],
            )
        )

    fun orthonormalized(): DoubleArray {
        val x = normalize(doubleArrayOf(values[0], values[3], values[6]))
        val y =
            normalize(
                subtract(
                    doubleArrayOf(values[1], values[4], values[7]),
                    scale(x, dot(x, doubleArrayOf(values[1], values[4], values[7]))),
                )
            )
        val z = cross(x, y)
        return doubleArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
    }

    fun eigen(): Pair<Matrix3, DoubleArray> {
        val a = values.copyOf()
        val v = IDENTITY.values.copyOf()
        repeat(24) {
            val pairs = arrayOf(0 to 1, 0 to 2, 1 to 2)
            val (p, q) = pairs.maxBy { abs(a[it.first * 3 + it.second]) }
            if (abs(a[p * 3 + q]) < 1e-12) return@repeat
            val angle = 0.5 * atan2(2 * a[p * 3 + q], a[q * 3 + q] - a[p * 3 + p])
            val c = cos(angle)
            val s = sin(angle)
            for (i in 0..2) {
                val ip = a[i * 3 + p]
                val iq = a[i * 3 + q]
                a[i * 3 + p] = c * ip - s * iq
                a[i * 3 + q] = s * ip + c * iq
            }
            for (i in 0..2) {
                val pi = a[p * 3 + i]
                val qi = a[q * 3 + i]
                a[p * 3 + i] = c * pi - s * qi
                a[q * 3 + i] = s * pi + c * qi
            }
            for (i in 0..2) {
                val ip = v[i * 3 + p]
                val iq = v[i * 3 + q]
                v[i * 3 + p] = c * ip - s * iq
                v[i * 3 + q] = s * ip + c * iq
            }
        }
        return Matrix3(v) to doubleArrayOf(a[0], a[4], a[8])
    }

    companion object {
        val IDENTITY = Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun diagonal(v: DoubleArray) =
            Matrix3(doubleArrayOf(v[0], 0.0, 0.0, 0.0, v[1], 0.0, 0.0, 0.0, v[2]))

        fun scale(v: Vec3) = diagonal(doubleArrayOf(v.x, v.y, v.z))

        fun rotation(t: Transform): Matrix3 {
            fun r(axis: Char, degrees: Double): Matrix3 {
                val c = cos(Math.toRadians(degrees))
                val s = sin(Math.toRadians(degrees))
                return when (axis) {
                    'x' -> Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
                    'y' -> Matrix3(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
                    else -> Matrix3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
                }
            }
            return r('y', t.rotation.yaw) * r('x', t.rotation.pitch) * r('z', t.rotation.roll)
        }

        fun fromQuaternion(q: FloatArray): Matrix3 {
            val x = q[0].toDouble()
            val y = q[1].toDouble()
            val z = q[2].toDouble()
            val w = q[3].toDouble()
            return Matrix3(
                doubleArrayOf(
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
            )
        }
    }
}

private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }

private fun scale(a: DoubleArray, s: Double) = DoubleArray(3) { a[it] * s }

private fun subtract(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }

private fun normalize(a: DoubleArray): DoubleArray {
    val n = sqrt(dot(a, a))
    return DoubleArray(3) { a[it] / n }
}

private fun cross(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
