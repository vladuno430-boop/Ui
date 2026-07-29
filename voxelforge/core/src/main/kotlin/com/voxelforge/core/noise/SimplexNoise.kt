package com.voxelforge.core.noise

import com.voxelforge.core.math.MathUtils

/**
 * Симплекс-шум — базовый источник когерентного шума для генерации мира.
 *
 * Назначение: даёт гладкую псевдослучайную функцию от координат. На её основе
 * строится всё: высота рельефа, влажность и температура биомов, пещеры, руда,
 * форма облаков.
 *
 * Почему симплекс, а не классический Перлин:
 *  1. В 3D он делает 4 вычисления вклада вместо 8 интерполяций — примерно
 *     вдвое быстрее, а пещеры требуют именно 3D-шума на каждый воксель.
 *  2. У него нет направленных артефактов вдоль осей: у Перлина на плоском
 *     рельефе видна прямоугольная «сетка», которая в воксельном мире особенно
 *     бросается в глаза, потому что рельеф ступенчатый.
 *  3. Градиент непрерывен, поэтому склоны получаются без изломов.
 *
 * Реализация написана с нуля по описанию алгоритма (симплициальная решётка,
 * скос координат, радиальное затухание вкладов). Таблица перестановок
 * строится из сида перемешиванием Фишера–Йетса, поэтому разные миры
 * действительно разные, а не сдвинутые копии одного.
 *
 * Экземпляр **потокобезопасен на чтение**: после конструктора состояние
 * не меняется, поэтому один объект шума спокойно используют все воркеры
 * генерации чанков одновременно.
 */
class SimplexNoise(seed: Long) {

    /**
     * Удвоенная таблица перестановок. Дублирование избавляет от операции
     * взятия остатка при индексации — вместо `perm[(i + 1) and 255]`
     * можно писать `perm[i + 1]`, что в 3D-шуме экономит 8 операций на вызов.
     */
    private val perm = IntArray(512)

