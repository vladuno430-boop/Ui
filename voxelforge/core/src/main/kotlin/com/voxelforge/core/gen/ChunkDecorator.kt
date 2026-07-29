package com.voxelforge.core.gen

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.noise.XorShiftRandom
import com.voxelforge.core.world.Chunk
import com.voxelforge.core.world.WorldConstants.MAX_Y
import com.voxelforge.core.world.WorldConstants.SEA_LEVEL
import kotlin.math.abs

/**
 * Декоратор чанка: размещает всё, что стоит «поверх» рельефа, —
 * деревья, кактусы, траву, снежный покров и рудные жилы.
 *
 * **Ключевое проектное ограничение: декоратор пишет только внутрь своего чанка.**
 *
 * Классический подход в воксельных играх — разрешить структурам пересекать
 * границу и решать проблему стадией «популяции», которая запускается лишь
 * когда все четыре соседа сгенерированы. Эта схема тянет за собой очередь
 * ожидания, риск взаимной блокировки чанков и, главное, недетерминированность
 * при многопоточной генерации: результат начинает зависеть от того, какой
 * воркер успел первым.
 *
 * Здесь выбрано иное: ствол дерева ставится не ближе [TREE_MARGIN] блоков
 * к краю, поэтому крона гарантированно умещается в чанк. Плата — деревья
 * никогда не стоят вплотную к границе чанка; при кроне радиусом 2 и чанке
 * 16×16 это исключает около 20 % позиций, что на глаз незаметно, потому что
 * сами позиции случайны. Взамен генерация становится полностью независимой
 * по чанкам: её можно вести в любом числе потоков в любом порядке, и мир
 * от этого не изменится ни на блок.
 *
 * Генератор случайных чисел засеивается координатами чанка, поэтому один
 * и тот же чанк всегда декорируется одинаково — это обязательное условие
 * того, чтобы несохранённые чанки корректно восстанавливались из сида.
 */
class ChunkDecorator(private val worldSeed: Long) {

    /**
     * Расставляет объекты в уже сформированном рельефе чанка.
     *
     * @param chunk    чанк с готовым рельефом (стадия TERRAIN)
     * @param samples  выборки колонок, вычисленные генератором рельефа;
     *                 переиспользуются, чтобы не считать шум повторно
     */
    fun decorate(chunk: Chunk, samples: Array<ColumnSample>) {
        val rng = XorShiftRandom.forChunk(worldSeed, chunk.pos.x, chunk.pos.z, DECORATION_SALT)

        generateOreVeins(chunk, rng)
        generateVegetation(chunk, samples, rng)
        generateSnowCover(chunk, samples)
    }

    // ------------------------------------------------------------------
    // Руда
    // ------------------------------------------------------------------

    /**
     * Рудные жилы. Жила выращивается случайным блужданием из стартовой точки:
     * так она получается неправильной формы и вытянутой, а не шарообразной.
     * Шар из руды сразу выдаёт процедурную природу, а извилистая жила выглядит
     * как настоящее рудное тело.
     */
    private fun generateOreVeins(chunk: Chunk, rng: XorShiftRandom) {
        // Уголь: обычен, встречается на всех глубинах.
        repeat(COAL_VEINS_PER_CHUNK) {
            growVein(
                chunk, rng, Blocks.COAL_ORE,
                minY = 6, maxY = 92,
                size = rng.nextIntRange(6, 14)
            )
        }
        // Железо: реже и глубже.
        repeat(IRON_VEINS_PER_CHUNK) {
            growVein(
                chunk, rng, Blocks.IRON_ORE,
                minY = 5, maxY = 56,
                size = rng.nextIntRange(4, 9)
            )
        }
        // Гравийные линзы в камне — разбавляют однородные каменные массивы.
        repeat(GRAVEL_POCKETS_PER_CHUNK) {
            growVein(
                chunk, rng, Blocks.GRAVEL,
                minY = 10, maxY = 70,
                size = rng.nextIntRange(10, 22)
            )
        }
    }

    /**
     * Случайное блуждание с заменой камня. Заменяется **только** камень:
     * жила, прорастающая сквозь воздух пещеры или сквозь землю на поверхности,
     * выглядела бы как парящие кубы руды.
     */
    private fun growVein(
        chunk: Chunk,
        rng: XorShiftRandom,
        oreId: Int,
        minY: Int,
        maxY: Int,
        size: Int
    ) {
        if (maxY <= minY) return
        var x = rng.nextInt(16)
        var y = rng.nextIntRange(minY, maxY)
        var z = rng.nextInt(16)

        repeat(size) {
            if (chunk.getBlock(x, y, z) == Blocks.STONE) {
                chunk.setBlockDuringGeneration(x, y, z, oreId)
            }
            // Шаг блуждания: одна из шести граней. Смещения по осям
            // независимы, поэтому жила может и утолщаться, и вытягиваться.
            when (rng.nextInt(6)) {
                0 -> x++
                1 -> x--
                2 -> y++
                3 -> y--
                4 -> z++
                else -> z--
            }
            // Выход за пределы чанка обрывает жилу: это прямое следствие
            // правила «пишем только в свой чанк».
            if (x !in 0..15 || z !in 0..15 || y < minY || y > maxY) return
        }
    }

