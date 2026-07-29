package com.voxelforge.core.debug

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.gen.Biomes
import com.voxelforge.core.gen.ColumnSample
import com.voxelforge.core.gen.TerrainSampler
import com.voxelforge.core.gen.WorldGenerator
import com.voxelforge.core.world.Chunk
import com.voxelforge.core.world.ChunkPos
import kotlin.test.Test

/**
 * Отладочный визуализатор генерации в ASCII.
 *
 * Назначение: дать возможность оценить качество мира глазами, не собирая
 * и не запуская APK. Автоматические тесты ловят логические ошибки
 * (недетерминированность, дыры в дне), но не отвечают на вопросы вида
 * «не слишком ли часто встречаются горы» и «не выглядят ли пещеры как
 * прямые трубы» — а именно эти вопросы решают, играбелен мир или нет.
 *
 * Инструмент оформлен тестом сознательно: так он всегда компилируется
 * вместе с проектом и не может незаметно устареть, в отличие от
 * отдельного скрипта. Результат пишется в файл, путь задаётся системным
 * свойством `voxelforge.visual.out`; без него вывод идёт в stdout.
 *
 * Запуск:
 * `./gradlew :core:test --tests "*TerrainVisualizer*" -Dvoxelforge.visual.out=/tmp/map.txt`
 */
class TerrainVisualizer {

    private val out = StringBuilder()

    private fun line(text: String = "") {
        out.append(text).append('\n')
    }

    @Test
    fun renderWorldPreview() {
        val seed = System.getProperty("voxelforge.visual.seed")?.toLongOrNull() ?: 20240501L
        renderBiomeMap(seed)
        line()
        renderCrossSection(seed)
        line()
        renderStatistics(seed)

        val path = System.getProperty("voxelforge.visual.out")
        if (path != null) {
            java.io.File(path).writeText(out.toString())
        } else {
            print(out)
        }
    }

    /** Карта биомов с высоты птичьего полёта. */
    private fun renderBiomeMap(seed: Long) {
        val sampler = TerrainSampler(seed)
        val sample = ColumnSample()
        val glyphs = mapOf(
            Biomes.OCEAN to '~', Biomes.RIVER to '=', Biomes.BEACH to '.',
            Biomes.PLAINS to ',', Biomes.FOREST to 'T', Biomes.DESERT to ':',
            Biomes.MOUNTAINS to '^', Biomes.SNOW to '*'
        )

        line("=== КАРТА БИОМОВ (сид $seed, шаг $MAP_STEP блоков) ===")
        line("~ океан  = река  . пляж  , равнина  T лес  : пустыня  ^ горы  * снег")
        for (row in 0 until MAP_ROWS) {
            val sb = StringBuilder()
            for (col in 0 until MAP_COLS) {
                val wx = (col - MAP_COLS / 2) * MAP_STEP
                val wz = (row - MAP_ROWS / 2) * MAP_STEP
                sampler.sample(wx, wz, sample)
                sb.append(glyphs[sample.biome] ?: '?')
            }
            line(sb.toString())
        }
    }

    /** Вертикальный разрез мира: видно рельеф, пещеры, воду и руду. */
    private fun renderCrossSection(seed: Long) {
        val generator = WorldGenerator(seed)
        val chunkCount = SECTION_WIDTH / 16
        val chunks = Array(chunkCount) { cx ->
            Chunk(ChunkPos(cx, 0)).also { generator.generate(it) }
        }

        line("=== ВЕРТИКАЛЬНЫЙ РАЗРЕЗ ($SECTION_WIDTH блоков в ширину) ===")
        for (y in SECTION_TOP downTo 0) {
            val sb = StringBuilder()
            sb.append(String.format("%3d|", y))
            for (wx in 0 until SECTION_WIDTH) {
                sb.append(glyphFor(chunks[wx shr 4].getBlock(wx and 15, y, 8)))
            }
            line(sb.toString().trimEnd())
        }
    }

    private fun glyphFor(blockId: Int): Char = when (blockId) {
        Blocks.AIR -> ' '
        Blocks.STONE -> '#'
        Blocks.DIRT -> 'd'
        Blocks.GRASS_BLOCK -> 'G'
        Blocks.SNOWY_GRASS -> 'S'
        Blocks.SAND -> ':'
        Blocks.SANDSTONE -> 's'
        Blocks.WATER -> '~'
        Blocks.LAVA -> 'L'
        Blocks.LOG -> '|'
        Blocks.LEAVES -> '%'
        Blocks.BEDROCK -> 'X'
        Blocks.GRAVEL -> 'o'
        Blocks.COAL_ORE -> 'c'
        Blocks.IRON_ORE -> 'i'
        Blocks.CLAY -> 'y'
        Blocks.ICE -> 'I'
        Blocks.TALL_GRASS -> '"'
        Blocks.CACTUS -> '!'
        Blocks.SNOW_BLOCK -> '*'
        else -> '?'
    }

    /**
     * Числовая сводка: доли биомов и время генерации.
     * Позволяет заметить перекос — например, что пустыня заняла треть суши.
     */
    private fun renderStatistics(seed: Long) {
        val sampler = TerrainSampler(seed)
        val sample = ColumnSample()
        val counts = IntArray(Biomes.COUNT)
        var total = 0
        var x = -8000
        while (x <= 8000) {
            var z = -8000
            while (z <= 8000) {
                sampler.sample(x, z, sample)
                counts[sample.biome]++
                total++
                z += 120
            }
            x += 120
        }

        line("=== РАСПРЕДЕЛЕНИЕ БИОМОВ ($total проб) ===")
        for (b in Biomes.ALL) {
            val pct = counts[b.id] * 100f / total
            line(String.format("%-16s %5.1f%% %s", b.displayName, pct, "#".repeat((pct / 2).toInt())))
        }

        val generator = WorldGenerator(seed)
        // Прогрев JIT: без него первые чанки измеряют компиляцию, а не генерацию.
        repeat(20) { generator.generate(Chunk(ChunkPos(it, 900))) }
        val start = System.nanoTime()
        val measured = 60
        repeat(measured) { generator.generate(Chunk(ChunkPos(it, 500))) }
        val msPerChunk = (System.nanoTime() - start) / 1_000_000.0 / measured
        line()
        line(String.format("Генерация чанка: %.2f мс (одно ядро JVM)", msPerChunk))
    }

    companion object {
        private const val MAP_COLS = 110
        private const val MAP_ROWS = 44
        private const val MAP_STEP = 44

        private const val SECTION_WIDTH = 208
        private const val SECTION_TOP = 100
    }
}
