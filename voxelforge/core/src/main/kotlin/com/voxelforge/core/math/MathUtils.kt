package com.voxelforge.core.math

import kotlin.math.abs
import kotlin.math.floor

/**
 * Набор скалярных математических утилит, используемых по всему движку.
 *
 * Назначение: собрать в одном месте операции, которые вызываются миллионы раз
 * за кадр (интерполяции, зажимы, целочисленное деление с округлением вниз),
 * чтобы они были инлайновыми и не аллоцировали объектов.
 *
 * Все функции помечены `inline` — JIT на ARM разворачивает их в пару инструкций,
 * что критично для мешера и генератора мира.
 */
object MathUtils {

    const val PI = 3.14159265358979323846f
    const val TWO_PI = PI * 2f
    const val DEG_TO_RAD = PI / 180f
    const val RAD_TO_DEG = 180f / PI

    /** Линейная интерполяция между [a] и [b] по параметру [t] в диапазоне 0..1. */
    inline fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Двойная точность — нужна для позиций игрока и плавных переходов времени суток. */
    inline fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /**
     * Кубическая сглаживающая кривая Кена Перлина 3t²-2t³.
     * Даёт непрерывную первую производную — используется при смешивании биомов,
     * чтобы на границе леса и пустыни не было ступеньки в высоте рельефа.
     */
    inline fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    /**
     * Квинтическая кривая 6t⁵-15t⁴+10t³ — непрерывна и по второй производной.
     * Применяется в шуме: убирает видимые «полосы» на пологих склонах.
     */
    inline fun smootherStep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    /** Плавный переход от 0 к 1 при движении [x] от [edge0] к [edge1]. */
    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    inline fun clamp(v: Float, min: Float, max: Float): Float = if (v < min) min else if (v > max) max else v
    inline fun clamp(v: Double, min: Double, max: Double): Double = if (v < min) min else if (v > max) max else v
    inline fun clamp(v: Int, min: Int, max: Int): Int = if (v < min) min else if (v > max) max else v

    /** Приводит значение из диапазона [inMin]..[inMax] в 0..1 с зажимом на краях. */
    fun inverseLerpClamped(inMin: Float, inMax: Float, v: Float): Float {
        if (inMax == inMin) return 0f
        return clamp((v - inMin) / (inMax - inMin), 0f, 1f)
    }

    /**
     * Целочисленное округление вниз. Обычный `toInt()` в Kotlin округляет к нулю,
     * из-за чего блок с координатой -0.5 попадал бы в клетку 0, а не -1.
     * Ошибка такого рода ломает физику ровно на отрицательной половине мира,
     * поэтому используется везде при переводе мировых координат в блочные.
     */
    inline fun floorInt(v: Float): Int {
        val i = v.toInt()
        return if (v < i) i - 1 else i
    }

    inline fun floorInt(v: Double): Int {
        val i = v.toInt()
        return if (v < i) i - 1 else i
    }

    /**
     * Деление с округлением вниз для отрицательных чисел.
     * Нужно для перевода блочной координаты в координату чанка:
     * блок -1 должен принадлежать чанку -1, а не 0.
     */
    inline fun floorDiv(a: Int, b: Int): Int {
        var q = a / b
        if ((a xor b) < 0 && q * b != a) q--
        return q
    }

    /** Остаток, всегда неотрицательный (для локальных координат внутри чанка). */
    inline fun floorMod(a: Int, b: Int): Int {
        val r = a % b
        return if (r < 0) r + b else r
    }

    /** Возвращает true, если [v] — степень двойки (проверка размеров чанка/атласа). */
    fun isPowerOfTwo(v: Int): Boolean = v > 0 && (v and (v - 1)) == 0

    /**
     * Быстрый обратный корень достаточной точности для нормализации векторов
     * освещения. Один шаг Ньютона даёт ошибку < 0.2 %, чего с запасом хватает
     * для затенения, но экономит деление на каждой вершине.
     */
    fun fastInverseSqrt(x: Float): Float {
        if (x <= 0f) return 0f
        val half = 0.5f * x
        var i = java.lang.Float.floatToRawIntBits(x)
        i = 0x5f3759df - (i shr 1)
        var y = java.lang.Float.intBitsToFloat(i)
        y *= (1.5f - half * y * y)
        y *= (1.5f - half * y * y)
        return y
    }

    /** Сравнение с допуском — для тестов и проверок вырожденных матриц. */
    fun approximately(a: Float, b: Float, eps: Float = 1e-5f): Boolean = abs(a - b) <= eps

    /**
     * Периодическая функция «треугольник» в диапазоне 0..1 с периодом 1.
     * Используется для колебаний воды и покачивания листвы во времени.
     */
    fun triangleWave(t: Float): Float {
        val f = t - floor(t)
        return if (f < 0.5f) f * 2f else (1f - f) * 2f
    }

    /**
     * Кратчайшая разница между двумя углами в радианах (−π..π).
     * Нужна для сглаживания поворота камеры при переходе через 360°.
     */
    fun angleDelta(from: Float, to: Float): Float {
        var d = (to - from) % TWO_PI
        if (d > PI) d -= TWO_PI
        if (d < -PI) d += TWO_PI
        return d
    }
}