    // ------------------------------------------------------------------
    // Растительность
    // ------------------------------------------------------------------

    private fun generateVegetation(chunk: Chunk, samples: Array<ColumnSample>, rng: XorShiftRandom) {
        // Биом чанка берём по центральной колонке: плотность флоры — величина
        // «на чанк», и считать её для каждой колонки отдельно бессмысленно.
        val centre = samples[centreIndex()]
        val biome = Biomes.get(centre.biome)

        // Деревья. Плотность дробная (например, 0.6 дерева на чанк равнины):
        // целую часть сажаем всегда, дробную — с соответствующей вероятностью.
        val treeCount = poissonCount(biome.treeDensity, rng)
        repeat(treeCount) {
            val lx = TREE_MARGIN + rng.nextInt(16 - TREE_MARGIN * 2)
            val lz = TREE_MARGIN + rng.nextInt(16 - TREE_MARGIN * 2)
            val sample = samples[lz * 16 + lx]
            val soil = chunk.getBlock(lx, sample.height, lz)
            // Дерево растёт только на почве и только выше воды.
            if (sample.height > SEA_LEVEL &&
                (soil == Blocks.GRASS_BLOCK || soil == Blocks.SNOWY_GRASS || soil == Blocks.DIRT)
            ) {
                if (biome.snowy) {
                    placeConiferTree(chunk, lx, sample.height + 1, lz, rng)
                } else {
                    placeBroadleafTree(chunk, lx, sample.height + 1, lz, rng)
                }
            }
        }

        // Кактусы: столбики 1–3 блока, только на песке.
        val cactusCount = poissonCount(biome.cactusDensity, rng)
        repeat(cactusCount) {
            val lx = 1 + rng.nextInt(14)
            val lz = 1 + rng.nextInt(14)
            val sample = samples[lz * 16 + lx]
            if (sample.height > SEA_LEVEL && chunk.getBlock(lx, sample.height, lz) == Blocks.SAND) {
                val h = rng.nextIntRange(1, 3)
                for (i in 0 until h) {
                    chunk.setBlockDuringGeneration(lx, sample.height + 1 + i, lz, Blocks.CACTUS)
                }
            }
        }

        // Трава — самый дешёвый способ убрать «пластиковую» гладкость газона.
        val grassCount = poissonCount(biome.grassDensity, rng)
        repeat(grassCount) {
            val lx = rng.nextInt(16)
            val lz = rng.nextInt(16)
            val sample = samples[lz * 16 + lx]
            val y = sample.height + 1
            if (y <= MAX_Y &&
                sample.height >= SEA_LEVEL &&
                chunk.getBlock(lx, sample.height, lz) == Blocks.GRASS_BLOCK &&
                chunk.getBlock(lx, y, lz) == Blocks.AIR
            ) {
                chunk.setBlockDuringGeneration(lx, y, lz, Blocks.TALL_GRASS)
            }
        }
    }

