package com.voxelforge.core.gen

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.noise.XorShiftRandom
import com.voxelforge.core.world.Chunk
import com.voxelforge.core.world.ChunkState
import com.voxelforge.core.world.WorldConstants.BEDROCK_TOP
import com.voxelforge.core.world.WorldConstants.CHUNK_AREA
import com.voxelforge.core.world.WorldConstants.MAX_Y
import com.voxelforge.core.world.WorldConstants.SEA_LEVEL

/**
 * Рабочий буфер генерации, свой у каждого потока.
 *
 * Назначение: убрать аллокации из горячего пути. На один чанк нужно
 * 256 объектов [ColumnSample]; при генерации десятков чанков в секунду
 * это десятки тысяч короткоживущих объектов, то есть постоянные паузы
 * сборщика мусора ровно в тот момент, когда игрок летит над миром и кадры
 * особенно дороги.
 *
 * Буфер живёт в ThreadLocal и переиспользуется бесконечно, поэтому за всю
 * сессию на генерацию приходится ровно N_потоков × 256 объектов.
 */
private class GenerationScratch {
    val samples: Array<ColumnSample> = Array(CHUNK_AREA) { ColumnSample() }
}

/**
 * Процедурный генератор мира.
 *
 * Назначение: превратить пару координат чанка и сид мира в полностью
 * заполненный [Chunk]. Это единственный класс, знающий порядок этапов
 * генерации, и он же — точка, где сходятся [TerrainSampler] (форма),
 * [CaveCarver] (пустоты) и [ChunkDecorator] (объекты).
 *
 * **Порядок этапов не произволен**, каждый шаг опирается на результат
 * предыдущего:
 *
 *  1. Выборка колонок — высота, биом, климат.
 *  2. Заливка камнем до уровня рельефа плюс коренная порода снизу.
 *  3. Покров — биомные блоки поверхности и подпочвы.
 *  4. Прокладка пещер. Именно после покрова: пещера должна прорезать
 *     готовый рельеф вместе с дёрном, иначе входы окажутся «залатаны»
 *     травой, положенной сверху.
 *  5. Лава на дне глубоких полостей.
 *  6. Заливка водой всех впадин ниже уровня моря — так океаны, озёра
 *     и реки получаются одним механизмом, без отдельного кода для каждого.
 *  7. Декорации — деревья, руда, снег.
 *  8. Пересчёт карты высот для системы освещения.
 *
 * **Потокобезопасность.** Генератор не хранит изменяемого состояния:
 * все поля — неизменяемые источники шума, а рабочие буферы лежат
 * в ThreadLocal. Один экземпляр обслуживает весь пул воркеров.
 *
 * @param seed сид мира; полностью определяет результат генерации
 */
class WorldGenerator(@JvmField val seed: Long) {

    private val terrain = TerrainSampler(seed)
    private val caves = CaveCarver(seed)
    private val decorator = ChunkDecorator(seed)

    private val scratch = ThreadLocal.withInitial { GenerationScratch() }

    /**
     * Полностью генерирует чанк. По завершении чанк находится в состоянии
     * [ChunkState.TERRAIN] — рельеф готов, освещение ещё не рассчитано.
     *
     * Метод вызывается из потока-воркера на чанке, который **ещё не
     * опубликован** в карте мира, поэтому синхронизация не нужна.
     */
    fun generate(chunk: Chunk) {
        val samples = scratch.get().samples
        val originX = chunk.pos.originX
        val originZ = chunk.pos.originZ
        val rng = XorShiftRandom.forChunk(seed, chunk.pos.x, chunk.pos.z, TERRAIN_SALT)

        sampleColumns(originX, originZ, samples, chunk)
        fillBaseTerrain(chunk, samples, rng)
        applySurfaceLayers(chunk, samples)
        carveCaves(chunk, samples, originX, originZ)
        floodLava(chunk)
        floodWater(chunk, samples)

        decorator.decorate(chunk, samples)

        // Счётчики секций после массового заполнения и карта высот для
        // освещения — оба шага обязаны идти последними, когда блоки на местах.
        for (section in chunk.sections) section?.recountContents()
        chunk.recomputeHeightMap()

        chunk.state = ChunkState.TERRAIN
    }

    // ------------------------------------------------------------------
    // Этап 1. Выборка колонок
    // ------------------------------------------------------------------

