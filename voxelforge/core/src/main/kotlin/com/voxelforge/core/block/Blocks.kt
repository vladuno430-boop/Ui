package com.voxelforge.core.block

/**
 * Реестр всех типов блоков игры.
 *
 * Назначение: единая таблица определений плюс «плоские» массивы свойств для
 * горячих путей. Разделение здесь не преждевременная оптимизация, а
 * необходимость: мешер за одну перестройку чанка спрашивает «непрозрачен ли
 * блок?» порядка 200 000 раз. Обращение `definitions[id].opaqueCube` — это
 * два разыменования и почти гарантированный промах кэша, тогда как
 * `OPAQUE[id]` — одно чтение из массива в 26 байт, который целиком лежит
 * в L1. На среднем Snapdragon разница по времени мешинга — примерно вдвое.
 *
 * Идентификаторы блоков умещаются в один байт: это позволяет хранить
 * секцию 16×16×16 в 4 КБ и держать в памяти сотни чанков.
 *
 * Класс полностью неизменяем после инициализации и потокобезопасен.
 */
object Blocks {

    // --- Идентификаторы. Порядок фиксирован: он попадает в файлы сохранений. ---

    const val AIR = 0
    const val STONE = 1
    const val GRASS_BLOCK = 2
    const val DIRT = 3
    const val COBBLESTONE = 4
    const val SAND = 5
    const val GRAVEL = 6
    const val LOG = 7
    const val LEAVES = 8
    const val GLASS = 9
    const val WATER = 10
    const val LAVA = 11
    const val TORCH = 12
    const val PLANKS = 13
    const val BRICKS = 14
    const val BEDROCK = 15
    const val SANDSTONE = 16
    const val SNOW_BLOCK = 17
    const val ICE = 18
    const val CRAFTING_TABLE = 19
    const val COAL_ORE = 20
    const val IRON_ORE = 21
    const val CACTUS = 22
    const val TALL_GRASS = 23
    const val CLAY = 24
    const val SNOWY_GRASS = 25

    /** Число зарегистрированных блоков. */
    const val COUNT = 26

    /**
     * Определения, индексируемые идентификатором. Размер массива — 256,
     * чтобы повреждённый байт из файла сохранения не вызвал выход за границы;
     * незанятые ячейки заполнены воздухом.
     */
    private val definitions = arrayOfNulls<BlockDefinition>(256)

    // --- Плоские таблицы свойств для горячих путей ---

    /** Полностью ли блок перекрывает обзор — главный вопрос отсечения граней. */
    @JvmField val OPAQUE = BooleanArray(256)

    /** Участвует ли в коллизиях. */
    @JvmField val SOLID = BooleanArray(256)

    /** Жидкость ли — проверяется физикой каждый кадр для плавания. */
    @JvmField val LIQUID = BooleanArray(256)

    /** Сколько света излучает (факел, лава). */
    @JvmField val EMISSION = IntArray(256)

    /** На сколько уровней гасит проходящий свет. */
    @JvmField val OPACITY = IntArray(256)

    /** Форма — мешер ветвится по ней на каждом вокселе. */
    @JvmField val SHAPE = arrayOfNulls<BlockShape>(256)

    /** Слой отрисовки — определяет, в какой буфер пишется грань. */
    @JvmField val LAYER = arrayOfNulls<RenderLayer>(256)

    /** Заменяемый ли блок (можно строить прямо в него). */
    @JvmField val REPLACEABLE = BooleanArray(256)

