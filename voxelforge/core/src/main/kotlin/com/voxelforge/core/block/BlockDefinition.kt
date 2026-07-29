package com.voxelforge.core.block

/**
 * Слой отрисовки блока. Определяет, в каком проходе рендера строится грань
 * и какое состояние GL при этом установлено.
 */
enum class RenderLayer {
    /**
     * Полностью непрозрачные блоки. Рисуются первыми, с записью в буфер
     * глубины и без смешивания — это позволяет GPU отбросить закрытые
     * фрагменты ещё до фрагментного шейдера (early-Z).
     */
    OPAQUE,

    /**
     * Блоки с бинарной прозрачностью: листва, факел, трава.
     * Пиксель либо есть, либо его нет — отбрасывается по alpha-test в шейдере.
     * Рисуются после OPAQUE, но всё ещё пишут глубину, поэтому не требуют
     * сортировки по расстоянию.
     */
    CUTOUT,

    /**
     * Полупрозрачные: вода, стекло, лёд. Требуют смешивания и рисуются
     * последними, отсортированными от дальних к ближним по чанкам.
     * Запись глубины отключена, иначе стёкла перекрывали бы друг друга.
     */
    TRANSLUCENT
}

/**
 * Геометрическая форма блока.
 */
enum class BlockShape {
    /** Не порождает геометрии вовсе (воздух). */
    EMPTY,

    /** Обычный куб 1×1×1 — основной случай, идёт через жадный мешер. */
    CUBE,

    /**
     * Два пересекающихся под прямым углом квада («крестик»).
     * Так рисуются трава и факел. Такие блоки не объединяются мешером
     * и обрабатываются отдельной веткой.
     */
    CROSS,

    /**
     * Жидкость: куб с пониженной верхней гранью и анимацией в шейдере.
     * Не сталкивается с игроком, но замедляет его и включает плавание.
     */
    LIQUID
}

/**
 * Неизменяемое описание свойств одного типа блока.
 *
 * Назначение: единственный источник правды о поведении блока. Мешер,
 * освещение, физика, разрушение и инвентарь спрашивают свойства **здесь**,
 * а не проверяют идентификатор через `when`. Благодаря этому добавление
 * нового блока — одна строка в [Blocks], а не правка десяти мест
 * (принцип открытости/закрытости).
 *
 * Класс неизменяемый и потокобезопасный: определения читают одновременно
 * все воркеры генерации и мешинга.
 *
 * @param id             числовой идентификатор 0..255, хранится в чанке одним байтом
 * @param key            строковый ключ для сохранений; при смене порядка блоков
 *                       именно он позволяет корректно мигрировать старые миры
 * @param displayName    название для интерфейса
 * @param shape          геометрическая форма
 * @param renderLayer    проход отрисовки
 * @param solid          участвует ли в коллизиях игрока
 * @param opaqueCube     полностью ли перекрывает свет и грани соседей;
 *                       именно этот флаг управляет отсечением скрытых граней
 * @param lightEmission  сколько света излучает (0..15)
 * @param lightOpacity   насколько гасит проходящий свет (0..15)
 * @param hardness       время разрушения в секундах; -1 означает неразрушимость
 * @param liquid         жидкость ли это (плавание, замедление, отсутствие опоры)
 * @param replaceable    можно ли ставить блок прямо поверх (трава, вода)
 * @param slipperiness   коэффициент сцепления: лёд < 1 даёт скольжение
 */
