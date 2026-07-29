package com.voxelforge.core.world

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.world.WorldConstants.MAX_LIGHT
import com.voxelforge.core.world.WorldConstants.SECTION_VOLUME
import com.voxelforge.core.world.WorldConstants.sectionIndex

/**
 * Куб вокселей 16×16×16 — минимальная единица хранения и отрисовки.
 *
 * Назначение: хранит идентификаторы блоков и их освещённость. Это самый
 * «дорогой по памяти» класс игры, поэтому его устройство подчинено экономии:
 *
 *  1. **Ленивое выделение.** У пустой секции массивы равны null. В типичном
 *     мире выше рельефа пустует больше половины секций: при дальности
 *     прорисовки 12 чанков это около 1500 секций, то есть 12 МБ, которые
 *     иначе были бы заняты нулями. Секция освобождает массивы автоматически,
 *     как только последний блок в ней разрушен.
 *
 *  2. **Один байт на блок.** Идентификаторов 26, потолок — 256. Хранение
 *     в Short «на будущее» удвоило бы расход без единого нового блока.
 *
 *  3. **Свет упакован в полубайты.** Оба вида освещения (солнечное и от
 *     источников) лежат в одном байте: старший полубайт — небо, младший —
 *     блоки. Значения 0..15 занимают ровно 4 бита, а один массив вместо двух
 *     означает одно чтение кэш-линии вместо двух при построении меша.
 *
 * Итого полная секция — 8 КБ вместо 24 КБ при «наивном» хранении.
 *
 * **Потокобезопасность.** Класс не синхронизирован. Модель доступа такая:
 * генерация заполняет секцию в потоке-воркере до того, как чанк опубликован
 * в карте мира; после публикации запись идёт только из игрового потока, а
 * воркеры мешинга читают снимок. Поле [nonAirCount] помечено @Volatile,
 * чтобы мешер не увидел устаревшее «секция пуста» и не пропустил геометрию.
 */
class ChunkSection {

    /**
     * Идентификаторы блоков, индекс — [sectionIndex]. null означает,
     * что вся секция состоит из воздуха.
     */
    @JvmField
    var blocks: ByteArray? = null

    /**
     * Освещённость: старший полубайт — солнечный свет, младший — от источников.
     * null означает «свет ещё не рассчитан» либо «секция пуста».
     */
    @JvmField
    var light: ByteArray? = null

    /**
     * Счётчик блоков, отличных от воздуха. Позволяет мгновенно ответить
     * на главный вопрос конвейера — «нужно ли строить меш для этой секции».
     */
    @Volatile
    @JvmField
    var nonAirCount: Int = 0

    /**
     * Счётчик блоков, требующих отрисовки в полупрозрачном проходе.
     * Если он нулевой, второй проход для секции пропускается целиком.
     */
    @JvmField
    var translucentCount: Int = 0

    /** Секция не содержит ничего, кроме воздуха. */
    val isEmpty: Boolean get() = nonAirCount == 0

    /**
     * Читает идентификатор блока. Координаты обязаны быть локальными 0..15;
     * проверка вынесена наружу, потому что вызывающий код (мешер, освещение)
     * уже гарантирует диапазон, а лишняя ветка в таком горячем месте заметна.
     */
    fun getBlock(x: Int, y: Int, z: Int): Int {
        val b = blocks ?: return Blocks.AIR
        return b[sectionIndex(x, y, z)].toInt() and 0xFF
    }

    fun getBlockAt(index: Int): Int {
        val b = blocks ?: return Blocks.AIR
        return b[index].toInt() and 0xFF
    }

    /**
     * Записывает блок и поддерживает счётчики в согласованном состоянии.
     * Возвращает прежний идентификатор — вызывающему коду он нужен, чтобы
     * решить, надо ли пересчитывать освещение.
     */
    fun setBlock(x: Int, y: Int, z: Int, id: Int): Int {
        val index = sectionIndex(x, y, z)
        // Установка воздуха в уже пустую секцию не должна выделять память.
        if (blocks == null) {
            if (id == Blocks.AIR) return Blocks.AIR
            allocate()
        }
        val b = blocks!!
        val old = b[index].toInt() and 0xFF
        if (old == id) return old
        b[index] = id.toByte()

        if (old == Blocks.AIR) nonAirCount++
        if (id == Blocks.AIR) nonAirCount--
        if (Blocks.LAYER[old] == com.voxelforge.core.block.RenderLayer.TRANSLUCENT) translucentCount--
        if (Blocks.LAYER[id] == com.voxelforge.core.block.RenderLayer.TRANSLUCENT) translucentCount++

        // Секция опустела — возвращаем память системе немедленно, не дожидаясь
        // выгрузки чанка. Игрок, выкопавший карьер, не должен платить за это
        // постоянно занятыми килобайтами.
        if (nonAirCount == 0) release()
        return old
    }