    init {
        // Воздух. Единственный блок с формой EMPTY; его идентификатор 0
        // не случаен — свежий массив байтов уже заполнен воздухом.
        register(
            BlockDefinition.Builder(AIR, "air").name("Воздух")
                .shape(BlockShape.EMPTY).solid(false).opaque(false)
                .opacity(0).hardness(-1f).replaceable(true).dropsNothing()
                .allTiles(0).build()
        )

        register(
            BlockDefinition.Builder(STONE, "stone").name("Камень")
                .allTiles(Tiles.STONE).hardness(1.5f)
                // Камень при добыче даёт булыжник — это делает верстак и печь
                // осмысленной целью, а не декорацией.
                .drops(COBBLESTONE).build()
        )

        register(
            BlockDefinition.Builder(GRASS_BLOCK, "grass_block").name("Земля с травой")
                .tiles(Tiles.GRASS_TOP, Tiles.GRASS_SIDE, Tiles.DIRT)
                .icon(Tiles.GRASS_SIDE)
                .hardness(0.6f).drops(DIRT).build()
        )

        register(
            BlockDefinition.Builder(DIRT, "dirt").name("Земля")
                .allTiles(Tiles.DIRT).hardness(0.5f).build()
        )

        register(
            BlockDefinition.Builder(COBBLESTONE, "cobblestone").name("Булыжник")
                .allTiles(Tiles.COBBLESTONE).hardness(2.0f).build()
        )

        register(
            BlockDefinition.Builder(SAND, "sand").name("Песок")
                .allTiles(Tiles.SAND).hardness(0.5f).build()
        )

        register(
            BlockDefinition.Builder(GRAVEL, "gravel").name("Гравий")
                .allTiles(Tiles.GRAVEL).hardness(0.6f).build()
        )

        register(
            BlockDefinition.Builder(LOG, "log").name("Дерево")
                .tiles(Tiles.LOG_TOP, Tiles.LOG_SIDE, Tiles.LOG_TOP)
                .hardness(2.0f).build()
        )

        // Листва непрозрачна визуально, но не перекрывает свет полностью:
        // opacity=1 создаёт под кроной мягкий полумрак вместо чёрного пятна.
        register(
            BlockDefinition.Builder(LEAVES, "leaves").name("Листва")
                .allTiles(Tiles.LEAVES)
                .layer(RenderLayer.CUTOUT).opaque(false).opacity(1)
                .hardness(0.2f).build()
        )

        register(
            BlockDefinition.Builder(GLASS, "glass").name("Стекло")
                .allTiles(Tiles.GLASS)
                .layer(RenderLayer.TRANSLUCENT).opaque(false).opacity(0)
                .hardness(0.3f).dropsNothing().build()
        )

        // Вода: не твёрдая, слегка гасит свет (даёт естественное затемнение
        // с глубиной), высота коллайдера ниже полной — так игрок плавает,
        // а не «стоит» на поверхности.
        register(
            BlockDefinition.Builder(WATER, "water").name("Вода")
                .allTiles(Tiles.WATER)
                .shape(BlockShape.LIQUID).layer(RenderLayer.TRANSLUCENT)
                .solid(false).opaque(false).opacity(2).liquid(true)
                .replaceable(true).hardness(-1f).dropsNothing()
                .collisionHeight(0.9).build()
        )

        register(
            BlockDefinition.Builder(LAVA, "lava").name("Лава")
                .allTiles(Tiles.LAVA)
                .shape(BlockShape.LIQUID).layer(RenderLayer.TRANSLUCENT)
                .solid(false).opaque(false).opacity(0).liquid(true)
                .emission(15).replaceable(true).hardness(-1f).dropsNothing()
                .collisionHeight(0.9).build()
        )

        register(
            BlockDefinition.Builder(TORCH, "torch").name("Факел")
                .allTiles(Tiles.TORCH)
                .shape(BlockShape.CROSS).layer(RenderLayer.CUTOUT)
                .solid(false).opaque(false).opacity(0)
                .emission(14).hardness(0.05f).build()
        )

        register(
            BlockDefinition.Builder(PLANKS, "planks").name("Доски")
                .allTiles(Tiles.PLANKS).hardness(1.5f).build()
        )

        register(
            BlockDefinition.Builder(BRICKS, "bricks").name("Кирпич")
                .allTiles(Tiles.BRICKS).hardness(2.0f).build()
        )

        // Коренная порода: hardness -1 делает её неразрушимой и защищает
        // нижнюю границу мира от проваливания игрока в пустоту.
        register(
            BlockDefinition.Builder(BEDROCK, "bedrock").name("Коренная порода")
                .allTiles(Tiles.BEDROCK).hardness(-1f).dropsNothing().build()
        )

        register(
            BlockDefinition.Builder(SANDSTONE, "sandstone").name("Песчаник")
                .tiles(Tiles.SANDSTONE_TOP, Tiles.SANDSTONE_SIDE, Tiles.SANDSTONE_TOP)
                .hardness(1.2f).build()
        )

        register(
            BlockDefinition.Builder(SNOW_BLOCK, "snow_block").name("Снег")
                .allTiles(Tiles.SNOW).hardness(0.3f).build()
        )

        // Лёд скользкий: slipperiness > 1 увеличивает сохраняемую инерцию
        // в физике движения.
        register(
            BlockDefinition.Builder(ICE, "ice").name("Лёд")
                .allTiles(Tiles.ICE)
                .layer(RenderLayer.TRANSLUCENT).opaque(false).opacity(2)
                .slippery(1.7f).hardness(0.5f).dropsNothing().build()
        )

        register(
            BlockDefinition.Builder(CRAFTING_TABLE, "crafting_table").name("Верстак")
                .tiles(Tiles.CRAFTING_TOP, Tiles.CRAFTING_SIDE, Tiles.PLANKS)
                .hardness(2.5f).build()
        )

        register(
            BlockDefinition.Builder(COAL_ORE, "coal_ore").name("Угольная руда")
                .allTiles(Tiles.COAL_ORE).hardness(3.0f)
                // Руда выпадает не блоком, а предметом — см. Items.COAL.
                .dropsNothing().build()
        )

        register(
            BlockDefinition.Builder(IRON_ORE, "iron_ore").name("Железная руда")
                .allTiles(Tiles.IRON_ORE).hardness(3.0f).build()
        )

        // Кактус не полный куб по коллизии — иначе он выглядел бы как столб,
        // сквозь который нельзя протиснуться в узком проходе пустыни.
        register(
            BlockDefinition.Builder(CACTUS, "cactus").name("Кактус")
                .tiles(Tiles.CACTUS_TOP, Tiles.CACTUS_SIDE, Tiles.CACTUS_TOP)
                .opaque(false).opacity(1).layer(RenderLayer.CUTOUT)
                .hardness(0.4f).build()
        )

        register(
            BlockDefinition.Builder(TALL_GRASS, "tall_grass").name("Трава")
                .allTiles(Tiles.TALL_GRASS)
                .shape(BlockShape.CROSS).layer(RenderLayer.CUTOUT)
                .solid(false).opaque(false).opacity(0)
                .replaceable(true).hardness(0.05f).dropsNothing().build()
        )

        register(
            BlockDefinition.Builder(CLAY, "clay").name("Глина")
                .allTiles(Tiles.CLAY).hardness(0.6f).build()
        )

        register(
            BlockDefinition.Builder(SNOWY_GRASS, "snowy_grass").name("Заснеженная земля")
                .tiles(Tiles.SNOW, Tiles.SNOW_SIDE, Tiles.DIRT)
                .icon(Tiles.SNOW_SIDE)
                .hardness(0.6f).drops(DIRT).build()
        )

        // Все незанятые идентификаторы указывают на воздух. Это защита от
        // повреждённого сохранения: игра покажет дырку в рельефе, но не упадёт.
        val air = definitions[AIR]!!
        for (i in 0 until 256) {
            if (definitions[i] == null) {
                definitions[i] = air
                SHAPE[i] = BlockShape.EMPTY
                LAYER[i] = RenderLayer.OPAQUE
                REPLACEABLE[i] = true
            }
        }
    }

