package com.voxelforge.core.world

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.gen.WorldGenerator
import com.voxelforge.core.mesh.SectionMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционные проверки конвейера «генерация → освещение → мешинг».
 *
 * Юнит-тесты подтверждают корректность каждого звена по отдельности,
 * но самые дорогие дефекты воксельного движка живут на стыках: чанк
 * опубликован раньше, чем освещён; меш построен раньше, чем пришёл сосед;
 * секция выгружена, пока её меш ещё строился. Такие ошибки проявляются
 * редко и невоспроизводимо, поэтому проверяются здесь, на живом конвейере
 * с настоящим пулом потоков.
 */
class ChunkPipelineTest {

    /** Накопитель мешей: заменяет рендер в тестовой среде. */
    private class MeshCollector : MeshConsumer {
        val quadsBySection = HashMap<Long, Int>()
        var totalQuads = 0
        var removals = 0

        override fun onMeshReady(mesh: SectionMesh) {
            val key = SectionPos.pack(mesh.chunkX, mesh.sectionY, mesh.chunkZ)
            quadsBySection[key] = mesh.totalQuads
            totalQuads += mesh.totalQuads
        }

        override fun onSectionRemoved(chunkX: Int, sectionY: Int, chunkZ: Int) {
            removals++
        }
    }

    /**
     * Прогоняет конвейер до стабилизации либо до истечения времени.
     * Возвращает число выполненных шагов.
     */
    private fun runPipeline(
        manager: ChunkManager,
        collector: MeshCollector,
        x: Double,
        z: Double,
        maxMillis: Long = 30_000
    ): Int {
        val deadline = System.currentTimeMillis() + maxMillis
        var steps = 0
        var idleStreak = 0
        while (System.currentTimeMillis() < deadline) {
            manager.update(x, z, budgetNanos = 20_000_000L)
            val before = collector.totalQuads
            manager.drainMeshes(collector, maxUploads = 64)
            steps++
            // Конвейер считается устоявшимся, когда несколько шагов подряд
            // не приносят ни новых мешей, ни новых чанков в очереди.
            idleStreak = if (collector.totalQuads == before && !manager.hasPendingWork()) {
                idleStreak + 1
            } else {
                0
            }
            if (idleStreak > 40) break
            Thread.sleep(1)
        }
        return steps
    }

