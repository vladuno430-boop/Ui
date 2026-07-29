package com.voxelforge.core.world

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.world.WorldConstants.MAX_Y
import com.voxelforge.core.world.WorldConstants.SECTIONS_PER_CHUNK
import com.voxelforge.core.world.WorldConstants.SECTION_MASK
import com.voxelforge.core.world.WorldConstants.SECTION_SHIFT
import com.voxelforge.core.world.WorldConstants.columnIndex

/**
 * Стадия готовности чанка в конвейере загрузки.
 *
 * Конвейер асинхронный: генерация и мешинг идут в пуле потоков, а публикация
 * результата — в игровом. Явная стадия нужна, чтобы ни один этап не начался
 * раньше времени: нельзя считать освещение по недогенерированному рельефу и
 * нельзя строить меш, пока соседи не имеют рельефа (иначе на стыке чанков
 * появится сплошная стена из граней, которые тут же придётся перестраивать).
 */
enum class ChunkState {
    /** Объект создан, данных нет. */
    EMPTY,

    /** Идёт генерация в фоновом потоке. Повторно ставить в очередь нельзя. */
    GENERATING,

    /** Рельеф, руды и растительность на месте; освещение не рассчитано. */
    TERRAIN,

    /** Освещение рассчитано, чанк готов к построению меша. */
    LIT,

    /** Меш построен и загружен в GPU. */
    READY
}

/**
 * Вертикальная колонка мира 16×128×16.
 *
 * Назначение: объединяет восемь [ChunkSection], карту высот и карту биомов —
 * то есть всё, что относится к одной клетке горизонтальной сетки мира.
 * Колонка, а не отдельная секция, выбрана единицей загрузки и сохранения,
 * потому что генерация рельефа по своей природе работает со столбцами:
 * высота, биом и заливка водой считаются один раз на колонку.
 *
 * **Потокобезопасность.** Поля [state] и [dirtyMask] помечены @Volatile:
 * их пишет воркер, а читает игровой поток. Данные секций защищены протоколом
 * публикации — воркер заполняет чанк, который ещё никому не виден, и лишь
 * затем переводит [state] в готовое значение; запись в volatile создаёт
 * барьер памяти, поэтому читатель, увидевший новое состояние, гарантированно
 * видит и все записи в массивы, сделанные до него.
 */
class Chunk(val pos: ChunkPos) {

    /**
     * Секции снизу вверх. Элементы создаются по мере надобности:
     * у равнинного чанка верхние четыре секции так и остаются null.
     */
    @JvmField
    val sections = arrayOfNulls<ChunkSection>(SECTIONS_PER_CHUNK)

    /**
     * Высота верхнего блока, гасящего свет, +1 для каждой из 256 колонок.
     *
     * Это ключевая структура для освещения: солнечный свет заливается сверху
     * вниз до этой отметки без всякого волнового алгоритма. Без карты высот
     * пришлось бы гнать волну через все 128 уровней каждой колонки, что
     * примерно на порядок дороже.
     */
    @JvmField
    val heightMap = IntArray(WorldConstants.CHUNK_AREA)

    /** Биом каждой колонки — влияет на цвет травы, погоду и звуки. */
    @JvmField
    val biomes = ByteArray(WorldConstants.CHUNK_AREA)

    @Volatile
    @JvmField
    var state: ChunkState = ChunkState.EMPTY

    /**
     * Битовая маска секций, требующих перестройки меша.
     * Маска вместо массива Boolean — потому что проверка «есть ли вообще
     * что перестраивать» сводится к сравнению с нулём, а обход занятых
     * битов идёт через countTrailingZeroBits без перебора всех восьми.
     */
    @Volatile
    @JvmField
    var dirtyMask: Int = 0

    /**
     * Изменялся ли чанк игроком. Только такие чанки попадают в файл сохранения:
     * остальные детерминированно восстанавливаются из сида. Это сокращает
     * размер сохранения мира с сотен мегабайт до единиц.
     */
    @Volatile
    @JvmField
    var modified: Boolean = false

    /** Время последнего обращения — по нему работает выгрузка дальних чанков. */
    @JvmField
    @Volatile
    var lastAccessMillis: Long = System.currentTimeMillis()

    /**
     * Признак того, что чанк выгружен и его объекты возвращены в пул.
     * Защищает от гонки, когда воркер завершает работу уже после выгрузки.
     */
    @Volatile
    @JvmField
    var unloaded: Boolean = false

    // --- Доступ к блокам по локальным координатам (x, z в 0..15; y в 0..127) ---

    fun getBlock(x: Int, y: Int, z: Int): Int {
        if (y < 0 || y > MAX_Y) return Blocks.AIR
        val section = sections[y shr SECTION_SHIFT] ?: return Blocks.AIR
        return section.getBlock(x, y and SECTION_MASK, z)
    }

    /**
     * Устанавливает блок и обновляет карту высот.
     * Возвращает прежний идентификатор либо -1, если координата вне мира.
     */
    fun setBlock(x: Int, y: Int, z: Int, id: Int): Int {
        if (y < 0 || y > MAX_Y) return -1
        val si = y shr SECTION_SHIFT
        var section = sections[si]
        if (section == null) {
            if (id == Blocks.AIR) return Blocks.AIR
            section = ChunkSection()
            sections[si] = section
        }
        val old = section.setBlock(x, y and SECTION_MASK, z, id)
        if (old != id) {
            updateHeightMapOnChange(x, y, z, id)
            markSectionDirty(si)
        }
        return old
    }