class BlockDefinition(
    @JvmField val id: Int,
    @JvmField val key: String,
    @JvmField val displayName: String,
    @JvmField val shape: BlockShape,
    @JvmField val renderLayer: RenderLayer,
    @JvmField val solid: Boolean,
    @JvmField val opaqueCube: Boolean,
    @JvmField val lightEmission: Int,
    @JvmField val lightOpacity: Int,
    @JvmField val hardness: Float,
    @JvmField val liquid: Boolean,
    @JvmField val replaceable: Boolean,
    @JvmField val slipperiness: Float,
    /**
     * Текстурный слой для каждой из шести граней, индексируется [BlockFace.index].
     * Хранится плоским массивом, потому что мешер обращается сюда на каждую
     * построенную грань — а это сотни тысяч обращений на чанк.
     */
    @JvmField val faceTiles: IntArray,
    /** Слой, используемый как иконка в инвентаре. */
    @JvmField val iconTile: Int,
    /**
     * Идентификатор блока, выпадающего при разрушении. Позволяет камню
     * выпадать булыжником, а траве — землёй. -1 означает «ничего не выпадает».
     */
    @JvmField val dropBlockId: Int,
    /** Сколько предметов выпадает. */
    @JvmField val dropCount: Int,
    /** Высота коллайдера: снег и жидкости ниже полного блока. */
    @JvmField val collisionHeight: Double,
    /**
     * Ширина «крестика» в шестнадцатых долях блока (для [BlockShape.CROSS]).
     * У травы это полная ширина, у факела — узкая полоска, благодаря чему
     * один и тот же механизм отрисовки даёт две разные по силуэту вещи
     * без отдельного кода для каждой.
     */
    @JvmField val crossWidth: Int,
    /** Высота «крестика» в шестнадцатых долях блока. */
    @JvmField val crossHeight: Int
) {

    /** Текстурный слой конкретной грани. */
    fun tileFor(face: BlockFace): Int = faceTiles[face.index]

    /** Блок, который не строит геометрию и не мешает движению. */
    val isAir: Boolean get() = shape == BlockShape.EMPTY

    /** Можно ли разрушить блок в выживании. */
    val breakable: Boolean get() = hardness >= 0f

    override fun toString(): String = "Block($key#$id)"

    /**
     * Строитель определений. Нужен потому, что у блока полтора десятка
     * параметров, и позиционный вызов конструктора на 18 аргументов
     * невозможно читать и легко испортить перестановкой двух Boolean.
     * Строитель задаёт разумные умолчания «обычного каменного куба»,
     * и в описании каждого блока остаются только его отличия.
     */
    class Builder(private val id: Int, private val key: String) {
        private var displayName: String = key
        private var shape: BlockShape = BlockShape.CUBE
        private var renderLayer: RenderLayer = RenderLayer.OPAQUE
        private var solid: Boolean = true
        private var opaqueCube: Boolean = true
        private var lightEmission: Int = 0
        private var lightOpacity: Int = 15
        private var hardness: Float = 1.0f
        private var liquid: Boolean = false
        private var replaceable: Boolean = false
        private var slipperiness: Float = 1.0f
        private val tiles = IntArray(6)
        private var iconTile: Int = -1
        private var dropBlockId: Int = -2   // -2 = «выпадает сам собой»
        private var dropCount: Int = 1
        private var collisionHeight: Double = 1.0
        private var crossWidth: Int = 16
        private var crossHeight: Int = 16

        fun name(v: String) = apply { displayName = v }
        fun shape(v: BlockShape) = apply { shape = v }
        fun layer(v: RenderLayer) = apply { renderLayer = v }
        fun solid(v: Boolean) = apply { solid = v }
        fun opaque(v: Boolean) = apply { opaqueCube = v }
        fun emission(v: Int) = apply { lightEmission = v.coerceIn(0, 15) }
        fun opacity(v: Int) = apply { lightOpacity = v.coerceIn(0, 15) }
        fun hardness(v: Float) = apply { hardness = v }
        fun liquid(v: Boolean) = apply { liquid = v }
        fun replaceable(v: Boolean) = apply { replaceable = v }
        fun slippery(v: Float) = apply { slipperiness = v }
        fun drops(blockId: Int, count: Int = 1) = apply { dropBlockId = blockId; dropCount = count }
        fun dropsNothing() = apply { dropBlockId = -1 }
        fun collisionHeight(v: Double) = apply { collisionHeight = v }

        /** Габариты «крестика» в шестнадцатых долях блока. */
        fun cross(width: Int, height: Int) = apply { crossWidth = width; crossHeight = height }

        /** Одна текстура на все шесть граней — самый частый случай. */
        fun allTiles(tile: Int) = apply {
            tiles.fill(tile)
            if (iconTile < 0) iconTile = tile
        }

        /** Раздельные верх / бок / низ — трава, песчаник, верстак, бревно. */
        fun tiles(top: Int, side: Int, bottom: Int) = apply {
            tiles[BlockFace.UP.index] = top
            tiles[BlockFace.DOWN.index] = bottom
            tiles[BlockFace.NORTH.index] = side
            tiles[BlockFace.SOUTH.index] = side
            tiles[BlockFace.WEST.index] = side
            tiles[BlockFace.EAST.index] = side
            if (iconTile < 0) iconTile = side
        }

        fun icon(tile: Int) = apply { iconTile = tile }

        fun build(): BlockDefinition {
            val resolvedDrop = if (dropBlockId == -2) id else dropBlockId
            return BlockDefinition(
                id = id,
                key = key,
                displayName = displayName,
                shape = shape,
                renderLayer = renderLayer,
                solid = solid,
                opaqueCube = opaqueCube,
                lightEmission = lightEmission,
                lightOpacity = lightOpacity,
                hardness = hardness,
                liquid = liquid,
                replaceable = replaceable,
                slipperiness = slipperiness,
                faceTiles = tiles,
                iconTile = if (iconTile < 0) 0 else iconTile,
                dropBlockId = resolvedDrop,
                dropCount = dropCount,
                collisionHeight = collisionHeight,
                crossWidth = crossWidth,
                crossHeight = crossHeight
            )
        }
    }
}
