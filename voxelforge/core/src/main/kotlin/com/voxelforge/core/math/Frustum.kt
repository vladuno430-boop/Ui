package com.voxelforge.core.math

/**
 * Пирамида видимости камеры — шесть плоскостей, извлечённых из матрицы
 * «проекция × вид».
 *
 * Назначение: frustum culling. Это самая выгодная оптимизация воксельного
 * рендера: при дальности 12 чанков в загруженном мире около 2000 секций,
 * а в кадр при 70° обзора попадает примерно четверть. Отсечение убирает
 * ~75 % вызовов отрисовки ценой шести скалярных произведений на секцию.
 *
 * Метод извлечения — алгоритм Грибба–Хартманна: строки матрицы комбинируются
 * так, что сразу дают уравнения плоскостей в мировом пространстве, без
 * обращения матрицы.
 *
 * Плоскости нормализованы, поэтому подстановка точки даёт **знаковое
 * расстояние** до неё — это используется для «мягкой» проверки с запасом
 * (см. [isBoxVisible] с параметром margin).
 */
class Frustum {

    /** 6 плоскостей × 4 коэффициента (a, b, c, d) уравнения ax+by+cz+d=0. */
    private val planes = FloatArray(6 * 4)

    /**
     * Пересчитывает плоскости из комбинированной матрицы.
     * Вызывается один раз за кадр, до обхода списка секций.
     */
    fun setFromMatrix(viewProj: Mat4) {
        val m = viewProj.m
        // Матрица column-major: m[col*4+row].
        // Обозначим строки: r0 = (m0, m4, m8,  m12), r1 = (m1, m5, m9,  m13),
        //                   r2 = (m2, m6, m10, m14), r3 = (m3, m7, m11, m15)

        // Левая плоскость: r3 + r0
        setPlane(0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12])
        // Правая: r3 - r0
        setPlane(1, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12])
        // Нижняя: r3 + r1
        setPlane(2, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13])
        // Верхняя: r3 - r1
        setPlane(3, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13])
        // Ближняя: r3 + r2
        setPlane(4, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14])
        // Дальняя: r3 - r2
        setPlane(5, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14])
    }

    private fun setPlane(index: Int, a: Float, b: Float, c: Float, d: Float) {
        val invLen = MathUtils.fastInverseSqrt(a * a + b * b + c * c)
        val o = index * 4
        planes[o] = a * invLen
        planes[o + 1] = b * invLen
        planes[o + 2] = c * invLen
        planes[o + 3] = d * invLen
    }

    /**
     * Проверка видимости коробки методом «положительной вершины».
     *
     * Для каждой плоскости берётся та вершина коробки, которая дальше всех
     * по нормали. Если даже она позади плоскости — коробка целиком снаружи,
     * и остальные плоскости можно не проверять. Это даёт ранний выход
     * примерно на второй плоскости для большинства отсечённых секций.
     *
     * Координаты — float и **относительно камеры**: так их точность не зависит
     * от удаления мира от начала координат.
     *
     * @param margin запас в блоках; секции проверяются чуть увеличенными,
     *               чтобы объекты, выходящие за геометрические границы
     *               (колышущаяся листва, частицы), не мигали на краю экрана
     */
    fun isBoxVisible(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        margin: Float = 0f
    ): Boolean {
        val x0 = minX - margin; val y0 = minY - margin; val z0 = minZ - margin
        val x1 = maxX + margin; val y1 = maxY + margin; val z1 = maxZ + margin

        for (i in 0 until 6) {
            val o = i * 4
            val a = planes[o]; val b = planes[o + 1]; val c = planes[o + 2]; val d = planes[o + 3]
            // Положительная вершина: по каждой оси берём границу, дающую больший вклад.
            val px = if (a >= 0f) x1 else x0
            val py = if (b >= 0f) y1 else y0
            val pz = if (c >= 0f) z1 else z0
            if (a * px + b * py + c * pz + d < 0f) return false
        }
        return true
    }

    /**
     * Проверка сферы — дешевле коробки (одно скалярное произведение на плоскость).
     * Применяется для частиц и облаков, где точность отсечения не важна.
     */
    fun isSphereVisible(cx: Float, cy: Float, cz: Float, radius: Float): Boolean {
        for (i in 0 until 6) {
            val o = i * 4
            if (planes[o] * cx + planes[o + 1] * cy + planes[o + 2] * cz + planes[o + 3] < -radius) {
                return false
            }
        }
        return true
    }
}
