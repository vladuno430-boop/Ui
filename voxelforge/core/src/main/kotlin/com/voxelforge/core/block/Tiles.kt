package com.voxelforge.core.block

/**
 * Идентификаторы слоёв текстурного массива (GL_TEXTURE_2D_ARRAY).
 *
 * Назначение: связать грань блока с конкретной картинкой, не зная ничего
 * о том, как она нарисована. Модуль :core объявляет **какой** слой у грани,
 * а Android-модуль процедурно рисует **что** в этом слое (см. ProceduralAtlas).
 * Это граница между логикой и представлением: логику можно тестировать
 * на JVM, где нет ни OpenGL, ни Bitmap.
 *
 * Почему текстурный массив, а не классический атлас-сетка:
 *  1. Жадный мешер объединяет соседние одинаковые грани в один прямоугольник
 *     N×M. В атласе UV вышли бы за пределы тайла и захватили чужую текстуру,
 *     поэтому пришлось бы либо отказаться от объединения, либо городить
 *     padding с ручным повтором. В массиве координата слоя отделена от UV,
 *     и GL_REPEAT работает штатно.
 *  2. Нет «протекания» соседних тайлов при мипмаппинге — вечная болезнь
 *     атласов на дальних дистанциях.
 *
 * Слои нумеруются подряд с нуля: значение напрямую идёт в атрибут вершины.
 */
object Tiles {
    const val STONE = 0
    const val DIRT = 1
    const val GRASS_TOP = 2
    const val GRASS_SIDE = 3
    const val COBBLESTONE = 4
    const val SAND = 5
    const val GRAVEL = 6
    const val LOG_SIDE = 7
    const val LOG_TOP = 8
    const val LEAVES = 9
    const val GLASS = 10
    const val WATER = 11
    const val LAVA = 12
    const val TORCH = 13
    const val PLANKS = 14
    const val BRICKS = 15
    const val BEDROCK = 16
    const val SANDSTONE_TOP = 17
    const val SANDSTONE_SIDE = 18
    const val SNOW = 19
    const val ICE = 20
    const val CRAFTING_TOP = 21
    const val CRAFTING_SIDE = 22
    const val COAL_ORE = 23
    const val IRON_ORE = 24
    const val CACTUS_TOP = 25
    const val CACTUS_SIDE = 26
    const val TALL_GRASS = 27
    const val CLAY = 28
    const val SNOW_SIDE = 29
    const val ICON_STICK = 30
    const val ICON_COAL = 31

    /**
     * Полное число слоёв. Держится степенью двойки: часть мобильных GPU
     * (особенно Mali старых поколений) заметно эффективнее работает с такими
     * массивами при генерации мип-уровней.
     */
    const val COUNT = 32

    /** Разрешение одного слоя в пикселях. 16×16 — канон воксельного стиля. */
    const val TILE_SIZE = 16
}
