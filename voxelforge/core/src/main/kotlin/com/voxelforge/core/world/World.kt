package com.voxelforge.core.world

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.gen.WorldGenerator
import com.voxelforge.core.util.ObjectPool
import com.voxelforge.core.world.WorldConstants.MAX_Y
import com.voxelforge.core.world.WorldConstants.SECTION_MASK
import com.voxelforge.core.world.WorldConstants.SECTION_SHIFT
import java.util.concurrent.ConcurrentHashMap

/**
 * Слушатель изменений мира.
 *
 * Назначение: развязать модель и рендер. Мир не знает ни про OpenGL,
 * ни про очередь перестройки мешей — он лишь сообщает, что секция изменилась.
 * Подписчиком выступает рендер, но с тем же успехом им может стать
 * система звука или сохранение. Прямой вызов рендера из [World] сделал бы
 * невозможным ни тестирование логики на JVM, ни выделение :core в отдельный
 * модуль.
 */
interface WorldListener {
    /** Секция изменилась и требует перестройки меша. */
    fun onSectionChanged(chunkX: Int, sectionY: Int, chunkZ: Int)

    /** Блок изменён — повод проиграть звук и выбросить частицы. */
    fun onBlockChanged(x: Int, y: Int, z: Int, oldId: Int, newId: Int)

    /** Чанк загружен и готов к отрисовке. */
    fun onChunkLoaded(chunk: Chunk)

    /** Чанк выгружен — связанные с ним ресурсы GPU нужно освободить. */
    fun onChunkUnloaded(chunk: Chunk)
}

/**
 * Контейнер загруженной части мира.
 *
 * Назначение: хранит карту чанков, даёт доступ к блокам по мировым
 * координатам и распространяет изменения. Это модель в чистом виде —
 * ни потоков, ни ввода-вывода, ни графики здесь нет; всем этим управляет
 * ChunkManager, который использует [World] как хранилище.
 *
 * **Модель потокобезопасности.** Карта чанков — ConcurrentHashMap, поэтому
 * поиск и публикация безопасны из любого потока. Содержимое чанка
 * защищено дисциплиной: воркер заполняет чанк **до** публикации,
 * а после публикации изменять блоки вправе только игровой поток.
 * Воркеры мешинга читают уже опубликованные данные; гонка чтения с записью
 * при этом возможна лишь на одном байте блока и приводит в худшем случае
 * к одному кадру с устаревшей геометрией, которая тут же перестраивается, —
 * это осознанный размен, потому что блокировка на каждом чтении вокселя
 * стоила бы кратно дороже всей экономии.
 *
 * @param seed      сид мира
 * @param generator генератор; передаётся снаружи, чтобы в тестах можно было
 *                  подставить детерминированную заглушку
 */
