package com.voxelforge.core.math

import kotlin.math.max
import kotlin.math.min

/**
 * Осепараллельный ограничивающий параллелепипед (Axis-Aligned Bounding Box).
 *
 * Назначение: два независимых потребителя —
 *  1. Физика: коллайдер игрока и коллайдеры блоков. Мир состоит из кубов,
 *     поэтому AABB здесь не приближение, а точная форма — дешёвая и корректная.
 *  2. Рендер: границы секции чанка для отсечения по пирамиде видимости.
 *
 * Класс изменяемый по той же причине, что и [Vec3]: физика игрока за кадр
 * проверяет десятки блоков по трём осям, и аллокация на каждую проверку
 * создавала бы заметный мусор.
 */
class AABB(
    @JvmField var minX: Double = 0.0,
    @JvmField var minY: Double = 0.0,
    @JvmField var minZ: Double = 0.0,
    @JvmField var maxX: Double = 0.0,
    @JvmField var maxY: Double = 0.0,
    @JvmField var maxZ: Double = 0.0
) {

    fun set(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double
    ): AABB {
        this.minX = minX; this.minY = minY; this.minZ = minZ
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ
        return this
    }

    fun set(o: AABB): AABB = set(o.minX, o.minY, o.minZ, o.maxX, o.maxY, o.maxZ)

    /**
     * Задаёт коробку по центру основания и габаритам — так удобнее описывать
     * персонажа: точка позиции у него в ногах, а не в геометрическом центре.
     */
    fun setFromFeet(x: Double, y: Double, z: Double, width: Double, height: Double): AABB {
        val h = width * 0.5
        return set(x - h, y, z - h, x + h, y + height, z + h)
    }

    /** Устанавливает коробку единичного блока с целочисленными координатами. */
    fun setBlock(bx: Int, by: Int, bz: Int): AABB =
        set(bx.toDouble(), by.toDouble(), bz.toDouble(), bx + 1.0, by + 1.0, bz + 1.0)

    /** Устанавливает коробку блока с нестандартной высотой (плита, снег, вода). */
    fun setBlock(bx: Int, by: Int, bz: Int, height: Double): AABB =
        set(bx.toDouble(), by.toDouble(), bz.toDouble(), bx + 1.0, by + height, bz + 1.0)

    fun translate(dx: Double, dy: Double, dz: Double): AABB {
        minX += dx; maxX += dx
        minY += dy; maxY += dy
        minZ += dz; maxZ += dz
        return this
    }

    /**
     * Расширяет коробку по направлению движения. Используется для получения
     * «заметённого» объёма за кадр: собрав все блоки внутри него, мы гарантированно
     * не пропустим ни одного потенциального столкновения.
     */
    fun expandTowards(dx: Double, dy: Double, dz: Double, out: AABB): AABB {
        out.set(this)
        if (dx < 0) out.minX += dx else out.maxX += dx
        if (dy < 0) out.minY += dy else out.maxY += dy
        if (dz < 0) out.minZ += dz else out.maxZ += dz
        return out
    }

    /** Раздувает коробку во все стороны на заданную величину (skin width). */
    fun grow(amount: Double): AABB {
        minX -= amount; minY -= amount; minZ -= amount
        maxX += amount; maxY += amount; maxZ += amount
        return this
    }

    /**
     * Пересечение объёмов. Границы считаются открытыми (строгое неравенство):
     * коробка, стоящая ровно на другой, столкновением не считается, иначе
     * игрок «залипал» бы, стоя на полу.
     */
    fun intersects(o: AABB): Boolean =
        minX < o.maxX && maxX > o.minX &&
            minY < o.maxY && maxY > o.minY &&
            minZ < o.maxZ && maxZ > o.minZ

    fun contains(x: Double, y: Double, z: Double): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    /**
     * Ограничивает перемещение по оси X так, чтобы не произошло проникновения
     * в коробку [o]. Возвращает скорректированную величину смещения.
     *
     * Это ядро разрешения коллизий: вместо «сдвинули и вытолкнули» мы заранее
     * укорачиваем шаг. Такой подход не даёт туннелирования и не выбрасывает
     * игрока из геометрии при высокой скорости падения.
     */
    fun clipXCollide(o: AABB, offset: Double): Double {
        // Проверяем перекрытие по двум другим осям: если его нет, коробки
        // не могут столкнуться при движении вдоль X.
        if (o.maxY <= minY || o.minY >= maxY) return offset
        if (o.maxZ <= minZ || o.minZ >= maxZ) return offset
        if (offset > 0.0 && o.maxX <= minX) {
            val d = minX - o.maxX
            if (d < offset) return d
        }
        if (offset < 0.0 && o.minX >= maxX) {
            val d = maxX - o.minX
            if (d > offset) return d
        }
        return offset
    }

    fun clipYCollide(o: AABB, offset: Double): Double {
        if (o.maxX <= minX || o.minX >= maxX) return offset
        if (o.maxZ <= minZ || o.minZ >= maxZ) return offset
        if (offset > 0.0 && o.maxY <= minY) {
            val d = minY - o.maxY
            if (d < offset) return d
        }
        if (offset < 0.0 && o.minY >= maxY) {
            val d = maxY - o.minY
            if (d > offset) return d
        }
        return offset
    }

    fun clipZCollide(o: AABB, offset: Double): Double {
        if (o.maxX <= minX || o.minX >= maxX) return offset
        if (o.maxY <= minY || o.minY >= maxY) return offset
        if (offset > 0.0 && o.maxZ <= minZ) {
            val d = minZ - o.maxZ
            if (d < offset) return d
        }
        if (offset < 0.0 && o.minZ >= maxZ) {
            val d = maxZ - o.minZ
            if (d > offset) return d
        }
        return offset
    }

    fun centerX(): Double = (minX + maxX) * 0.5
    fun centerY(): Double = (minY + maxY) * 0.5
    fun centerZ(): Double = (minZ + maxZ) * 0.5

    fun copy(): AABB = AABB(minX, minY, minZ, maxX, maxY, maxZ)

    /** Объединение — пригождается при расчёте границ меша секции. */
    fun encapsulate(o: AABB): AABB {
        minX = min(minX, o.minX); minY = min(minY, o.minY); minZ = min(minZ, o.minZ)
        maxX = max(maxX, o.maxX); maxY = max(maxY, o.maxY); maxZ = max(maxZ, o.maxZ)
        return this
    }

    override fun toString(): String =
        "AABB[($minX,$minY,$minZ)..($maxX,$maxY,$maxZ)]"
}