    @Test
    fun `конвейер загружает мир и строит геометрию`() {
        val world = World(seed = 777L)
        val manager = ChunkManager(world, renderDistance = 5)
        val collector = MeshCollector()
        try {
            runPipeline(manager, collector, 0.0, 0.0)

            // Радиус 5 даёт квадрат 11×11 = 121 чанк.
            assertTrue(
                world.loadedChunkCount >= 100,
                "Загружено всего ${world.loadedChunkCount} чанков"
            )
            assertTrue(collector.totalQuads > 0, "Геометрия не построена вовсе")

            // Каждый загруженный чанк должен пройти освещение.
            val unlit = world.loadedChunks().count {
                it.state != ChunkState.LIT && it.state != ChunkState.READY
            }
            assertEquals(0, unlit, "Не освещено чанков: $unlit")
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `солнечный свет доходит до поверхности и гаснет под землёй`() {
        val world = World(seed = 4242L)
        val manager = ChunkManager(world, renderDistance = 3)
        val collector = MeshCollector()
        try {
            runPipeline(manager, collector, 0.0, 0.0)

            val chunk = world.getChunk(0, 0)!!
            var checkedSurface = 0
            var checkedDeep = 0

            for (lz in 0 until 16) {
                for (lx in 0 until 16) {
                    val height = chunk.getHeight(lx, lz)
                    if (height in 1..WorldConstants.MAX_Y) {
                        // Прямо над поверхностью небо открыто — свет полный.
                        assertEquals(
                            WorldConstants.MAX_LIGHT,
                            chunk.getSkyLight(lx, height, lz),
                            "Тень над поверхностью в ($lx, $height, $lz)"
                        )
                        checkedSurface++
                    }
                    // Непрозрачный блок не может нести солнечный свет:
                    // волна в него не заходит по определению. Проверка
                    // именно по непрозрачности, а не по глубине, — иначе
                    // тест ложно срабатывал бы на лаве и воде, которые
                    // свет пропускают и вполне могут быть освещены
                    // через пещерный колодец.
                    for (y in 4..30) {
                        val id = chunk.getBlock(lx, y, lz)
                        if (Blocks.OPACITY[id] >= WorldConstants.MAX_LIGHT) {
                            assertEquals(
                                0, chunk.getSkyLight(lx, y, lz),
                                "Солнечный свет внутри непрозрачного блока ($lx, $y, $lz)"
                            )
                            checkedDeep++
                        }
                    }
                }
            }
            assertTrue(checkedSurface > 200 && checkedDeep > 200, "Проверено слишком мало колонок")
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `факел освещает окружающие блоки`() {
        val world = World(seed = 1L)
        val manager = ChunkManager(world, renderDistance = 3)
        val collector = MeshCollector()
        try {
            runPipeline(manager, collector, 0.0, 0.0)

            // Выкапываем нишу глубоко под землёй и ставим факел.
            val y = 20
            for (dx in -2..2) {
                for (dz in -2..2) {
                    for (dy in 0..2) {
                        world.setBlock(dx, y + dy, dz, Blocks.AIR)
                    }
                }
            }
            assertEquals(0, world.getBlockLight(0, y, 0), "В нише изначально должно быть темно")

            world.setBlock(0, y, 0, Blocks.TORCH)
            manager.onBlockChanged(0, y, 0, Blocks.AIR, Blocks.TORCH)

            assertEquals(14, world.getBlockLight(0, y, 0), "Факел не светит")
            assertEquals(13, world.getBlockLight(1, y, 0), "Свет не распространился на соседа")
            assertEquals(12, world.getBlockLight(2, y, 0), "Свет затухает неверно")

            // Убираем факел — свет обязан погаснуть полностью.
            world.setBlock(0, y, 0, Blocks.AIR)
            manager.onBlockChanged(0, y, 0, Blocks.TORCH, Blocks.AIR)

            assertEquals(0, world.getBlockLight(0, y, 0), "Свет остался после удаления факела")
            assertEquals(0, world.getBlockLight(2, y, 0), "Остаточное свечение у соседей")
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `разрушение блока перестраивает соседнюю секцию`() {
        val world = World(seed = 55L)
        val manager = ChunkManager(world, renderDistance = 3)
        val collector = MeshCollector()
        try {
            runPipeline(manager, collector, 0.0, 0.0)

            // Блок на границе чанков 0 и -1 по оси X.
            val x = 0
            val z = 8
            val y = world.getHeight(x, z) - 1
            world.setBlock(x, y, z, Blocks.AIR)

            val neighbour = world.getChunk(-1, 0)!!
            assertTrue(
                neighbour.hasDirtySections,
                "Соседний чанк не помечен на перестройку — на стыке останется дыра"
            )
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `выгрузка дальних чанков освобождает память`() {
        val world = World(seed = 9L)
        val manager = ChunkManager(world, renderDistance = 3)
        val collector = MeshCollector()
        try {
            runPipeline(manager, collector, 0.0, 0.0)
            val loadedNear = world.loadedChunkCount
            assertTrue(loadedNear > 30, "Мир не загрузился: $loadedNear")

            // Перемещаем игрока далеко: прежние чанки обязаны выгрузиться.
            runPipeline(manager, collector, 4000.0, 4000.0)

            assertTrue(
                world.getChunk(0, 0) == null,
                "Чанк начала координат не выгружен после ухода игрока"
            )
            assertTrue(
                world.loadedChunkCount < loadedNear * 2,
                "Число чанков растёт неограниченно: ${world.loadedChunkCount}"
            )
        } finally {
            manager.shutdown()
        }
    }

    /**
     * Замер эффективности объединения граней и производительности конвейера.
     *
     * Тест не проверяет абсолютных чисел (они зависят от машины), но требует
     * минимального коэффициента сокращения геометрии. Если однажды кто-то
     * сломает объединение, тест поймает это сразу: без него коэффициент
     * упадёт с нескольких единиц до единицы.
     */
    @Test
    fun `объединение граней сокращает геометрию в разы`() {
        val world = World(seed = 20240501L, generator = WorldGenerator(20240501L))
        val manager = ChunkManager(world, renderDistance = 6)
        val collector = MeshCollector()
        try {
            val start = System.nanoTime()
            runPipeline(manager, collector, 0.0, 0.0)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

            // Считаем, сколько граней дал бы наивный мешер без объединения.
            var naiveQuads = 0L
            for (chunk in world.loadedChunks()) {
                for (sectionIndex in chunk.sections.indices) {
                    val section = chunk.sections[sectionIndex] ?: continue
                    if (section.isEmpty) continue
                    val baseY = sectionIndex shl WorldConstants.SECTION_SHIFT
                    for (ly in 0 until 16) {
                        for (lz in 0 until 16) {
                            for (lx in 0 until 16) {
                                val id = section.getBlock(lx, ly, lz)
                                if (id == Blocks.AIR) continue
                                for (face in com.voxelforge.core.block.BlockFace.VALUES) {
                                    val nx = chunk.pos.originX + lx + face.dx
                                    val ny = baseY + ly + face.dy
                                    val nz = chunk.pos.originZ + lz + face.dz
                                    val neighbour = world.getBlock(nx, ny, nz)
                                    if (!Blocks.hidesFace(id, neighbour)) naiveQuads++
                                }
                            }
                        }
                    }
                }
            }

            val ratio = naiveQuads.toDouble() / collector.totalQuads.coerceAtLeast(1)
            println(
                "Мешинг: ${collector.totalQuads} граней против $naiveQuads без объединения " +
                    "(в %.2f раза меньше), загрузка ${world.loadedChunkCount} чанков за %.0f мс"
                        .format(ratio, elapsedMs)
            )

            assertTrue(
                ratio > 2.5,
                "Объединение граней малоэффективно: сокращение всего в %.2f раза".format(ratio)
            )
        } finally {
            manager.shutdown()
        }
    }
}
