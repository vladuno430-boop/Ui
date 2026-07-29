package com.voxelforge.core.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Матрица 4×4 в column-major раскладке — той же, что ожидает OpenGL ES.
 *
 * Назначение: матрицы проекции, вида и модели; расчёт матрицы света для
 * карты теней. Раскладка выбрана не случайно: при column-major массив можно
 * отдать в `glUniformMatrix4fv` с `transpose=false` без единого копирования,
 * тогда как row-major потребовал бы транспонирования каждый кадр.
 *
 * Элементы хранятся в открытом массиве [m] — он напрямую загружается в GL,
 * поэтому обёртки-геттеры были бы лишним слоем в самом горячем месте.
 *
 * Индексация: m[column * 4 + row].
 */
class Mat4 {

    @JvmField
    val m = FloatArray(16)

    init {
        setIdentity()
    }

    fun setIdentity(): Mat4 {
        java.util.Arrays.fill(m, 0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    fun set(o: Mat4): Mat4 {
        System.arraycopy(o.m, 0, m, 0, 16)
        return this
    }

    /**
     * Перспективная проекция с right-handed системой координат и глубиной −1..1.
     *
     * @param fovYDeg вертикальный угол обзора в градусах
     * @param aspect  отношение ширины экрана к высоте
     * @param near    ближняя плоскость; на мобильных не стоит опускать ниже 0.05,
     *                иначе точности depth-буфера не хватит и появится z-fighting
     *                на дальних чанках
     * @param far     дальняя плоскость — согласуется с дальностью прорисовки
     */
    fun setPerspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): Mat4 {
        val f = 1f / tan(fovYDeg * MathUtils.DEG_TO_RAD * 0.5f)
        java.util.Arrays.fill(m, 0f)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = (2f * far * near) / (near - far)
        return this
    }

    /**
     * Ортографическая проекция. Используется для карты теней (солнце —
     * направленный источник, у него нет перспективы) и для отрисовки HUD.
     */
    fun setOrtho(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): Mat4 {
        java.util.Arrays.fill(m, 0f)
        m[0] = 2f / (right - left)
        m[5] = 2f / (top - bottom)
        m[10] = -2f / (far - near)
        m[12] = -(right + left) / (right - left)
        m[13] = -(top + bottom) / (top - bottom)
        m[14] = -(far + near) / (far - near)
        m[15] = 1f
        return this
    }

    /**
     * Матрица вида по позиции камеры, точке взгляда и вектору «вверх».
     * Вырожденный случай (взгляд строго вдоль up) обрабатывается сдвигом up,
     * иначе матрица содержала бы NaN и кадр стал бы чёрным.
     */
    fun setLookAt(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        cx: Float, cy: Float, cz: Float,
        upX: Float, upY: Float, upZ: Float
    ): Mat4 {
        var fx = cx - eyeX; var fy = cy - eyeY; var fz = cz - eyeZ
        val flen = MathUtils.fastInverseSqrt(fx * fx + fy * fy + fz * fz)
        fx *= flen; fy *= flen; fz *= flen

        // s = f × up
        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        var slen2 = sx * sx + sy * sy + sz * sz
        if (slen2 < 1e-9f) {
            // Взгляд совпал с up — подменяем up на другую ось, чтобы базис остался ортонормированным.
            sx = fy * 0f - fz * 1f
            sy = fz * 0f - fx * 0f
            sz = fx * 1f - fy * 0f
            slen2 = sx * sx + sy * sy + sz * sz
            if (slen2 < 1e-9f) { sx = 1f; sy = 0f; sz = 0f; slen2 = 1f }
        }
        val sinv = MathUtils.fastInverseSqrt(slen2)
        sx *= sinv; sy *= sinv; sz *= sinv

        // u = s × f
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        m[0] = sx; m[4] = sy; m[8] = sz; m[12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        m[1] = ux; m[5] = uy; m[9] = uz; m[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = (fx * eyeX + fy * eyeY + fz * eyeZ)
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        return this
    }

    /**
     * Матрица вида из позиции и углов Эйлера (yaw/pitch) — так удобнее для
     * камеры от первого лица, где направление задаётся свайпом по экрану.
     * Roll не поддерживается сознательно: в шутере от первого лица он не нужен,
     * а его отсутствие экономит два тригонометрических вызова на кадр.
     */
    fun setFirstPersonView(eyeX: Float, eyeY: Float, eyeZ: Float, yawRad: Float, pitchRad: Float): Mat4 {
        val cp = cos(pitchRad); val sp = sin(pitchRad)
        val cy = cos(yawRad); val sy = sin(yawRad)
        // Направление взгляда в правой системе координат: yaw=0 смотрит вдоль -Z.
        val fx = -sy * cp
        val fy = sp
        val fz = -cy * cp
        return setLookAt(eyeX, eyeY, eyeZ, eyeX + fx, eyeY + fy, eyeZ + fz, 0f, 1f, 0f)
    }

    /** this = this × other. Результат — сначала применяется other, затем this. */
    fun multiply(o: Mat4): Mat4 = multiply(this, o, this)

    fun setTranslation(x: Float, y: Float, z: Float): Mat4 {
        setIdentity()
        m[12] = x; m[13] = y; m[14] = z
        return this
    }

    fun setScale(x: Float, y: Float, z: Float): Mat4 {
        setIdentity()
        m[0] = x; m[5] = y; m[10] = z
        return this
    }

    /** Домножает текущую матрицу на перенос справа (локальный сдвиг). */
    fun translate(x: Float, y: Float, z: Float): Mat4 {
        m[12] += m[0] * x + m[4] * y + m[8] * z
        m[13] += m[1] * x + m[5] * y + m[9] * z
        m[14] += m[2] * x + m[6] * y + m[10] * z
        m[15] += m[3] * x + m[7] * y + m[11] * z
        return this
    }

    /** Преобразует точку (w=1). Результат пишется в [out] как xyz. */
    fun transformPoint(x: Float, y: Float, z: Float, out: Vec3): Vec3 {
        val ox = m[0] * x + m[4] * y + m[8] * z + m[12]
        val oy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val oz = m[2] * x + m[6] * y + m[10] * z + m[14]
        return out.set(ox, oy, oz)
    }

    /**
     * Обращение общей матрицы 4×4 методом алгебраических дополнений.
     * Нужно для восстановления мировой позиции из глубины в шейдерах
     * и для перевода экранного касания в луч выбора блока.
     * При вырожденной матрице возвращает false и не портит [out].
     */
    fun invertTo(out: Mat4): Boolean {
        val a = m
        val inv = FloatArray(16)

        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] +
            a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] -
            a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] +
            a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] -
            a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] -
            a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] +
            a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] -
            a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] +
            a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] +
            a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] -
            a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] +
            a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] -
            a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] -
            a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] +
            a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] -
            a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] +
            a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]

        var det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (det == 0f || !det.isFinite()) return false
        det = 1f / det
        for (i in 0 until 16) out.m[i] = inv[i] * det
        return true
    }

    override fun toString(): String = buildString {
        for (row in 0 until 4) {
            append('[')
            for (col in 0 until 4) {
                append(m[col * 4 + row])
                if (col < 3) append(", ")
            }
            append("]\n")
        }
    }

    companion object {
        /**
         * out = a × b. [out] может совпадать с [a] или [b] — промежуточный
         * результат копится в локальных переменных, поэтому псевдонимы безопасны.
         */
        fun multiply(a: Mat4, b: Mat4, out: Mat4): Mat4 {
            val am = a.m; val bm = b.m
            val r = FloatArray(16)
            for (c in 0 until 4) {
                val c4 = c * 4
                val b0 = bm[c4]; val b1 = bm[c4 + 1]; val b2 = bm[c4 + 2]; val b3 = bm[c4 + 3]
                r[c4] = am[0] * b0 + am[4] * b1 + am[8] * b2 + am[12] * b3
                r[c4 + 1] = am[1] * b0 + am[5] * b1 + am[9] * b2 + am[13] * b3
                r[c4 + 2] = am[2] * b0 + am[6] * b1 + am[10] * b2 + am[14] * b3
                r[c4 + 3] = am[3] * b0 + am[7] * b1 + am[11] * b2 + am[15] * b3
            }
            System.arraycopy(r, 0, out.m, 0, 16)
            return out
        }
    }
}