    /** Предвычисленный индекс градиента (perm[i] % 12) — ещё одно деление долой. */
    private val permMod12 = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rng = XorShiftRandom(seed)
        // Перемешивание Фишера–Йетса: даёт равномерную случайную перестановку.
        for (i in 255 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = p[i]
            p[i] = p[j]
            p[j] = tmp
        }
        for (i in 0 until 512) {
            val v = p[i and 255]
            perm[i] = v
            permMod12[i] = v % 12
        }
    }

    /**
     * Двумерный симплекс-шум в диапазоне примерно [-1, 1].
     *
     * Используется там, где значение зависит только от горизонтали:
     * высота рельефа, карты температуры и влажности, русла рек.
     */
    fun noise2D(xin: Float, yin: Float): Float {
        // Скос входных координат: переводим квадратную решётку в симплициальную,
        // где базовая фигура — равносторонний треугольник.
        val s = (xin + yin) * F2
        val i = MathUtils.floorInt(xin + s)
        val j = MathUtils.floorInt(yin + s)

        val t = (i + j) * G2
        val x0 = xin - (i - t)   // смещение от вершины симплекса в обычных координатах
        val y0 = yin - (j - t)

        // Определяем, в каком из двух треугольников ячейки мы находимся.
        val i1: Int; val j1: Int
        if (x0 > y0) { i1 = 1; j1 = 0 } else { i1 = 0; j1 = 1 }

        val x1 = x0 - i1 + G2
        val y1 = y0 - j1 + G2
        val x2 = x0 - 1f + 2f * G2
        val y2 = y0 - 1f + 2f * G2

        val ii = i and 255
        val jj = j and 255
        val gi0 = permMod12[ii + perm[jj]]
        val gi1 = permMod12[ii + i1 + perm[jj + j1]]
        val gi2 = permMod12[ii + 1 + perm[jj + 1]]

        // Вклад каждой из трёх вершин с радиальным затуханием (0.5 - r²)⁴.
        var n = 0f
        var t0 = 0.5f - x0 * x0 - y0 * y0
        if (t0 > 0f) {
            t0 *= t0
            n += t0 * t0 * dot2(gi0, x0, y0)
        }
        var t1 = 0.5f - x1 * x1 - y1 * y1
        if (t1 > 0f) {
            t1 *= t1
            n += t1 * t1 * dot2(gi1, x1, y1)
        }
        var t2 = 0.5f - x2 * x2 - y2 * y2
        if (t2 > 0f) {
            t2 *= t2
            n += t2 * t2 * dot2(gi2, x2, y2)
        }
        // Масштаб подобран так, чтобы результат укладывался в [-1, 1].
        return 70f * n
    }

    /**
     * Трёхмерный симплекс-шум в диапазоне примерно [-1, 1].
     *
     * Используется для пещер, «нависаний» рельефа и объёмной формы облаков —
     * везде, где нужна зависимость от высоты, а не только от плоскости.
     */
    fun noise3D(xin: Float, yin: Float, zin: Float): Float {
        val s = (xin + yin + zin) * F3
        val i = MathUtils.floorInt(xin + s)
        val j = MathUtils.floorInt(yin + s)
        val k = MathUtils.floorInt(zin + s)

        val t = (i + j + k) * G3
        val x0 = xin - (i - t)
        val y0 = yin - (j - t)
        val z0 = zin - (k - t)

        // Ранжируем координаты, чтобы понять, через какие из шести тетраэдров
        // ячейки проходит точка. Это шесть сравнений вместо ветвлений по таблице.
        val i1: Int; val j1: Int; val k1: Int
        val i2: Int; val j2: Int; val k2: Int
        if (x0 >= y0) {
            if (y0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0 }
            else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1 }
            else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1 }
        } else {
            if (y0 < z0) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1 }
            else if (x0 < z0) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1 }
            else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0 }
        }

        val x1 = x0 - i1 + G3;  val y1 = y0 - j1 + G3;  val z1 = z0 - k1 + G3
        val x2 = x0 - i2 + 2f * G3; val y2 = y0 - j2 + 2f * G3; val z2 = z0 - k2 + 2f * G3
        val x3 = x0 - 1f + 3f * G3; val y3 = y0 - 1f + 3f * G3; val z3 = z0 - 1f + 3f * G3

        val ii = i and 255
        val jj = j and 255
        val kk = k and 255
        val gi0 = permMod12[ii + perm[jj + perm[kk]]]
        val gi1 = permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]]
        val gi2 = permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]]
        val gi3 = permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]]

        var n = 0f
        var t0 = 0.6f - x0 * x0 - y0 * y0 - z0 * z0
        if (t0 > 0f) { t0 *= t0; n += t0 * t0 * dot3(gi0, x0, y0, z0) }
        var t1 = 0.6f - x1 * x1 - y1 * y1 - z1 * z1
        if (t1 > 0f) { t1 *= t1; n += t1 * t1 * dot3(gi1, x1, y1, z1) }
        var t2 = 0.6f - x2 * x2 - y2 * y2 - z2 * z2
        if (t2 > 0f) { t2 *= t2; n += t2 * t2 * dot3(gi2, x2, y2, z2) }
        var t3 = 0.6f - x3 * x3 - y3 * y3 - z3 * z3
        if (t3 > 0f) { t3 *= t3; n += t3 * t3 * dot3(gi3, x3, y3, z3) }

        return 32f * n
    }

    /** Шум, нормализованный в [0, 1] — удобно для порогов и вероятностей. */
    fun noise2D01(x: Float, y: Float): Float = noise2D(x, y) * 0.5f + 0.5f

    fun noise3D01(x: Float, y: Float, z: Float): Float = noise3D(x, y, z) * 0.5f + 0.5f

    private inline fun dot2(gi: Int, x: Float, y: Float): Float =
        GRAD_X[gi] * x + GRAD_Y[gi] * y

    private inline fun dot3(gi: Int, x: Float, y: Float, z: Float): Float =
        GRAD_X[gi] * x + GRAD_Y[gi] * y + GRAD_Z[gi] * z

    companion object {
        // Коэффициенты скоса/раскоса симплициальной решётки.
        private val F2 = (0.5f * (Math.sqrt(3.0) - 1.0)).toFloat()
        private val G2 = ((3.0 - Math.sqrt(3.0)) / 6.0).toFloat()
        private const val F3 = 1f / 3f
        private const val G3 = 1f / 6f

        /*
         * 12 градиентов — рёбра куба, направленные к серединам граней.
         * Хранятся тремя параллельными массивами, а не массивом объектов:
         * так они укладываются в кэш линейно и не требуют разыменования.
         */
        private val GRAD_X = floatArrayOf(1f, -1f, 1f, -1f, 1f, -1f, 1f, -1f, 0f, 0f, 0f, 0f)
        private val GRAD_Y = floatArrayOf(1f, 1f, -1f, -1f, 0f, 0f, 0f, 0f, 1f, -1f, 1f, -1f)
        private val GRAD_Z = floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, -1f)
    }
}
