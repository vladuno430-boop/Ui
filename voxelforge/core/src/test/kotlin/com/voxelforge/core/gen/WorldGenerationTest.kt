package com.voxelforge.core.gen

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.world.Chunk
import com.voxelforge.core.world.ChunkPos
import com.voxelforge.core.world.ChunkState
import com.voxelforge.core.world.WorldConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки генератора мира.
 *
 * Смысл этих тестов не в «покрытии», а в защите двух свойств, нарушение
 * которых проявляется не сразу и отлаживается крайне тяжело:
 *
 *  1. **Детерминированность.** Мир обязан зависеть только от сида. Стоит
 *     где-то в генераторе появиться общему изменяемому состоянию — и мир
 *     начнёт незаметно отличаться при загрузке, а сохранённые постройки
 *     окажутся висящими в воздухе. Проверить это глазами невозможно.
 *
 *  2. **Независимость чанков от порядка генерации.** Многопоточный конвейер
 *     обрабатывает чанки в непредсказуемом порядке. Тест генерирует один
 *     и тот же чанк первым и последним и сравнивает результат побайтово.
 */
class WorldGenerationTest {

    private fun generateChunk(seed: Long, cx: Int, cz: Int): Chunk {
        val gen = WorldGenerator(seed)
        val chunk = Chunk(ChunkPos(cx, cz))
        gen.generate(chunk)
        return chunk
    }

    private fun snapshot(chunk: Chunk): ByteArray {
        val out = ByteArray(16 * 16 * WorldConstants.WORLD_HEIGHT)
        var i = 0
        for (y in 0 until WorldConstants.WORLD_HEIGHT) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    out[i++] = chunk.getBlock(x, y, z).toByte()
                }
            }
        }
        return out
    }

    @Test
    fun `один и тот же сид даёт побитово одинаковый чанк`() {
        val a = snapshot(generateChunk(12345L, 3, -7))
        val b = snapshot(generateChunk(12345L, 3, -7))
        assertTrue(a.contentEquals(b), "Генерация недетерминирована при одном сиде")
    }

    @Test
    fun `разные сиды дают разные миры`() {
        val a = snapshot(generateChunk(1L, 0, 0))
        val b = snapshot(generateChunk(2L, 0, 0))
        assertTrue(!a.contentEquals(b), "Разные сиды породили идентичные чанки")
    }

    @Test
    fun `порядок генерации не влияет на содержимое чанка`() {
        // Один генератор обрабатывает несколько чанков; целевой чанк
        // генерируется до и после других. Если бы в генераторе завелось
        // состояние, разделяемое между вызовами, результаты разошлись бы.
        val gen = WorldGenerator(777L)

        val first = Chunk(ChunkPos(5, 5))
        gen.generate(first)

        for (i in 0 until 6) {
            gen.generate(Chunk(ChunkPos(i * 3, i * 2 - 4)))
        }

        val last = Chunk(ChunkPos(5, 5))
        gen.generate(last)

        assertTrue(
            snapshot(first).contentEquals(snapshot(last)),
            "Содержимое чанка зависит от порядка генерации"
        )
    }

    @Test
    fun `генерация из нескольких потоков совпадает с однопоточной`() {
        val gen = WorldGenerator(4242L)
        val positions = (0 until 16).map { ChunkPos(it % 4, it / 4) }

        val sequential = positions.map { pos ->
            Chunk(pos).also { gen.generate(it) }
        }.map { snapshot(it) }

        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val parallel = try {
            positions.map { pos ->
                pool.submit<ByteArray> {
                    val c = Chunk(pos)
                    gen.generate(c)
                    snapshot(c)
                }
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }

        for (i in positions.indices) {
            assertTrue(
                sequential[i].contentEquals(parallel[i]),
                "Чанк ${positions[i]} отличается при параллельной генерации"
            )
        }
    }

    @Test
    fun `дно мира герметично`() {
        // Провал сквозь дно мира — фатальный дефект: игрок падает в пустоту
        // без возможности вернуться. Нижний слой обязан быть сплошным.
        val chunk = generateChunk(999L, -2, 11)
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                assertEquals(
                    Blocks.BEDROCK, chunk.getBlock(x, 0, z),
                    "Дыра в коренной породе на ($x, 0, $z)"
                )
            }
        }
    }

    @Test
    fun `вода не висит над уровнем моря`() {
        // Вода выше уровня моря означала бы ошибку в заливке впадин —
        // визуально это «водопад из ниоткуда» на склоне.
        for (seedTrial in 0 until 4) {
            val chunk = generateChunk(500L + seedTrial, seedTrial, seedTrial * 2)
            for (y in (WorldConstants.SEA_LEVEL + 1) until WorldConstants.WORLD_HEIGHT) {
                for (z in 0 until 16) {
                    for (x in 0 until 16) {
                        assertTrue(
                            chunk.getBlock(x, y, z) != Blocks.WATER,
                            "Вода на высоте $y выше уровня моря"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `карта высот согласована с содержимым`() {
        val chunk = generateChunk(31337L, 8, -3)
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val h = chunk.getHeight(x, z)
                // Над отметкой не должно быть блоков, гасящих свет.
                for (y in h until WorldConstants.WORLD_HEIGHT) {
                    val id = chunk.getBlock(x, y, z)
                    assertTrue(
                        Blocks.OPACITY[id] == 0,
                        "Блок $id на ($x,$y,$z) выше отметки карты высот $h"
                    )
                }
            }
        }
    }

    @Test
    fun `чанк переходит в состояние TERRAIN`() {
        val chunk = generateChunk(1L, 0, 0)
        assertEquals(ChunkState.TERRAIN, chunk.state)
    }

    @Test
    fun `точка появления находится на суше`() {
        for (seed in listOf(1L, 42L, 1000L, -55L)) {
            val gen = WorldGenerator(seed)
            val (sx, sy, sz) = gen.findSpawnPoint()
            assertTrue(
                sy > WorldConstants.SEA_LEVEL,
                "Спавн для сида $seed оказался под водой: ($sx, $sy, $sz)"
            )
        }
    }

    @Test
    fun `в мире встречаются все требуемые биомы`() {
        // Биом, который не появляется ни разу, — это мёртвый код в генераторе.
        // Проверяем достаточно широкую выборку, чтобы поймать такую ошибку.
        val gen = WorldGenerator(20240501L)
        val found = HashSet<Int>()
        var x = -6000
        while (x <= 6000) {
            var z = -6000
            while (z <= 6000) {
                found.add(gen.biomeAt(x, z))
                z += 150
            }
            x += 150
        }
        for (required in listOf(
            Biomes.PLAINS, Biomes.FOREST, Biomes.DESERT,
            Biomes.MOUNTAINS, Biomes.SNOW, Biomes.OCEAN, Biomes.RIVER, Biomes.BEACH
        )) {
            assertTrue(
                found.contains(required),
                "Биом ${Biomes.get(required).key} не встретился ни разу"
            )
        }
    }

    @Test
    fun `пещеры действительно прокладываются`() {
        // Отсутствие пустот под землёй означало бы, что порог прокладчика
        // выставлен так, что пещер нет вовсе.
        var airUnderground = 0
        for (cx in 0 until 4) {
            val chunk = generateChunk(88L, cx, 0)
            for (y in 10 until 45) {
                for (z in 0 until 16) {
                    for (x in 0 until 16) {
                        if (chunk.getBlock(x, y, z) == Blocks.AIR) airUnderground++
                    }
                }
            }
        }
        assertTrue(airUnderground > 500, "Подземных пустот почти нет: $airUnderground")
    }
}
