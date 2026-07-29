package com.voxelforge.core.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Изменяемый трёхмерный вектор с плавающей точкой одинарной точности.
 *
 * Назначение: направления, нормали, скорости, цвета освещения — всё, что
 * пересчитывается каждый кадр. Класс намеренно сделан **изменяемым**: в горячих
 * циклах рендера и физики создание нового объекта на каждую операцию приводит
 * к паузам сборщика мусора, что на Android сразу видно как рывки кадров.
 * Поэтому все операции имеют вариант «в себя» (`addLocal`, `scaleLocal`).
 *
 * Для мировых позиций используется [Vec3d] с двойной точностью — на расстоянии
 * в миллионы блоков float теряет разрешение и игрока начинает «дёргать».
 */
class Vec3(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var z: Float = 0f
) {

    constructor(other: Vec3) : this(other.x, other.y, other.z)

    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x; this.y = y; this.z = z
        return this
    }

    fun set(other: Vec3): Vec3 = set(other.x, other.y, other.z)

    fun setZero(): Vec3 = set(0f, 0f, 0f)

    // --- Операции «в себя»: не аллоцируют, применяются в физике и рендере ---

    fun addLocal(x: Float, y: Float, z: Float): Vec3 {
        this.x += x; this.y += y; this.z += z
        return this
    }

    fun addLocal(o: Vec3): Vec3 = addLocal(o.x, o.y, o.z)

    fun subLocal(o: Vec3): Vec3 {
        x -= o.x; y -= o.y; z -= o.z
        return this
    }

    fun scaleLocal(s: Float): Vec3 {
        x *= s; y *= s; z *= s
        return this
    }

    /** Прибавляет [o], умноженный на [s]. Классический шаг интегрирования скорости. */
    fun addScaledLocal(o: Vec3, s: Float): Vec3 {
        x += o.x * s; y += o.y * s; z += o.z * s
        return this
    }

    /** Покомпонентное умножение — используется для тонирования света цветом. */
    fun mulLocal(o: Vec3): Vec3 {
        x *= o.x; y *= o.y; z *= o.z
        return this
    }

    fun lerpLocal(target: Vec3, t: Float): Vec3 {
        x += (target.x - x) * t
        y += (target.y - y) * t
        z += (target.z - z) * t
        return this
    }

    // --- Метрики ---

    fun lengthSquared(): Float = x * x + y * y + z * z

    fun length(): Float = sqrt(lengthSquared())

    fun distanceSquared(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return dx * dx + dy * dy + dz * dz
    }

    /**
     * Нормализация «в себя». Нулевой вектор остаётся нулевым — это осознанное
     * решение: молчаливый возврат нуля безопаснее NaN, который распространился бы
     * по всей матрице вида и привёл к чёрному экрану.
     */
    fun normalizeLocal(): Vec3 {
        val len2 = lengthSquared()
        if (len2 < 1e-12f) return this
        val inv = 1f / sqrt(len2)
        x *= inv; y *= inv; z *= inv
        return this
    }

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    /** Векторное произведение, результат записывается в [out] (может быть this). */
    fun cross(o: Vec3, out: Vec3): Vec3 {
        val nx = y * o.z - z * o.y
        val ny = z * o.x - x * o.z
        val nz = x * o.y - y * o.x
        return out.set(nx, ny, nz)
    }

    /** Копия — там, где изменяемость мешает (например, при сохранении состояния). */
    fun copy(): Vec3 = Vec3(x, y, z)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    override fun toString(): String = "Vec3($x, $y, $z)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vec3) return false
        return abs(x - other.x) < 1e-6f && abs(y - other.y) < 1e-6f && abs(z - other.z) < 1e-6f
    }

    override fun hashCode(): Int {
        var h = x.toRawBits()
        h = 31 * h + y.toRawBits()
        h = 31 * h + z.toRawBits()
        return h
    }

    companion object {
        fun up() = Vec3(0f, 1f, 0f)
        fun zero() = Vec3(0f, 0f, 0f)
    }
}

/**
 * Позиция в мировых координатах с двойной точностью.
 *
 * Назначение: хранить положение игрока и сущностей в бесконечном мире.
 * При удалении на 10 млн блоков от начала координат float32 даёт шаг сетки
 * около 1 метра — персонаж буквально телепортировался бы между блоками.
 * Double сохраняет субмиллиметровую точность на всём допустимом диапазоне мира.
 *
 * В рендер позиции попадают уже как разность с центром камеры, поэтому
 * потеря точности при переводе в float там принципиально невозможна.
 */
class Vec3d(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var z: Double = 0.0
) {

    fun set(x: Double, y: Double, z: Double): Vec3d {
        this.x = x; this.y = y; this.z = z
        return this
    }

    fun set(o: Vec3d): Vec3d = set(o.x, o.y, o.z)

    fun addLocal(dx: Double, dy: Double, dz: Double): Vec3d {
        x += dx; y += dy; z += dz
        return this
    }

    fun distanceSquared(o: Vec3d): Double {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return dx * dx + dy * dy + dz * dz
    }

    fun horizontalDistanceSquared(ox: Double, oz: Double): Double {
        val dx = x - ox; val dz = z - oz
        return dx * dx + dz * dz
    }

    /** Блочная координата, в которой находится точка (округление вниз). */
    fun blockX(): Int = MathUtils.floorInt(x)
    fun blockY(): Int = MathUtils.floorInt(y)
    fun blockZ(): Int = MathUtils.floorInt(z)

    fun copy(): Vec3d = Vec3d(x, y, z)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    override fun toString(): String = "Vec3d($x, $y, $z)"
}
