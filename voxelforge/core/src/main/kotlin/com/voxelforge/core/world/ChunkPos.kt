package com.voxelforge.core.world

import kotlin.math.abs
import kotlin.math.max

/**
 * Координаты чанка в сетке чанков (не блоков).
 *
 * Назначение: ключ для карты загруженных чанков и единица планирования
 * загрузки/выгрузки.
 *
 * Класс объявлен как `value class` поверх упакованного Long: в бесконечном
 * мире таких ключей за сессию создаются десятки тысяч, и обычный data class
 * означал бы столько же короткоживущих объектов в куче. Value class
 * компилируется в примитивный long везде, где не требуется бокс, —
 * при этом сохраняется типобезопасность (нельзя случайно передать
 * координату блока туда, где ждут координату чанка).
 *
 * Упаковка: старшие 32 бита — X, младшие 32 — Z. Диапазон ±2 млрд чанков
 * заведомо перекрывает любой практический размер мира.
 */
@JvmInline
value class ChunkPos(val packed: Long) {

    constructor(x: Int, z: Int) : this(pack(x, z))

    val x: Int get() = (packed shr 32).toInt()

    val z: Int get() = (packed and 0xFFFFFFFFL).toInt()

    /** Мировая координата X западного края чанка. */
    val originX: Int get() = x shl WorldConstants.SECTION_SHIFT

    /** Мировая координата Z северного края чанка. */
    val originZ: Int get() = z shl WorldConstants.SECTION_SHIFT

    /** Центр чанка в мировых координатах — для сортировки по расстоянию. */
    val centerX: Double get() = originX + 8.0
    val centerZ: Double get() = originZ + 8.0

    fun offset(dx: Int, dz: Int): ChunkPos = ChunkPos(x + dx, z + dz)

    /**
     * Расстояние в чанках по метрике Чебышёва (максимум из модулей разностей).
     *
     * Именно эта метрика, а не евклидова, соответствует квадратной области
     * загрузки: при дальности прорисовки 12 загружается квадрат 25×25 чанков.
     * Квадрат предпочтительнее круга, потому что даёт предсказуемое число
     * чанков и, значит, предсказуемое потребление памяти.
     */
    fun chebyshevDistance(other: ChunkPos): Int =
        max(abs(x - other.x), abs(z - other.z))

    /** Квадрат евклидова расстояния — для приоритета загрузки: ближние вперёд. */
    fun squaredDistance(other: ChunkPos): Int {
        val dx = x - other.x
        val dz = z - other.z
        return dx * dx + dz * dz
    }

    override fun toString(): String = "ChunkPos($x, $z)"

    companion object {
        fun pack(x: Int, z: Int): Long =
            (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

        fun unpackX(packed: Long): Int = (packed shr 32).toInt()

        fun unpackZ(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

        /** Чанк, содержащий блок с указанными мировыми координатами. */
        fun ofBlock(blockX: Int, blockZ: Int): ChunkPos = ChunkPos(
            blockX shr WorldConstants.SECTION_SHIFT,
            blockZ shr WorldConstants.SECTION_SHIFT
        )

        fun ofBlock(blockX: Double, blockZ: Double): ChunkPos = ofBlock(
            com.voxelforge.core.math.MathUtils.floorInt(blockX),
            com.voxelforge.core.math.MathUtils.floorInt(blockZ)
        )
    }
}

/**
 * Позиция секции: чанк плюс вертикальный индекс 0..7.
 *
 * Назначение: ключ для мешей и очереди перестройки. Единица отрисовки —
 * именно секция, а не колонка: это даёт вчетверо более точное отсечение
 * по пирамиде видимости (когда игрок смотрит вдоль горизонта, верхние и
 * нижние секции колонки в кадр не попадают) и делает перестройку после
 * разрушения блока в 8 раз дешевле.
 */
@JvmInline
value class SectionPos(val packed: Long) {

    constructor(x: Int, y: Int, z: Int) : this(pack(x, y, z))

    val x: Int get() = (packed shr 36).toInt()
    val y: Int get() = ((packed shr 32) and 0xF).toInt()
    val z: Int get() = (packed and 0xFFFFFFFFL).toInt()

    val chunkPos: ChunkPos get() = ChunkPos(x, z)

    val originX: Int get() = x shl WorldConstants.SECTION_SHIFT
    val originY: Int get() = y shl WorldConstants.SECTION_SHIFT
    val originZ: Int get() = z shl WorldConstants.SECTION_SHIFT

    override fun toString(): String = "SectionPos($x, $y, $z)"

    companion object {
        /**
         * Упаковка: 28 бит X, 4 бита Y, 32 бита Z.
         * Y занимает ровно 4 бита, потому что секций в колонке восемь.
         * Диапазон X сужен до ±134 млн чанков — этого хватает с колоссальным
         * запасом, а взамен всё умещается в один long без второго поля.
         */
        fun pack(x: Int, y: Int, z: Int): Long =
            (x.toLong() shl 36) or ((y.toLong() and 0xF) shl 32) or (z.toLong() and 0xFFFFFFFFL)
    }
}
