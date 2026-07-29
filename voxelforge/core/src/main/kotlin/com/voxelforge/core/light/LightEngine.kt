package com.voxelforge.core.light

import com.voxelforge.core.block.BlockFace
import com.voxelforge.core.block.Blocks
import com.voxelforge.core.world.Chunk
import com.voxelforge.core.world.World
import com.voxelforge.core.world.WorldConstants.MAX_LIGHT
import com.voxelforge.core.world.WorldConstants.MAX_Y
import com.voxelforge.core.world.WorldConstants.SECTION_MASK
import com.voxelforge.core.world.WorldConstants.SECTION_SHIFT

/**
 * Растущая очередь координат для волнового алгоритма распространения света.
 *
 * Назначение: заменить `ArrayDeque<LightNode>` на структуру без объектов.
 * При первичном освещении чанка через очередь проходят десятки тысяч записей;
 * на каждую пришлось бы по объекту-узлу, что дало бы мегабайты мусора
 * на один чанк. Три параллельных массива примитивов исключают аллокации
 * полностью и вдобавок укладываются в кэш линейно.
 *
 * Очередь кольцевая: после извлечения элемента голова сдвигается, а память
 * переиспользуется, поэтому размер массива определяется не общим числом
 * обработанных узлов, а лишь пиковой шириной волны.
 */
private class LightQueue(initialCapacity: Int = 4096) {

    private var xs = IntArray(initialCapacity)
    private var ys = IntArray(initialCapacity)
    private var zs = IntArray(initialCapacity)
    private var levels = ByteArray(initialCapacity)

    private var head = 0
    private var tail = 0
    private var count = 0

    val size: Int get() = count
    val isEmpty: Boolean get() = count == 0

    fun push(x: Int, y: Int, z: Int, level: Int) {
        if (count == xs.size) grow()
        xs[tail] = x
        ys[tail] = y
        zs[tail] = z
        levels[tail] = level.toByte()
        tail = (tail + 1) % xs.size
        count++
    }

    /** Извлекает элемент; компоненты читаются через [x], [y], [z], [level]. */
    fun pop() {
        x = xs[head]
        y = ys[head]
        z = zs[head]
        level = levels[head].toInt()
        head = (head + 1) % xs.size
        count--
    }

    @JvmField var x = 0
    @JvmField var y = 0
    @JvmField var z = 0
    @JvmField var level = 0

    fun clear() {
        head = 0
        tail = 0
        count = 0
    }

    private fun grow() {
        val newCapacity = xs.size * 2
        val nx = IntArray(newCapacity)
        val ny = IntArray(newCapacity)
        val nz = IntArray(newCapacity)
        val nl = ByteArray(newCapacity)
        // Кольцо разворачивается в линейный массив: сначала хвост от головы
        // до конца, затем начало.
        for (i in 0 until count) {
            val src = (head + i) % xs.size
            nx[i] = xs[src]; ny[i] = ys[src]; nz[i] = zs[src]; nl[i] = levels[src]
        }
        xs = nx; ys = ny; zs = nz; levels = nl
        head = 0
        tail = count
    }
}

/**
 * Система освещения мира.
 *
 * Назначение: рассчитать два независимых канала света для каждого вокселя —
 * солнечный ([skyLight]) и от источников ([blockLight]).
 *
 * **Почему два канала, а не один.** Их нельзя складывать при хранении, потому
 * что солнечный свет меняется со временем суток, а свет факела — нет. Если
 * сложить их при расчёте, то ночью пришлось бы пересчитывать освещение всего
 * мира заново — это десятки миллисекунд каждый игровой тик. Раздельное
 * хранение позволяет смешивать каналы уже в шейдере: `max(sky * dayFactor,
 * block)`. Смена дня и ночи становится изменением одной uniform-переменной
 * и не стоит ничего.
 *
 * **Алгоритм.** Классический поиск в ширину по решётке. Свет убывает на
 * единицу за блок (плюс непрозрачность материала), поэтому фронт волны
 * обрабатывается строго по убыванию уровня, и каждый воксель посещается
 * не более одного раза на канал.
 *
 * Отдельного внимания заслуживает **удаление света**. Когда игрок ломает
 * факел, недостаточно обнулить одну клетку: нужно погасить всю освещённую им
 * область, но не тронуть освещение от других источников. Это делается
 * в два прохода — сначала волна затемнения помечает область и собирает
 * встреченные «чужие» источники, затем от них запускается обычная волна
 * распространения. Одним проходом задача не решается в принципе.
 *
 * **Потокобезопасность.** Экземпляр не потокобезопасен: очереди — общее
 * изменяемое состояние. Освещение выполняется в игровом потоке (при
 * изменении блока) либо в потоке-воркере на ещё не опубликованном чанке.
 */
