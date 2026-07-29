package com.voxelforge.core.gen

import com.voxelforge.core.block.Blocks

/**
 * Описание биома — климатической зоны со своим рельефом, покровом и флорой.
 *
 * Назначение: биом отвечает на четыре вопроса генератора:
 *  1. Какой высоты здесь рельеф и насколько он изрезан.
 *  2. Чем покрыта поверхность и что лежит под ней.
 *  3. Что здесь растёт и как густо.
 *  4. Какого оттенка трава и листва.
 *
 * Класс неизменяемый, экземпляры создаются один раз в [Biomes] и читаются
 * из всех потоков генерации.
 *
 * Отдельно стоит пояснить пару [baseHeight] / [heightVariation]. Генератор
 * не выбирает высоту «по биому» — он **смешивает** параметры соседних биомов
 * в радиусе нескольких блоков (см. BiomeSource.sampleBlendedShape). Без такого
 * смешивания на границе равнины и гор возникал бы вертикальный обрыв в
 * тридцать блоков, что выглядит как дефект генерации, а не как ландшафт.
 *
 * @param id                 идентификатор, хранится байтом в карте биомов чанка
 * @param key                строковый ключ для сохранений
 * @param displayName        название для интерфейса
 * @param baseHeight         средняя высота поверхности в блоках
 * @param heightVariation    амплитуда неровностей вокруг средней высоты
 * @param ruggedness         доля «скального» ridged-шума: 0 — пологие холмы,
 *                           1 — острые гребни. Именно он делает горы горами
 * @param surfaceBlock       верхний блок поверхности
 * @param subSurfaceBlock    3–4 блока под поверхностью
 * @param underwaterSurface  чем покрыто дно, если колонка ниже уровня моря
 * @param treeDensity        среднее число деревьев на чанк
 * @param grassDensity       среднее число кустиков травы на чанк
 * @param cactusDensity      среднее число кактусов на чанк
 * @param snowy              выпадает ли снег вместо дождя и мёрзнет ли вода
 * @param grassTint          цвет тонирования травы в формате 0xRRGGBB
 * @param foliageTint        цвет тонирования листвы
 */
class Biome(
    @JvmField val id: Int,
    @JvmField val key: String,
    @JvmField val displayName: String,
    @JvmField val baseHeight: Float,
    @JvmField val heightVariation: Float,
    @JvmField val ruggedness: Float,
    @JvmField val surfaceBlock: Int,
    @JvmField val subSurfaceBlock: Int,
    @JvmField val underwaterSurface: Int,
    @JvmField val treeDensity: Float,
    @JvmField val grassDensity: Float,
    @JvmField val cactusDensity: Float,
    @JvmField val snowy: Boolean,
    @JvmField val grassTint: Int,
    @JvmField val foliageTint: Int
) {
    override fun toString(): String = "Biome($key)"
}

/**
 * Реестр биомов.
 *
 * Пять биомов из требований (лес, пустыня, горы, равнины, снег) дополнены
 * тремя служебными — океаном, пляжем и рекой. Они не «лишние»: без пляжа
 * трава обрывается в воду вертикальной стенкой, а без отдельного биома реки
 * русло получало бы покров окружающего биома, и река в пустыне текла бы
 * между песчаных обрывов без единого признака берега.
 */
object Biomes {

    const val OCEAN = 0
    const val BEACH = 1
    const val PLAINS = 2
    const val FOREST = 3
    const val DESERT = 4
    const val MOUNTAINS = 5
    const val SNOW = 6
    const val RIVER = 7

    const val COUNT = 8