    /**
     * Лиственное дерево: прямой ствол и крона из четырёх слоёв.
     *
     * Форма кроны задаётся не сферой, а послойными радиусами: у настоящего
     * дерева нижние слои шире верхних, и верхушка сходится в точку. Углы
     * широких слоёв прореживаются случайно — сплошной куб листвы выглядит
     * как коробка.
     */
    private fun placeBroadleafTree(chunk: Chunk, x: Int, baseY: Int, z: Int, rng: XorShiftRandom) {
        val trunkHeight = rng.nextIntRange(4, 6)
        if (baseY + trunkHeight + 2 > MAX_Y) return

        // Крона строится до ствола: так ствол перезапишет листву в своей
        // колонке и внутри дерева не окажется листьев.
        val crownBase = baseY + trunkHeight - 2
        for (layer in 0 until 4) {
            val y = crownBase + layer
            if (y > MAX_Y) break
            // Радиусы 2,2,1,1 — широкое основание кроны, сходящаяся верхушка.
            val radius = if (layer < 2) 2 else 1
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    // Углы самого широкого слоя срезаем, иначе крона — куб.
                    if (radius == 2 && abs(dx) == 2 && abs(dz) == 2) {
                        if (rng.chance(0.75f)) continue
                    }
                    val lx = x + dx
                    val lz = z + dz
                    if (lx !in 0..15 || lz !in 0..15) continue
                    if (chunk.getBlock(lx, y, lz) == Blocks.AIR) {
                        chunk.setBlockDuringGeneration(lx, y, lz, Blocks.LEAVES)
                    }
                }
            }
        }
        // Шапка на макушке — убирает плоский срез сверху.
        val topY = crownBase + 4
        if (topY <= MAX_Y) {
            chunk.setBlockDuringGeneration(x, topY, z, Blocks.LEAVES)
            if (x > 0) chunk.setBlockDuringGeneration(x - 1, topY, z, Blocks.LEAVES)
            if (x < 15) chunk.setBlockDuringGeneration(x + 1, topY, z, Blocks.LEAVES)
            if (z > 0) chunk.setBlockDuringGeneration(x, topY, z - 1, Blocks.LEAVES)
            if (z < 15) chunk.setBlockDuringGeneration(x, topY, z + 1, Blocks.LEAVES)
        }

        for (i in 0 until trunkHeight) {
            chunk.setBlockDuringGeneration(x, baseY + i, z, Blocks.LOG)
        }
    }

    /**
     * Хвойное дерево для снежного биома: конус с убывающим кверху радиусом
     * и открытым нижним стволом. Силуэт ели узнаётся именно по конусу,
     * поэтому радиус здесь зависит от высоты слоя линейно.
     */
    private fun placeConiferTree(chunk: Chunk, x: Int, baseY: Int, z: Int, rng: XorShiftRandom) {
        val trunkHeight = rng.nextIntRange(6, 9)
        if (baseY + trunkHeight + 1 > MAX_Y) return

        val crownStart = baseY + 2
        val crownHeight = trunkHeight - 1
        for (layer in 0 until crownHeight) {
            val y = crownStart + layer
            if (y > MAX_Y) break
            // Радиус убывает ступенями, с чередованием — так конус получается
            // «ершистым», а не гладким.
            val t = layer.toFloat() / crownHeight
            val radius = when {
                t < 0.30f -> 2
                t < 0.72f -> if (layer % 2 == 0) 2 else 1
                t < 0.92f -> 1
                else -> 0
            }
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (abs(dx) == 2 && abs(dz) == 2) continue
                    val lx = x + dx
                    val lz = z + dz
                    if (lx !in 0..15 || lz !in 0..15) continue
                    if (chunk.getBlock(lx, y, lz) == Blocks.AIR) {
                        chunk.setBlockDuringGeneration(lx, y, lz, Blocks.LEAVES)
                    }
                }
            }
        }
        val tip = crownStart + crownHeight
        if (tip <= MAX_Y) chunk.setBlockDuringGeneration(x, tip, z, Blocks.LEAVES)

        for (i in 0 until trunkHeight) {
            chunk.setBlockDuringGeneration(x, baseY + i, z, Blocks.LOG)
        }
    }

    // ------------------------------------------------------------------
    // Снег и лёд
    // ------------------------------------------------------------------

    /**
     * Снежный покров и замерзание воды.
     *
     * Условие — не биом, а температура колонки: так снег ложится и на вершины
     * гор в умеренном поясе, где это физически оправдано высотой. Порог
     * снижается с высотой, что даёт естественную снеговую линию на склонах.
     */
    private fun generateSnowCover(chunk: Chunk, samples: Array<ColumnSample>) {
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val s = samples[lz * 16 + lx]
                // Каждые 30 блоков подъёма прибавляют к «холоду» 0.1.
                val altitudeChill = ((s.height - SEA_LEVEL).coerceAtLeast(0)) / 300f
                val effectiveTemp = s.temperature - altitudeChill

                if (effectiveTemp >= TerrainSampler.FREEZE_TEMPERATURE) continue

                val surfaceY = s.height
                when (chunk.getBlock(lx, surfaceY, lz)) {
                    Blocks.WATER -> {
                        // Мёрзнет только верхний слой: подо льдом остаётся вода,
                        // иначе водоём превратился бы в монолит.
                        chunk.setBlockDuringGeneration(lx, surfaceY, lz, Blocks.ICE)
                    }
                    Blocks.GRASS_BLOCK -> {
                        chunk.setBlockDuringGeneration(lx, surfaceY, lz, Blocks.SNOWY_GRASS)
                    }
                    Blocks.STONE, Blocks.GRAVEL -> {
                        val above = surfaceY + 1
                        if (above <= MAX_Y && chunk.getBlock(lx, above, lz) == Blocks.AIR) {
                            chunk.setBlockDuringGeneration(lx, above, lz, Blocks.SNOW_BLOCK)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    /**
     * Целое число объектов по дробной плотности.
     * Целая часть — гарантированные экземпляры, дробная — вероятность ещё одного.
     * Благодаря этому плотность 0.6 действительно даёт в среднем 0.6 дерева
     * на чанк, а не ноль от округления вниз.
     */
    private fun poissonCount(density: Float, rng: XorShiftRandom): Int {
        if (density <= 0f) return 0
        val whole = density.toInt()
        val frac = density - whole
        return whole + if (rng.chance(frac)) 1 else 0
    }

    private fun centreIndex(): Int = 8 * 16 + 8

    companion object {
        /**
         * Отступ ствола от края чанка. Равен радиусу кроны, поэтому
         * листва физически не может выйти за границу.
         */
        private const val TREE_MARGIN = 2

        private const val DECORATION_SALT = 0x5EEDL

        private const val COAL_VEINS_PER_CHUNK = 14
        private const val IRON_VEINS_PER_CHUNK = 7
        private const val GRAVEL_POCKETS_PER_CHUNK = 2
    }
}