    /** Быстрая установка при генерации: без пересчёта высот и пометки грязным. */
    fun setBlockDuringGeneration(x: Int, y: Int, z: Int, id: Int) {
        if (y < 0 || y > MAX_Y) return
        val si = y shr SECTION_SHIFT
        var section = sections[si]
        if (section == null) {
            if (id == Blocks.AIR) return
            section = ChunkSection()
            sections[si] = section
        }
        section.setBlockRaw(
            WorldConstants.sectionIndex(x, y and SECTION_MASK, z),
            id
        )
    }

    fun getOrCreateSection(index: Int): ChunkSection {
        var s = sections[index]
        if (s == null) {
            s = ChunkSection()
            sections[index] = s
        }
        return s
    }

    // --- Освещение ---

    fun getSkyLight(x: Int, y: Int, z: Int): Int {
        // Выше мира всегда открытое небо, ниже — темнота коренной породы.
        if (y > MAX_Y) return WorldConstants.MAX_LIGHT
        if (y < 0) return 0
        val section = sections[y shr SECTION_SHIFT]
            ?: return if (y >= heightMap[columnIndex(x, z)]) WorldConstants.MAX_LIGHT else 0
        return section.getSkyLight(x, y and SECTION_MASK, z)
    }

    fun getBlockLight(x: Int, y: Int, z: Int): Int {
        if (y < 0 || y > MAX_Y) return 0
        val section = sections[y shr SECTION_SHIFT] ?: return 0
        return section.getBlockLight(x, y and SECTION_MASK, z)
    }

    fun setSkyLight(x: Int, y: Int, z: Int, value: Int) {
        if (y < 0 || y > MAX_Y) return
        getOrCreateSection(y shr SECTION_SHIFT).setSkyLight(x, y and SECTION_MASK, z, value)
    }

    fun setBlockLight(x: Int, y: Int, z: Int, value: Int) {
        if (y < 0 || y > MAX_Y) return
        getOrCreateSection(y shr SECTION_SHIFT).setBlockLight(x, y and SECTION_MASK, z, value)
    }

    // --- Карта высот ---

    fun getHeight(x: Int, z: Int): Int = heightMap[columnIndex(x, z)]

    /**
     * Полный пересчёт карты высот. Вызывается один раз после генерации:
     * спускаемся сверху до первого блока, гасящего свет.
     */
    fun recomputeHeightMap() {
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                var y = MAX_Y
                while (y >= 0) {
                    val id = getBlock(x, y, z)
                    if (Blocks.OPACITY[id] > 0) break
                    y--
                }
                heightMap[columnIndex(x, z)] = y + 1
            }
        }
    }

    /**
     * Инкрементальное обновление карты высот при изменении одного блока.
     *
     * Здесь важна асимметрия: постановка непрозрачного блока выше текущей
     * отметки — это просто присваивание. А вот разрушение блока **на самой
     * отметке** требует спуска вниз в поисках новой поверхности, потому что
     * под ним может оказаться пещера произвольной глубины. Разделение этих
     * случаев экономит спуск в подавляющем большинстве изменений.
     */
    private fun updateHeightMapOnChange(x: Int, y: Int, z: Int, newId: Int) {
        val ci = columnIndex(x, z)
        val current = heightMap[ci]
        val opaque = Blocks.OPACITY[newId] > 0
        if (opaque) {
            if (y >= current) heightMap[ci] = y + 1
        } else if (y == current - 1) {
            var ny = y - 1
            while (ny >= 0 && Blocks.OPACITY[getBlock(x, ny, z)] == 0) ny--
            heightMap[ci] = ny + 1
        }
    }

    // --- Биомы ---

    fun getBiome(x: Int, z: Int): Int = biomes[columnIndex(x, z)].toInt() and 0xFF

    fun setBiome(x: Int, z: Int, biomeId: Int) {
        biomes[columnIndex(x, z)] = biomeId.toByte()
    }

    // --- Управление перестройкой мешей ---

    fun markSectionDirty(sectionIndex: Int) {
        if (sectionIndex in 0 until SECTIONS_PER_CHUNK) {
            dirtyMask = dirtyMask or (1 shl sectionIndex)
        }
    }

    fun markAllSectionsDirty() {
        dirtyMask = (1 shl SECTIONS_PER_CHUNK) - 1
    }

    fun clearSectionDirty(sectionIndex: Int) {
        dirtyMask = dirtyMask and (1 shl sectionIndex).inv()
    }

    val hasDirtySections: Boolean get() = dirtyMask != 0

    /**
     * Индекс самой верхней непустой секции +1. Позволяет циклам мешинга
     * и освещения не обходить заведомо пустое небо над рельефом.
     */
    fun topNonEmptySection(): Int {
        for (i in SECTIONS_PER_CHUNK - 1 downTo 0) {
            val s = sections[i]
            if (s != null && !s.isEmpty) return i + 1
        }
        return 0
    }

    /** Суммарное число непустых вокселей — используется в отладочной статистике. */
    fun nonAirBlockCount(): Int {
        var total = 0
        for (s in sections) total += s?.nonAirCount ?: 0
        return total
    }

    /** Подготавливает объект к повторному использованию из пула. */
    fun resetForReuse() {
        for (i in sections.indices) sections[i] = null
        java.util.Arrays.fill(heightMap, 0)
        java.util.Arrays.fill(biomes, 0)
        state = ChunkState.EMPTY
        dirtyMask = 0
        modified = false
        unloaded = false
    }

    override fun toString(): String = "Chunk(${pos.x}, ${pos.z}, $state)"
}