    /**
     * Быстрая запись при генерации: без пересчёта счётчиков и без проверок.
     * Генератор заполняет секцию плотным потоком и в конце один раз вызывает
     * [recountContents]. Это убирает из внутреннего цикла три ветвления,
     * что на 4096 вокселях × 1500 секций даёт заметную экономию.
     */
    fun setBlockRaw(index: Int, id: Int) {
        if (blocks == null) {
            if (id == Blocks.AIR) return
            allocate()
        }
        blocks!![index] = id.toByte()
    }

    /** Пересчитывает счётчики после массового заполнения. */
    fun recountContents() {
        val b = blocks
        if (b == null) {
            nonAirCount = 0
            translucentCount = 0
            return
        }
        var solid = 0
        var translucent = 0
        for (i in 0 until SECTION_VOLUME) {
            val id = b[i].toInt() and 0xFF
            if (id != Blocks.AIR) {
                solid++
                if (Blocks.LAYER[id] == com.voxelforge.core.block.RenderLayer.TRANSLUCENT) translucent++
            }
        }
        nonAirCount = solid
        translucentCount = translucent
        if (solid == 0) release()
    }

    // --- Освещение ---

    /** Солнечный свет 0..15 (старший полубайт). */
    fun getSkyLight(x: Int, y: Int, z: Int): Int {
        val l = light ?: return MAX_LIGHT   // нет данных — считаем открытым небом
        return (l[sectionIndex(x, y, z)].toInt() shr 4) and 0xF
    }

    /** Свет от источников 0..15 (младший полубайт). */
    fun getBlockLight(x: Int, y: Int, z: Int): Int {
        val l = light ?: return 0
        return l[sectionIndex(x, y, z)].toInt() and 0xF
    }

    fun setSkyLight(x: Int, y: Int, z: Int, value: Int) {
        val l = ensureLight()
        val i = sectionIndex(x, y, z)
        l[i] = ((l[i].toInt() and 0x0F) or ((value and 0xF) shl 4)).toByte()
    }

    fun setBlockLight(x: Int, y: Int, z: Int, value: Int) {
        val l = ensureLight()
        val i = sectionIndex(x, y, z)
        l[i] = ((l[i].toInt() and 0xF0) or (value and 0xF)).toByte()
    }

    /** Оба значения разом — экономит чтение-модификацию-запись при заливке. */
    fun setLight(index: Int, sky: Int, block: Int) {
        val l = ensureLight()
        l[index] = (((sky and 0xF) shl 4) or (block and 0xF)).toByte()
    }

    fun getLightPacked(index: Int): Int {
        val l = light ?: return MAX_LIGHT shl 4
        return l[index].toInt() and 0xFF
    }

    /**
     * Заполняет всю секцию заданным уровнем солнечного света.
     * Применяется к секциям выше рельефа: там свет заведомо максимален,
     * и волновой алгоритм заливки для них — лишняя работа.
     */
    fun fillSkyLight(value: Int) {
        val l = ensureLight()
        java.util.Arrays.fill(l, (((value and 0xF) shl 4)).toByte())
    }

    private fun ensureLight(): ByteArray {
        var l = light
        if (l == null) {
            l = ByteArray(SECTION_VOLUME)
            light = l
        }
        return l
    }

    private fun allocate() {
        blocks = ByteArray(SECTION_VOLUME)
    }

    private fun release() {
        blocks = null
        light = null
        translucentCount = 0
    }

    /**
     * Возвращает секцию в исходное состояние для повторного использования
     * из пула объектов. Массивы **сохраняются**: именно ради этого пул и
     * существует — выделение 8 КБ на каждый входящий чанк при полёте
     * над миром давало бы постоянное давление на сборщик мусора.
     */
    fun resetForReuse() {
        blocks?.let { java.util.Arrays.fill(it, 0) }
        light?.let { java.util.Arrays.fill(it, 0) }
        nonAirCount = 0
        translucentCount = 0
    }

    /** Гарантирует наличие массива блоков — вызывается генератором перед заливкой. */
    fun ensureAllocated() {
        if (blocks == null) allocate()
    }
}