    /** Заносит определение в реестр и разворачивает его свойства в плоские таблицы. */
    private fun register(def: BlockDefinition) {
        require(definitions[def.id] == null) { "Duplicate block id ${def.id} for ${def.key}" }
        definitions[def.id] = def
        OPAQUE[def.id] = def.opaqueCube
        SOLID[def.id] = def.solid
        LIQUID[def.id] = def.liquid
        EMISSION[def.id] = def.lightEmission
        OPACITY[def.id] = def.lightOpacity
        SHAPE[def.id] = def.shape
        LAYER[def.id] = def.renderLayer
        REPLACEABLE[def.id] = def.replaceable
    }

    /** Полное определение блока. Для горячих путей предпочитайте плоские таблицы. */
    fun get(id: Int): BlockDefinition = definitions[id and 0xFF]!!

    /** Поиск по строковому ключу — используется при миграции сохранений. */
    fun byKey(key: String): BlockDefinition? =
        definitions.filterNotNull().firstOrNull { it.key == key && it.id != AIR || (key == "air" && it.id == AIR) }

    /** Список всех блоков, доступных игроку в творческом режиме. */
    val creativePalette: IntArray = intArrayOf(
        GRASS_BLOCK, DIRT, STONE, COBBLESTONE, SAND, SANDSTONE, GRAVEL, CLAY,
        LOG, PLANKS, LEAVES, BRICKS, GLASS, TORCH, CRAFTING_TABLE,
        SNOW_BLOCK, ICE, CACTUS, COAL_ORE, IRON_ORE, WATER, LAVA
    )

    /** Быстрая проверка «этот воксель вообще что-то рисует». */
    inline fun isAir(id: Int): Boolean = id == AIR

    /**
     * Перекрывает ли блок [neighbour] грань блока [self].
     *
     * Ключевая функция отсечения граней. Тонкость в последнем условии:
     * два соседних стекла (или две воды) не должны рисовать разделяющую
     * грань — иначе внутри стеклянного куба видна сетка граней, а под водой
     * экран превращается в мутную кашу из перекрывающихся полупрозрачных
     * слоёв, что и стоит кадров, и выглядит плохо.
     */
    fun hidesFace(self: Int, neighbour: Int): Boolean {
        if (OPAQUE[neighbour]) return true
        if (neighbour == self && LAYER[neighbour] == RenderLayer.TRANSLUCENT) return true
        return false
    }
}