    private fun sampleColumns(
        originX: Int,
        originZ: Int,
        samples: Array<ColumnSample>,
        chunk: Chunk
    ) {
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val s = samples[lz * 16 + lx]
                terrain.sample(originX + lx, originZ + lz, s)
                chunk.setBiome(lx, lz, s.biome)
            }
        }
    }

    // ------------------------------------------------------------------
    // Этап 2. Базовый рельеф
    // ------------------------------------------------------------------

    /**
     * Заливает колонки камнем до высоты рельефа.
     *
     * Нижние слои — коренная порода со «рваной» верхней границей: сплошная
     * плита на y=0..2 выглядит как техническая заглушка, а неровный слой
     * читается как естественное основание мира.
     */
    private fun fillBaseTerrain(chunk: Chunk, samples: Array<ColumnSample>, rng: XorShiftRandom) {
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val height = samples[lz * 16 + lx].height

                chunk.setBlockDuringGeneration(lx, 0, lz, Blocks.BEDROCK)
                for (y in 1..BEDROCK_TOP) {
                    // Чем выше, тем реже коренная порода.
                    if (rng.nextInt(BEDROCK_TOP + 2) >= y) {
                        chunk.setBlockDuringGeneration(lx, y, lz, Blocks.BEDROCK)
                    } else {
                        chunk.setBlockDuringGeneration(lx, y, lz, Blocks.STONE)
                    }
                }

                for (y in (BEDROCK_TOP + 1)..height) {
                    chunk.setBlockDuringGeneration(lx, y, lz, Blocks.STONE)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Этап 3. Биомный покров
    // ------------------------------------------------------------------

    /**
     * Кладёт поверхностный и подповерхностный слои.
     *
     * Толщина подпочвы зависит от крутизны: на пологом месте это 3–4 блока
     * земли, а на крутом склоне горы — ноль, и наружу выходит голый камень.
     * Без этого правила отвесные скалы оказались бы обмазаны землёй сверху
     * донизу, что мгновенно разрушает впечатление от гор.
     */
    private fun applySurfaceLayers(chunk: Chunk, samples: Array<ColumnSample>) {
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val s = samples[lz * 16 + lx]
                val biome = Biomes.get(s.biome)
                val height = s.height
                if (height < 1) continue

                val underwater = height < SEA_LEVEL

                // Крутизна оценивается по разнице высот с соседями внутри
                // чанка; на краю берём себя же — погрешность в один блок
                // на границе визуально незаметна.
                val steepness = localSteepness(samples, lx, lz)

                // На скалах покров исчезает: 6 блоков перепада — уже отвес.
                val soilDepth = when {
                    s.mountain > 0.55f && steepness > 3 -> 0
                    steepness > 5 -> 0
                    steepness > 3 -> 1
                    else -> 3
                }

                if (soilDepth == 0 && !underwater) continue

                val surfaceBlock = if (underwater) biome.underwaterSurface else biome.surfaceBlock
                chunk.setBlockDuringGeneration(lx, height, lz, surfaceBlock)

                for (d in 1..soilDepth) {
                    val y = height - d
                    if (y <= BEDROCK_TOP) break
                    chunk.setBlockDuringGeneration(lx, y, lz, biome.subSurfaceBlock)
                }
            }
        }
    }

    /** Максимальный перепад высоты с четырьмя соседними колонками. */
    private fun localSteepness(samples: Array<ColumnSample>, lx: Int, lz: Int): Int {
        val h = samples[lz * 16 + lx].height
        var maxDiff = 0
        if (lx > 0) maxDiff = maxOf(maxDiff, h - samples[lz * 16 + lx - 1].height)
        if (lx < 15) maxDiff = maxOf(maxDiff, h - samples[lz * 16 + lx + 1].height)
        if (lz > 0) maxDiff = maxOf(maxDiff, h - samples[(lz - 1) * 16 + lx].height)
        if (lz < 15) maxDiff = maxOf(maxDiff, h - samples[(lz + 1) * 16 + lx].height)
        return maxDiff
    }

    // ------------------------------------------------------------------
    // Этап 4. Пещеры
    // ------------------------------------------------------------------

    private fun carveCaves(
        chunk: Chunk,
        samples: Array<ColumnSample>,
        originX: Int,
        originZ: Int
    ) {
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val s = samples[lz * 16 + lx]
                val height = s.height
                val underwater = height < SEA_LEVEL
                val wx = originX + lx
                val wz = originZ + lz

                val top = if (underwater) {
                    kotlin.math.min(height - 6, SEA_LEVEL - 8)
                } else {
                    height - 2
                }

                var y = 1
                while (y <= top) {
                    val current = chunk.getBlock(lx, y, lz)
                    // Коренную породу пещеры не трогают: дно мира должно
                    // оставаться герметичным.
                    if (current != Blocks.AIR && current != Blocks.BEDROCK) {
                        if (caves.shouldCarve(wx, y, wz, height, underwater)) {
                            chunk.setBlockDuringGeneration(lx, y, lz, Blocks.AIR)
                        }
                    }
                    y++
                }

                // Входы — только на суше: дыра в дне озера его осушит.
                if (!underwater) {
                    var ey = height - 4
                    while (ey <= height) {
                        if (ey > 0 && chunk.getBlock(lx, ey, lz) != Blocks.BEDROCK &&
                            caves.shouldCarveEntrance(wx, ey, wz, height)
                        ) {
                            chunk.setBlockDuringGeneration(lx, ey, lz, Blocks.AIR)
                        }
                        ey++
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Этап 5. Лава
    // ------------------------------------------------------------------

    /**
     * Заливает лавой дно глубоких полостей.
     *
     * Единый уровень лавы на всей глубине — это и источник освещения
     * в пещерах, и внятный сигнал опасности: игрок понимает, что спустился
     * достаточно глубоко, по красному свечению из тоннеля.
     */
    private fun floodLava(chunk: Chunk) {
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                for (y in 1..LAVA_LEVEL) {
                    if (chunk.getBlock(lx, y, lz) == Blocks.AIR) {
                        chunk.setBlockDuringGeneration(lx, y, lz, Blocks.LAVA)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Этап 6. Вода
    // ------------------------------------------------------------------

    /**
     * Заливает водой всё, что ниже уровня моря и не занято блоком.
     *
     * Один и тот же проход порождает океаны, озёра в горных впадинах и русла
     * рек: разница между ними — только в форме рельефа, которую задал
     * [TerrainSampler]. Отдельного кода для озёр не требуется, и именно
     * поэтому вода везде согласована по уровню и никогда не «висит» стеной.
     */
    private fun floodWater(chunk: Chunk, samples: Array<ColumnSample>) {
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val height = samples[lz * 16 + lx].height
                if (height >= SEA_LEVEL) continue
                var y = height + 1
                while (y <= SEA_LEVEL && y <= MAX_Y) {
                    if (chunk.getBlock(lx, y, lz) == Blocks.AIR) {
                        chunk.setBlockDuringGeneration(lx, y, lz, Blocks.WATER)
                    }
                    y++
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Точка появления игрока
    // ------------------------------------------------------------------

    /**
     * Ищет пригодную точку появления: суша выше уровня моря, не в горах.
     *
     * Поиск идёт расширяющейся спиралью от начала координат. Ограничение
     * по числу проб не даёт зациклиться на «водном» сиде; если суша так и не
     * найдена, игрок появляется над уровнем моря и приземляется на воду —
     * плавание уже реализовано, поэтому такой исход играбелен, а не фатален.
     *
     * @return тройка (x, y, z) в мировых координатах
     */
    fun findSpawnPoint(): Triple<Int, Int, Int> {
        val probe = ColumnSample()
        var bestX = 0
        var bestZ = 0
        var bestHeight = -1

        var x = 0
        var z = 0
        var dx = 0
        var dz = -1
        var stepsTried = 0

        while (stepsTried < SPAWN_SEARCH_LIMIT) {
            terrain.sample(x * SPAWN_STEP, z * SPAWN_STEP, probe)
            val suitable = probe.height > SEA_LEVEL + 1 &&
                probe.height < SEA_LEVEL + 26 &&
                probe.biome != Biomes.OCEAN &&
                probe.biome != Biomes.RIVER
            if (suitable) {
                return Triple(x * SPAWN_STEP, probe.height + 1, z * SPAWN_STEP)
            }
            if (probe.height > bestHeight) {
                bestHeight = probe.height
                bestX = x * SPAWN_STEP
                bestZ = z * SPAWN_STEP
            }

            // Обход по спирали: смена направления на углах квадрата.
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                val t = dx
                dx = -dz
                dz = t
            }
            x += dx
            z += dz
            stepsTried++
        }

        return Triple(bestX, maxOf(bestHeight, SEA_LEVEL) + 1, bestZ)
    }

    /** Биом в произвольной точке — для интерфейса и погоды без загрузки чанка. */
    fun biomeAt(worldX: Int, worldZ: Int): Int {
        val s = ColumnSample()
        terrain.sample(worldX, worldZ, s)
        return s.biome
    }

    companion object {
        private const val TERRAIN_SALT = 0xB100DL

        /** Верхняя граница лавовых озёр. */
        private const val LAVA_LEVEL = 8

        /** Шаг спирали поиска спавна в блоках. */
        private const val SPAWN_STEP = 24

        private const val SPAWN_SEARCH_LIMIT = 2048
    }
}
