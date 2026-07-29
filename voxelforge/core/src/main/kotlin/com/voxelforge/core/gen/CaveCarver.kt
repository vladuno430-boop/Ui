package com.voxelforge.core.gen

import com.voxelforge.core.math.MathUtils
import com.voxelforge.core.noise.FractalNoise
import com.voxelforge.core.world.WorldConstants.SEA_LEVEL

/**
 * Прокладчик пещер.
 *
 * Назначение: вырезать в уже сформированном рельефе систему связных тоннелей
 * и залов.
 *
 * **Почему два поля шума, а не одно.** Простейший подход «вырезать там, где
 * |шум| < порога» даёт изолированные линзы-пустоты: множество уровня одной
 * функции в трёхмерном пространстве — это поверхность, и тонкий слой вокруг
 * неё образует не тоннель, а плоскую «пещеру-блин». Пересечение же двух
 * независимых полей — это множество коразмерности два, то есть **линия**.
 * Условие n1² + n2² < r² вырезает трубу вокруг этой линии: получаются длинные
 * ветвящиеся ходы, соединённые между собой, — ровно то, что нужно.
 *
 * Второй слой — крупные «сырные» полости с редким порогом — добавляет залы,
 * чтобы подземелье не состояло из одних коридоров одинакового сечения.
 *
 * Класс потокобезопасен на чтение.
 */
class CaveCarver(seed: Long) {

    private val tunnelA = FractalNoise(seed + 101, octaves = 2, persistence = 0.5f)
    private val tunnelB = FractalNoise(seed + 102, octaves = 2, persistence = 0.5f)
    private val chamber = FractalNoise(seed + 103, octaves = 3, persistence = 0.5f)
    private val radiusNoise = FractalNoise(seed + 104, octaves = 2, persistence = 0.5f)

    /**
     * Проверяет, должен ли воксель быть вырезан.
     *
     * @param x, y, z      мировые координаты
     * @param surfaceHeight высота поверхности в этой колонке
     * @param underwater    находится ли колонка под уровнем моря — под водоёмами
     *                      выход пещеры наружу осушил бы озеро
     */
    fun shouldCarve(x: Int, y: Int, z: Int, surfaceHeight: Int, underwater: Boolean): Boolean {
        // Коренная порода и слой над ней неприкосновенны: иначе игрок
        // проваливается в пустоту под миром.
        if (y <= FLOOR_LIMIT) return false

        // Не трогаем сам поверхностный слой — рельеф должен оставаться цельным,
        // а входы в пещеры создаются отдельно, ниже.
        val ceiling = if (underwater) {
            // Под водоёмом останавливаемся заметно ниже дна: тонкая перемычка
            // из нескольких блоков надёжно удерживает воду.
            kotlin.math.min(surfaceHeight - 6, SEA_LEVEL - 8)
        } else {
            surfaceHeight - 2
        }
        if (y > ceiling) return false

        val fx = x.toFloat()
        val fy = y.toFloat()
        val fz = z.toFloat()

        // Вертикальное сжатие координаты: тоннели становятся шире, чем выше,
        // и в разрезе выглядят приплюснутыми — так они читаются как ходы,
        // а не как круглые трубы.
        val fyScaled = fy * VERTICAL_SQUASH

        // Радиус тоннеля слегка «дышит» вдоль хода: местами он сужается
        // до лаза, местами расширяется. Постоянный радиус выглядит как
        // прокопанный техникой туннель метро.
        val r = TUNNEL_RADIUS * (0.72f + radiusNoise.fbm3D01(fx, fy, fz, FREQ_RADIUS) * 0.62f)

        val a = tunnelA.fbm3D(fx, fyScaled, fz, FREQ_TUNNEL)
        val b = tunnelB.fbm3D(fx, fyScaled, fz, FREQ_TUNNEL)
        if (a * a + b * b < r * r) return true

        // Крупные залы. Порог высокий, поэтому они редки; глубже они крупнее,
        // что даёт ощущение спуска в настоящее подземелье.
        val depthBonus = MathUtils.clamp((SEA_LEVEL - y) / 44f, 0f, 1f) * 0.055f
        if (chamber.fbm3D(fx, fy * 0.62f, fz, FREQ_CHAMBER) > CHAMBER_THRESHOLD - depthBonus) {
            return true
        }

        return false
    }

    /**
     * Отдельная проверка для входов в пещеру у поверхности.
     *
     * Вызывается только в узкой полосе под рельефом. Без неё все пещеры
     * оказались бы наглухо замурованы: основной проход намеренно
     * останавливается за два блока до поверхности, иначе ландшафт
     * покрылся бы дырами.
     */
    fun shouldCarveEntrance(x: Int, y: Int, z: Int, surfaceHeight: Int): Boolean {
        if (y > surfaceHeight - 1 || y < surfaceHeight - 4) return false
        val fx = x.toFloat()
        val fz = z.toFloat()
        // Вход открывается там, где тоннель подходит к поверхности почти
        // вплотную, и дополнительно проходит редкий порог, чтобы входов
        // было немного.
        val a = tunnelA.fbm3D(fx, y * VERTICAL_SQUASH, fz, FREQ_TUNNEL)
        val b = tunnelB.fbm3D(fx, y * VERTICAL_SQUASH, fz, FREQ_TUNNEL)
        return a * a + b * b < ENTRANCE_RADIUS * ENTRANCE_RADIUS
    }

    companion object {
        /** Ниже этой отметки пещеры не прокладываются. */
        private const val FLOOR_LIMIT = 4

        private const val FREQ_TUNNEL = 0.0155f
        private const val FREQ_CHAMBER = 0.028f
        private const val FREQ_RADIUS = 0.045f

        /**
         * Базовый радиус в единицах шума. 0.115 даёт ходы шириной
         * примерно 3–5 блоков — достаточно, чтобы игрок прошёл, и достаточно
         * тесно, чтобы подземелье не превратилось в пустую каверну.
         */
        private const val TUNNEL_RADIUS = 0.115f

        private const val ENTRANCE_RADIUS = 0.072f

        private const val CHAMBER_THRESHOLD = 0.545f

        /** Сжатие по вертикали: чем меньше, тем более плоскими выйдут ходы. */
        private const val VERTICAL_SQUASH = 1.85f
    }
}