    @JvmField
    val ALL: Array<Biome> = arrayOf(
        Biome(
            id = OCEAN, key = "ocean", displayName = "Океан",
            baseHeight = 40f, heightVariation = 4f, ruggedness = 0f,
            surfaceBlock = Blocks.GRAVEL, subSurfaceBlock = Blocks.GRAVEL,
            underwaterSurface = Blocks.GRAVEL,
            treeDensity = 0f, grassDensity = 0f, cactusDensity = 0f,
            snowy = false, grassTint = 0x6FA85C, foliageTint = 0x5C8F4A
        ),
        Biome(
            id = BEACH, key = "beach", displayName = "Пляж",
            baseHeight = 55f, heightVariation = 1.5f, ruggedness = 0f,
            surfaceBlock = Blocks.SAND, subSurfaceBlock = Blocks.SAND,
            underwaterSurface = Blocks.SAND,
            treeDensity = 0f, grassDensity = 0.4f, cactusDensity = 0f,
            snowy = false, grassTint = 0x8DB360, foliageTint = 0x74A24C
        ),
        Biome(
            id = PLAINS, key = "plains", displayName = "Равнина",
            baseHeight = 60f, heightVariation = 5f, ruggedness = 0.05f,
            surfaceBlock = Blocks.GRASS_BLOCK, subSurfaceBlock = Blocks.DIRT,
            underwaterSurface = Blocks.DIRT,
            // Редкие одиночные деревья: равнина должна читаться как открытая.
            treeDensity = 0.6f, grassDensity = 12f, cactusDensity = 0f,
            snowy = false, grassTint = 0x91BD59, foliageTint = 0x77AB2F
        ),
        Biome(
            id = FOREST, key = "forest", displayName = "Лес",
            baseHeight = 63f, heightVariation = 8f, ruggedness = 0.12f,
            surfaceBlock = Blocks.GRASS_BLOCK, subSurfaceBlock = Blocks.DIRT,
            underwaterSurface = Blocks.DIRT,
            treeDensity = 9f, grassDensity = 8f, cactusDensity = 0f,
            snowy = false, grassTint = 0x79C05A, foliageTint = 0x59AE30
        ),
        Biome(
            id = DESERT, key = "desert", displayName = "Пустыня",
            baseHeight = 58f, heightVariation = 6f, ruggedness = 0.08f,
            surfaceBlock = Blocks.SAND, subSurfaceBlock = Blocks.SANDSTONE,
            underwaterSurface = Blocks.SAND,
            treeDensity = 0f, grassDensity = 0f, cactusDensity = 1.2f,
            snowy = false, grassTint = 0xBFB755, foliageTint = 0xAEA42A
        ),
        Biome(
            id = MOUNTAINS, key = "mountains", displayName = "Горы",
            // Высокая база и большая амплитуда, но главное — ruggedness 0.85:
            // именно ridged-шум даёт скальные гребни вместо округлых холмов.
            baseHeight = 86f, heightVariation = 26f, ruggedness = 0.85f,
            surfaceBlock = Blocks.GRASS_BLOCK, subSurfaceBlock = Blocks.DIRT,
            underwaterSurface = Blocks.GRAVEL,
            treeDensity = 1.5f, grassDensity = 3f, cactusDensity = 0f,
            snowy = false, grassTint = 0x8AB689, foliageTint = 0x6DA36B
        ),
        Biome(
            id = SNOW, key = "snow", displayName = "Снежная тундра",
            baseHeight = 62f, heightVariation = 7f, ruggedness = 0.15f,
            surfaceBlock = Blocks.SNOWY_GRASS, subSurfaceBlock = Blocks.DIRT,
            underwaterSurface = Blocks.DIRT,
            treeDensity = 2.5f, grassDensity = 1f, cactusDensity = 0f,
            snowy = true, grassTint = 0x80B497, foliageTint = 0x60A17B
        ),
        Biome(
            id = RIVER, key = "river", displayName = "Река",
            baseHeight = 50f, heightVariation = 2f, ruggedness = 0f,
            surfaceBlock = Blocks.SAND, subSurfaceBlock = Blocks.CLAY,
            // Глина на дне рек — не украшательство: это единственный источник
            // материала для кирпича, то есть у рек появляется игровой смысл.
            underwaterSurface = Blocks.CLAY,
            treeDensity = 0f, grassDensity = 1f, cactusDensity = 0f,
            snowy = false, grassTint = 0x8EB971, foliageTint = 0x71A74D
        )
    )

    fun get(id: Int): Biome = ALL[if (id in ALL.indices) id else PLAINS]

    fun byKey(key: String): Biome? = ALL.firstOrNull { it.key == key }
}