class World(
    @JvmField val seed: Long,
    @JvmField val generator: WorldGenerator = WorldGenerator(seed)
) : BlockAccess {

    private val chunks = ConcurrentHashMap<Long, Chunk>()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<WorldListener>()

    /**
     * Пул чанков. Один чанк — это до 8 секций по 8 КБ; при полёте над миром
     * их создаются и выбрасываются десятки в секунду. Пул превращает этот
     * поток мусора в переиспользование одних и тех же объектов.
     */
    private val chunkPool = ObjectPool<Chunk>(
        maxSize = CHUNK_POOL_SIZE,
        factory = { Chunk(ChunkPos(0, 0)) },
        reset = { it.resetForReuse() }
    )

    /** Игровое время в тиках; используется циклом дня и ночи и погодой. */
    @Volatile
    @JvmField
    var timeTicks: Long = 0

    fun addListener(listener: WorldListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WorldListener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------
    // Доступ к чанкам
    // ------------------------------------------------------------------

    /** Загруженный чанк либо null. Не запускает генерацию. */
    fun getChunk(chunkX: Int, chunkZ: Int): Chunk? = chunks[ChunkPos.pack(chunkX, chunkZ)]

    fun getChunk(pos: ChunkPos): Chunk? = chunks[pos.packed]

    /** Чанк, содержащий указанный блок. */
    fun getChunkAtBlock(x: Int, z: Int): Chunk? =
        chunks[ChunkPos.pack(x shr SECTION_SHIFT, z shr SECTION_SHIFT)]

    val loadedChunkCount: Int get() = chunks.size

    /** Снимок списка загруженных чанков для обхода в рендере и сохранении. */
    fun loadedChunks(): Collection<Chunk> = chunks.values

    fun isChunkLoaded(chunkX: Int, chunkZ: Int): Boolean =
        chunks.containsKey(ChunkPos.pack(chunkX, chunkZ))

    /**
     * Выделяет объект чанка под указанную позицию, **не публикуя** его.
     * Дальше объект уходит воркеру на генерацию, и лишь готовым попадает
     * в карту через [publishChunk].
     */
    fun allocateChunk(pos: ChunkPos): Chunk {
        val pooled = chunkPool.obtain()
        // ChunkPos — неизменяемое поле, поэтому переиспользуем объект только
        // при совпадении позиции, иначе создаём новый.
        return if (pooled.pos.packed == pos.packed) {
            pooled.resetForReuse()
            pooled
        } else {
            Chunk(pos)
        }
    }

    /** Публикует готовый чанк и уведомляет подписчиков. */
    fun publishChunk(chunk: Chunk) {
        chunks[chunk.pos.packed] = chunk
        for (l in listeners) l.onChunkLoaded(chunk)
    }

    /**
     * Выгружает чанк. Возвращает выгруженный объект, чтобы вызывающий код
     * успел сохранить его при необходимости до возврата в пул.
     */
    fun unloadChunk(pos: ChunkPos): Chunk? {
        val chunk = chunks.remove(pos.packed) ?: return null
        chunk.unloaded = true
        for (l in listeners) l.onChunkUnloaded(chunk)
        return chunk
    }

    /** Возвращает выгруженный чанк в пул. Вызывать только после сохранения. */
    fun recycleChunk(chunk: Chunk) {
        chunkPool.release(chunk)
    }

    fun clearAllChunks() {
        for (chunk in chunks.values) {
            chunk.unloaded = true
            for (l in listeners) l.onChunkUnloaded(chunk)
        }
        chunks.clear()
        chunkPool.clear()
    }

    // ------------------------------------------------------------------
    // Доступ к блокам (реализация BlockAccess)
    // ------------------------------------------------------------------

    override fun getBlock(x: Int, y: Int, z: Int): Int {
        if (y < 0 || y > MAX_Y) return Blocks.AIR
        val chunk = getChunkAtBlock(x, z) ?: return Blocks.AIR
        return chunk.getBlock(x and SECTION_MASK, y, z and SECTION_MASK)
    }

    override fun getSkyLight(x: Int, y: Int, z: Int): Int {
        if (y > MAX_Y) return WorldConstants.MAX_LIGHT
        if (y < 0) return 0
        val chunk = getChunkAtBlock(x, z) ?: return WorldConstants.MAX_LIGHT
        return chunk.getSkyLight(x and SECTION_MASK, y, z and SECTION_MASK)
    }

    override fun getBlockLight(x: Int, y: Int, z: Int): Int {
        if (y < 0 || y > MAX_Y) return 0
        val chunk = getChunkAtBlock(x, z) ?: return 0
        return chunk.getBlockLight(x and SECTION_MASK, y, z and SECTION_MASK)
    }

    override fun getBiome(x: Int, z: Int): Int {
        val chunk = getChunkAtBlock(x, z) ?: return com.voxelforge.core.gen.Biomes.PLAINS
        return chunk.getBiome(x and SECTION_MASK, z and SECTION_MASK)
    }

    override fun getHeight(x: Int, z: Int): Int {
        val chunk = getChunkAtBlock(x, z) ?: return WorldConstants.SEA_LEVEL
        return chunk.getHeight(x and SECTION_MASK, z and SECTION_MASK)
    }

    /**
     * Устанавливает блок и помечает затронутые секции на перестройку.
     *
     * Тонкость, из-за которой этот метод не сводится к одной строке:
     * блок на **границе** секции влияет на видимость граней в соседней
     * секции — та обязана перестроиться тоже. Без этого после разрушения
     * блока у края чанка в стене остаётся чёрная дыра: соседняя секция
     * по-прежнему считает свою грань скрытой.
     *
     * @return true, если блок действительно изменился
     */
    fun setBlock(x: Int, y: Int, z: Int, id: Int): Boolean {
        if (y < 0 || y > MAX_Y) return false
        val chunk = getChunkAtBlock(x, z) ?: return false

        val lx = x and SECTION_MASK
        val lz = z and SECTION_MASK
        val old = chunk.setBlock(lx, y, lz, id)
        if (old == id || old < 0) return false

        chunk.modified = true
        chunk.lastAccessMillis = System.currentTimeMillis()

        val sectionY = y shr SECTION_SHIFT
        notifySectionChanged(chunk.pos.x, sectionY, chunk.pos.z)
        markNeighboursDirty(x, y, z, lx, lz, sectionY)

        for (l in listeners) l.onBlockChanged(x, y, z, old, id)
        return true
    }

    /**
     * Помечает соседние секции, если изменённый блок лежит на их границе.
     * Проверяются все три оси независимо: блок в углу чанка задевает
     * до трёх соседних секций сразу.
     */
    private fun markNeighboursDirty(
        x: Int, y: Int, z: Int,
        lx: Int, lz: Int,
        sectionY: Int
    ) {
        val cx = x shr SECTION_SHIFT
        val cz = z shr SECTION_SHIFT

        if (lx == 0) markSection(cx - 1, sectionY, cz)
        if (lx == SECTION_MASK) markSection(cx + 1, sectionY, cz)
        if (lz == 0) markSection(cx, sectionY, cz - 1)
        if (lz == SECTION_MASK) markSection(cx, sectionY, cz + 1)

        val ly = y and SECTION_MASK
        if (ly == 0 && sectionY > 0) markSection(cx, sectionY - 1, cz)
        if (ly == SECTION_MASK && sectionY < WorldConstants.SECTIONS_PER_CHUNK - 1) {
            markSection(cx, sectionY + 1, cz)
        }
    }

    private fun markSection(chunkX: Int, sectionY: Int, chunkZ: Int) {
        val chunk = getChunk(chunkX, chunkZ) ?: return
        chunk.markSectionDirty(sectionY)
        notifySectionChanged(chunkX, sectionY, chunkZ)
    }

    private fun notifySectionChanged(chunkX: Int, sectionY: Int, chunkZ: Int) {
        for (l in listeners) l.onSectionChanged(chunkX, sectionY, chunkZ)
    }

    /**
     * Разрушает блок и возвращает идентификатор выпадающего предмета
     * (-1, если ничего не выпадает или блок неразрушим).
     */
    fun breakBlock(x: Int, y: Int, z: Int): Int {
        val id = getBlock(x, y, z)
        if (id == Blocks.AIR) return -1
        val def = Blocks.get(id)
        if (!def.breakable) return -1
        if (!setBlock(x, y, z, Blocks.AIR)) return -1
        return def.dropBlockId
    }

    /**
     * Ставит блок, если целевая клетка свободна или заменяема.
     * Проверку пересечения с игроком делает вызывающий код — [World]
     * ничего не знает о сущностях.
     */
    fun placeBlock(x: Int, y: Int, z: Int, id: Int): Boolean {
        val current = getBlock(x, y, z)
        if (current != Blocks.AIR && !Blocks.REPLACEABLE[current]) return false
        return setBlock(x, y, z, id)
    }

    /** Есть ли все восемь соседей по горизонтали — условие построения меша. */
    fun hasAllNeighbours(chunkX: Int, chunkZ: Int): Boolean {
        for (dz in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dz == 0) continue
                if (!isChunkLoaded(chunkX + dx, chunkZ + dz)) return false
            }
        }
        return true
    }

    fun poolStats(): String = chunkPool.toString()

    companion object {
        /**
         * Ёмкость пула чанков. При дальности прорисовки 12 в мире около
         * 625 чанков; пул на 128 покрывает типичный поток загрузки-выгрузки
         * при перемещении игрока, не удерживая память впустую.
         */
        private const val CHUNK_POOL_SIZE = 128
    }
}