class LightEngine(private val world: World) {

    private val skyQueue = LightQueue()
    private val blockQueue = LightQueue()
    private val removalQueue = LightQueue()

    /**
     * Полный расчёт освещения свежесгенерированного чанка.
     *
     * Порядок важен: сначала солнечный свет заливается по карте высот
     * (это дёшево и покрывает большую часть мира), и лишь затем волна
     * растекается в тень — под кроны деревьев, в пещеры, под навесы.
     */
    fun computeInitialLight(chunk: Chunk) {
        seedSkyLightColumns(chunk)
        propagateSky()
        seedBlockLightSources(chunk)
        propagateBlock()
    }

    /**
     * Заливает солнечный свет по вертикали.
     *
     * Ключевая оптимизация: карта высот уже знает, где кончается открытое
     * небо, поэтому всё выше отметки получает максимум одним присваиванием,
     * а волновой алгоритм запускается только от граничных вокселей. Без
     * карты высот пришлось бы гнать волну сверху вниз через все 128 уровней
     * каждой из 256 колонок — примерно на порядок дороже.
     */
    private fun seedSkyLightColumns(chunk: Chunk) {
        val baseX = chunk.pos.originX
        val baseZ = chunk.pos.originZ

        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val height = chunk.getHeight(lx, lz)
                // Всё выше поверхности освещено полностью.
                for (y in height..MAX_Y) {
                    chunk.setSkyLight(lx, y, lz, MAX_LIGHT)
                }
                // Нижняя граница освещённой колонки — источник волны:
                // отсюда свет может уйти вбок, под навес или в пещеру.
                if (height <= MAX_Y) {
                    skyQueue.push(baseX + lx, height, baseZ + lz, MAX_LIGHT)
                }
                // Полупрозрачные блоки (вода, листва) свет пропускают
                // с ослаблением — заливаем их отдельно, спускаясь вниз.
                var y = height - 1
                var level = MAX_LIGHT
                while (y >= 0) {
                    val id = chunk.getBlock(lx, y, lz)
                    val opacity = Blocks.OPACITY[id]
                    if (opacity >= MAX_LIGHT) break
                    level -= maxOf(opacity, 1)
                    if (level <= 0) break
                    chunk.setSkyLight(lx, y, lz, level)
                    skyQueue.push(baseX + lx, y, baseZ + lz, level)
                    y--
                }
            }
        }
    }

    /** Ставит в очередь все блоки-источники света чанка. */
    private fun seedBlockLightSources(chunk: Chunk) {
        val baseX = chunk.pos.originX
        val baseZ = chunk.pos.originZ

        for (sectionIndex in chunk.sections.indices) {
            val section = chunk.sections[sectionIndex] ?: continue
            if (section.isEmpty) continue
            val baseY = sectionIndex shl SECTION_SHIFT

            for (ly in 0 until 16) {
                for (lz in 0 until 16) {
                    for (lx in 0 until 16) {
                        val id = section.getBlock(lx, ly, lz)
                        val emission = Blocks.EMISSION[id]
                        if (emission > 0) {
                            section.setBlockLight(lx, ly, lz, emission)
                            blockQueue.push(baseX + lx, baseY + ly, baseZ + lz, emission)
                        }
                    }
                }
            }
        }
    }

    /**
     * Волна распространения солнечного света.
     *
     * Особенность канала неба: **при движении строго вниз свет не убывает**,
     * если блок прозрачен. Это не оптимизация, а требование к картинке —
     * иначе дно глубокой шахты под открытым небом оказалось бы тёмным,
     * хотя над ним нет ни одного препятствия.
     */
    private fun propagateSky() {
        while (!skyQueue.isEmpty) {
            skyQueue.pop()
            val x = skyQueue.x
            val y = skyQueue.y
            val z = skyQueue.z
            val level = skyQueue.level
            if (level <= 1) continue

            for (face in BlockFace.VALUES) {
                val nx = x + face.dx
                val ny = y + face.dy
                val nz = z + face.dz
                if (ny < 0 || ny > MAX_Y) continue

                val chunk = world.getChunkAtBlock(nx, nz) ?: continue
                val clx = nx and SECTION_MASK
                val clz = nz and SECTION_MASK

                val id = chunk.getBlock(clx, ny, clz)
                val opacity = Blocks.OPACITY[id]
                if (opacity >= MAX_LIGHT) continue

                // Вертикальный спуск в полностью прозрачной среде сохраняет
                // уровень; во всех прочих направлениях свет убывает.
                val next = if (face == BlockFace.DOWN && opacity == 0 && level == MAX_LIGHT) {
                    MAX_LIGHT
                } else {
                    level - maxOf(opacity, 1)
                }
                if (next <= 0) continue

                if (chunk.getSkyLight(clx, ny, clz) >= next) continue
                chunk.setSkyLight(clx, ny, clz, next)
                chunk.markSectionDirty(ny shr SECTION_SHIFT)
                skyQueue.push(nx, ny, nz, next)
            }
        }
    }

    /** Волна распространения света от источников. Убывает во всех направлениях. */
    private fun propagateBlock() {
        while (!blockQueue.isEmpty) {
            blockQueue.pop()
            val x = blockQueue.x
            val y = blockQueue.y
            val z = blockQueue.z
            val level = blockQueue.level
            if (level <= 1) continue

            for (face in BlockFace.VALUES) {
                val nx = x + face.dx
                val ny = y + face.dy
                val nz = z + face.dz
                if (ny < 0 || ny > MAX_Y) continue

                val chunk = world.getChunkAtBlock(nx, nz) ?: continue
                val clx = nx and SECTION_MASK
                val clz = nz and SECTION_MASK

                val id = chunk.getBlock(clx, ny, clz)
                val opacity = Blocks.OPACITY[id]
                if (opacity >= MAX_LIGHT) continue

                val next = level - maxOf(opacity, 1)
                if (next <= 0) continue
                if (chunk.getBlockLight(clx, ny, clz) >= next) continue

                chunk.setBlockLight(clx, ny, clz, next)
                chunk.markSectionDirty(ny shr SECTION_SHIFT)
                blockQueue.push(nx, ny, nz, next)
            }
        }
    }

    // ------------------------------------------------------------------
    // Инкрементальное обновление при изменении блока
    // ------------------------------------------------------------------

    /**
     * Пересчитывает освещение после установки или разрушения блока.
     *
     * Разбор случаев здесь не формальность: каждый требует своей волны.
     *
     * @param oldId прежний блок
     * @param newId новый блок
     */
    fun onBlockChanged(x: Int, y: Int, z: Int, oldId: Int, newId: Int) {
        val oldEmission = Blocks.EMISSION[oldId]
        val newEmission = Blocks.EMISSION[newId]
        val oldOpacity = Blocks.OPACITY[oldId]
        val newOpacity = Blocks.OPACITY[newId]

        // 1. Убрали источник света — гасим освещённую им область.
        if (oldEmission > 0 && newEmission < oldEmission) {
            removeBlockLight(x, y, z)
        }

        // 2. Блок стал плотнее — свет, проходивший сквозь него, надо убрать.
        if (newOpacity > oldOpacity) {
            removeBlockLightAt(x, y, z)
            removeSkyLightColumn(x, y, z)
        }

        // 3. Новый источник — запускаем волну.
        if (newEmission > 0) {
            val chunk = world.getChunkAtBlock(x, z)
            if (chunk != null) {
                chunk.setBlockLight(x and SECTION_MASK, y, z and SECTION_MASK, newEmission)
                blockQueue.push(x, y, z, newEmission)
            }
        }

        // 4. Блок стал прозрачнее — свет от соседей должен затечь внутрь.
        //    Ставим в очередь всех соседей: волна сама разберётся, кто ярче.
        if (newOpacity < oldOpacity) {
            reseedFromNeighbours(x, y, z)
            reseedSkyColumn(x, y, z)
        }

        propagateBlock()
        propagateSky()
    }

    /**
     * Гасит область, освещённую исчезнувшим источником.
     *
     * Первый проход волны обнуляет ячейки, чья яркость могла исходить
     * только от этого источника, и попутно собирает встреченные «чужие»
     * источники — те, что ярче ожидаемого. Второй проход (обычное
     * распространение) заливает опустевшую область светом от них.
     */
    private fun removeBlockLight(x: Int, y: Int, z: Int) {
        val chunk = world.getChunkAtBlock(x, z) ?: return
        val level = chunk.getBlockLight(x and SECTION_MASK, y, z and SECTION_MASK)
        if (level == 0) return

        chunk.setBlockLight(x and SECTION_MASK, y, z and SECTION_MASK, 0)
        removalQueue.clear()
        removalQueue.push(x, y, z, level)

        while (!removalQueue.isEmpty) {
            removalQueue.pop()
            val cx = removalQueue.x
            val cy = removalQueue.y
            val cz = removalQueue.z
            val cl = removalQueue.level

            for (face in BlockFace.VALUES) {
                val nx = cx + face.dx
                val ny = cy + face.dy
                val nz = cz + face.dz
                if (ny < 0 || ny > MAX_Y) continue

                val nChunk = world.getChunkAtBlock(nx, nz) ?: continue
                val nlx = nx and SECTION_MASK
                val nlz = nz and SECTION_MASK
                val neighbourLevel = nChunk.getBlockLight(nlx, ny, nlz)
                if (neighbourLevel == 0) continue

                if (neighbourLevel < cl) {
                    // Эта яркость происходила от удалённого источника — гасим.
                    nChunk.setBlockLight(nlx, ny, nlz, 0)
                    nChunk.markSectionDirty(ny shr SECTION_SHIFT)
                    removalQueue.push(nx, ny, nz, neighbourLevel)
                } else {
                    // Сосед ярче — значит, его питает другой источник.
                    // Он станет затравкой восстанавливающей волны.
                    blockQueue.push(nx, ny, nz, neighbourLevel)
                }
            }
        }
    }

    /** Обнуляет свет в одной клетке (блок стал непрозрачным). */
    private fun removeBlockLightAt(x: Int, y: Int, z: Int) {
        val chunk = world.getChunkAtBlock(x, z) ?: return
        if (chunk.getBlockLight(x and SECTION_MASK, y, z and SECTION_MASK) > 0) {
            removeBlockLight(x, y, z)
        }
    }

    /**
     * Гасит солнечный свет в колонке под поставленным блоком.
     *
     * Столб света вниз обрывается сразу до самого низа, поэтому обрабатывается
     * не одна клетка, а вся колонка до первого непрозрачного блока. Затем
     * границы затемнённой области ставятся в очередь, чтобы боковой свет
     * затёк в получившуюся тень и создал мягкий переход, а не резкий столб.
     */
    private fun removeSkyLightColumn(x: Int, y: Int, z: Int) {
        val chunk = world.getChunkAtBlock(x, z) ?: return
        val lx = x and SECTION_MASK
        val lz = z and SECTION_MASK

        var cy = y
        while (cy >= 0) {
            val id = chunk.getBlock(lx, cy, lz)
            if (Blocks.OPACITY[id] >= MAX_LIGHT && cy != y) break
            if (chunk.getSkyLight(lx, cy, lz) == 0) break
            chunk.setSkyLight(lx, cy, lz, 0)
            chunk.markSectionDirty(cy shr SECTION_SHIFT)
            // Соседи по горизонтали могут осветить эту клетку сбоку.
            for (face in BlockFace.HORIZONTAL) {
                val nx = x + face.dx
                val nz = z + face.dz
                val nChunk = world.getChunkAtBlock(nx, nz) ?: continue
                val level = nChunk.getSkyLight(nx and SECTION_MASK, cy, nz and SECTION_MASK)
                if (level > 1) skyQueue.push(nx, cy, nz, level)
            }
            cy--
        }
    }

    /** Ставит в очередь соседей клетки — используется, когда блок исчез. */
    private fun reseedFromNeighbours(x: Int, y: Int, z: Int) {
        for (face in BlockFace.VALUES) {
            val nx = x + face.dx
            val ny = y + face.dy
            val nz = z + face.dz
            if (ny < 0 || ny > MAX_Y) continue
            val chunk = world.getChunkAtBlock(nx, nz) ?: continue
            val level = chunk.getBlockLight(nx and SECTION_MASK, ny, nz and SECTION_MASK)
            if (level > 1) blockQueue.push(nx, ny, nz, level)
        }
    }

    /**
     * Восстанавливает солнечный свет после разрушения блока.
     * Если сверху открытое небо, столб заливается максимумом заново;
     * иначе свет затекает от соседей.
     */
    private fun reseedSkyColumn(x: Int, y: Int, z: Int) {
        val chunk = world.getChunkAtBlock(x, z) ?: return
        val lx = x and SECTION_MASK
        val lz = z and SECTION_MASK

        // Карта высот уже обновлена в Chunk.setBlock, поэтому достаточно
        // сравнить отметку с координатой.
        if (y >= chunk.getHeight(lx, lz)) {
            chunk.setSkyLight(lx, y, lz, MAX_LIGHT)
            skyQueue.push(x, y, z, MAX_LIGHT)
        }
        for (face in BlockFace.VALUES) {
            val nx = x + face.dx
            val ny = y + face.dy
            val nz = z + face.dz
            if (ny < 0 || ny > MAX_Y) continue
            val nChunk = world.getChunkAtBlock(nx, nz) ?: continue
            val level = nChunk.getSkyLight(nx and SECTION_MASK, ny, nz and SECTION_MASK)
            if (level > 1) skyQueue.push(nx, ny, nz, level)
        }
    }

    /**
     * Досвечивает границу между чанком и его уже загруженными соседями.
     *
     * Нужен потому, что чанки приходят асинхронно и по одному: когда чанк
     * освещался, соседа могло ещё не быть, и свет остановился на границе.
     * Метод ставит в очередь граничные воксели соседей, и волна затекает
     * внутрь. Без этого шва на стыке чанков видна вертикальная полоса тени.
     */
    fun relightBorders(chunk: Chunk) {
        val baseX = chunk.pos.originX
        val baseZ = chunk.pos.originZ

        for (face in BlockFace.HORIZONTAL) {
            val neighbour = world.getChunk(chunk.pos.x + face.dx, chunk.pos.z + face.dz) ?: continue

            for (i in 0 until 16) {
                // Координаты граничного столбца со стороны соседа.
                val nx: Int
                val nz: Int
                when (face) {
                    BlockFace.WEST -> { nx = baseX - 1; nz = baseZ + i }
                    BlockFace.EAST -> { nx = baseX + 16; nz = baseZ + i }
                    BlockFace.NORTH -> { nx = baseX + i; nz = baseZ - 1 }
                    else -> { nx = baseX + i; nz = baseZ + 16 }
                }
                val nlx = nx and SECTION_MASK
                val nlz = nz and SECTION_MASK

                for (y in 0..MAX_Y) {
                    val sky = neighbour.getSkyLight(nlx, y, nlz)
                    if (sky > 1) skyQueue.push(nx, y, nz, sky)
                    val block = neighbour.getBlockLight(nlx, y, nlz)
                    if (block > 1) blockQueue.push(nx, y, nz, block)
                }
            }
        }

        // И в обратную сторону: свет самого чанка должен уйти к соседям.
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                if (lx != 0 && lx != 15 && lz != 0 && lz != 15) continue
                for (y in 0..MAX_Y) {
                    val sky = chunk.getSkyLight(lx, y, lz)
                    if (sky > 1) skyQueue.push(baseX + lx, y, baseZ + lz, sky)
                    val block = chunk.getBlockLight(lx, y, lz)
                    if (block > 1) blockQueue.push(baseX + lx, y, baseZ + lz, block)
                }
            }
        }

        propagateSky()
        propagateBlock()
    }
}
