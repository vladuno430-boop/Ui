package com.voxelforge.core.mesh

/**
 * Формат вершины воксельного меша и утилиты его упаковки.
 *
 * Назначение: описать, как атрибуты вершины укладываются в два 32-битных
 * слова. Этот файл — **контракт с шейдером**: раскладка битов здесь и разбор
 * в вершинном шейдере обязаны совпадать, поэтому смещения вынесены
 * в именованные константы и продублированы в шейдере строкой в строку.
 *
 * **Почему упаковка вообще нужна.** «Честная» вершина (позиция 3×float,
 * UV 2×float, нормаль 3×float, свет 2×float, слой float) занимает 44 байта.
 * В поле зрения при дальности 12 чанков находится порядка 600 непустых
 * секций; при типичных 700 гранях на секцию это 600 × 700 × 4 × 44 ≈ 74 МБ
 * видеопамяти. Мобильный GPU с общей памятью такого не переживёт: начнётся
 * вытеснение, и частота кадров обвалится независимо от нагрузки на шейдеры.
 *
 * Упаковка в 8 байт сокращает это до 13 МБ. Дополнительно вдвое падает
 * трафик чтения вершин, а на мобильных GPU с общей шиной памяти именно
 * пропускная способность, а не арифметика, обычно и оказывается узким местом.
 *
 * **Раскладка.**
 *
 * Слово 0:
 * ```
 *  биты 0..8    позиция X (0..511), в шестнадцатых долях блока
 *  биты 9..17   позиция Y
 *  биты 18..26  позиция Z
 *  биты 27..29  индекс грани (0..5) — нормаль берётся по нему из таблицы
 *  биты 30..31  Ambient Occlusion (0..3)
 * ```
 *
 * Слово 1:
 * ```
 *  биты 0..4    текстурная координата U (0..31), в целых тайлах
 *  биты 5..9    текстурная координата V (0..31)
 *  биты 10..15  слой текстурного массива (0..63)
 *  биты 16..19  солнечный свет (0..15)
 *  биты 20..23  свет от источников (0..15)
 *  биты 24..26  биом (0..7) — индекс в таблице цветов тонирования
 *  бит  27      признак анимации (колебание воды и лавы)
 *  бит  28      признак тонирования биомом (трава, листва)
 *  биты 29..31  резерв
 * ```
 *
 * Позиция хранится в шестнадцатых долях блока: девяти бит хватает
 * на диапазон 0..16 блоков секции с шагом 1/16. Такой шаг нужен только
 * блокам сложной формы (факел, трава, поверхность воды); для обычных кубов
 * значения кратны 16.
 */
object VertexFormat {

    /** Размер вершины в байтах — два 32-битных слова. */
    const val BYTES_PER_VERTEX = 8

    /** Число целых слов на вершину. */
    const val INTS_PER_VERTEX = 2

    /** Вершин и индексов на одну грань-четырёхугольник. */
    const val VERTICES_PER_QUAD = 4
    const val INDICES_PER_QUAD = 6

    /** Подразделений блока в единицах позиции. */
    const val POSITION_SCALE = 16

    // --- Смещения битов в слове 0 ---
    const val POS_X_SHIFT = 0
    const val POS_Y_SHIFT = 9
    const val POS_Z_SHIFT = 18
    const val FACE_SHIFT = 27
    const val AO_SHIFT = 30

    const val POS_MASK = 0x1FF     // 9 бит
    const val FACE_MASK = 0x7      // 3 бита
    const val AO_MASK = 0x3        // 2 бита

    // --- Смещения битов в слове 1 ---
    const val U_SHIFT = 0
    const val V_SHIFT = 5
    const val LAYER_SHIFT = 10
    const val SKY_SHIFT = 16
    const val BLOCK_LIGHT_SHIFT = 20
    const val BIOME_SHIFT = 24
    const val ANIMATED_SHIFT = 27
    const val TINTED_SHIFT = 28

    const val UV_MASK = 0x1F       // 5 бит
    const val LAYER_MASK = 0x3F    // 6 бит
    const val LIGHT_MASK = 0xF     // 4 бита
    const val BIOME_MASK = 0x7     // 3 бита

    /**
     * Упаковывает первое слово вершины.
     *
     * @param x, y, z позиция в шестнадцатых долях блока относительно угла секции
     * @param face    индекс грани [com.voxelforge.core.block.BlockFace.index]
     * @param ao      уровень затенения 0 (максимальная тень) .. 3 (нет тени)
     */
    fun packWord0(x: Int, y: Int, z: Int, face: Int, ao: Int): Int =
        ((x and POS_MASK) shl POS_X_SHIFT) or
            ((y and POS_MASK) shl POS_Y_SHIFT) or
            ((z and POS_MASK) shl POS_Z_SHIFT) or
            ((face and FACE_MASK) shl FACE_SHIFT) or
            ((ao and AO_MASK) shl AO_SHIFT)

    /**
     * Упаковывает второе слово вершины.
     *
     * @param u, v      координаты текстуры в целых тайлах (0..31)
     * @param layer     слой текстурного массива
     * @param sky       солнечный свет 0..15
     * @param blockLight свет от источников 0..15
     * @param biome     индекс биома для тонирования
     * @param animated  колеблется ли поверхность (вода, лава)
     * @param tinted    применять ли цвет биома (трава, листва)
     */
    fun packWord1(
        u: Int,
        v: Int,
        layer: Int,
        sky: Int,
        blockLight: Int,
        biome: Int,
        animated: Boolean,
        tinted: Boolean
    ): Int =
        ((u and UV_MASK) shl U_SHIFT) or
            ((v and UV_MASK) shl V_SHIFT) or
            ((layer and LAYER_MASK) shl LAYER_SHIFT) or
            ((sky and LIGHT_MASK) shl SKY_SHIFT) or
            ((blockLight and LIGHT_MASK) shl BLOCK_LIGHT_SHIFT) or
            ((biome and BIOME_MASK) shl BIOME_SHIFT) or
            (if (animated) 1 shl ANIMATED_SHIFT else 0) or
            (if (tinted) 1 shl TINTED_SHIFT else 0)

    // --- Распаковка. Используется тестами и отладочными инструментами. ---

    fun unpackX(word0: Int): Int = (word0 shr POS_X_SHIFT) and POS_MASK
    fun unpackY(word0: Int): Int = (word0 shr POS_Y_SHIFT) and POS_MASK
    fun unpackZ(word0: Int): Int = (word0 shr POS_Z_SHIFT) and POS_MASK
    fun unpackFace(word0: Int): Int = (word0 shr FACE_SHIFT) and FACE_MASK
    fun unpackAo(word0: Int): Int = (word0 shr AO_SHIFT) and AO_MASK

    fun unpackU(word1: Int): Int = (word1 shr U_SHIFT) and UV_MASK
    fun unpackV(word1: Int): Int = (word1 shr V_SHIFT) and UV_MASK
    fun unpackLayer(word1: Int): Int = (word1 shr LAYER_SHIFT) and LAYER_MASK
    fun unpackSky(word1: Int): Int = (word1 shr SKY_SHIFT) and LIGHT_MASK
    fun unpackBlockLight(word1: Int): Int = (word1 shr BLOCK_LIGHT_SHIFT) and LIGHT_MASK
    fun unpackBiome(word1: Int): Int = (word1 shr BIOME_SHIFT) and BIOME_MASK
}
