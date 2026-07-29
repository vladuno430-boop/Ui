package com.voxelforge.core.mesh

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.gen.Biomes
import com.voxelforge.core.world.Chunk
import com.voxelforge.core.world.World
import com.voxelforge.core.world.WorldConstants.MAX_LIGHT
import com.voxelforge.core.world.WorldConstants.MAX_Y
import com.voxelforge.core.world.WorldConstants.SECTION_MASK
import com.voxelforge.core.world.WorldConstants.SECTION_SHIFT

/**
 * Неизменяемый снимок секции вместе с однослойной каймой соседей.
 *
 * Назначение: дать мешеру данные, которые он может читать в фоновом потоке
 * без единой блокировки и без обращения к карте чанков.
 *
 * **Зачем копировать, а не читать мир напрямую.** Причин две, и обе весомые.
 *
 *  1. *Корректность.* Мешинг идёт в пуле потоков, а игрок в это время ломает
 *     блоки в игровом потоке. Читая мир напрямую, мешер увидел бы половину
 *     секции до изменения, половину после — и построил бы геометрию,
 *     не соответствующую ни одному состоянию мира. Снимок снимается за один
 *     проход, поэтому такой рассинхронизации не возникает: в худшем случае
 *     он окажется на кадр устаревшим, а секция уже помечена на перестройку.
 *
 *  2. *Скорость.* Мешер обращается к вокселям около 40 000 раз на секцию,
 *     и каждое обращение к миру — это хеш-поиск чанка, разыменование секции
 *     и проверки границ. Снимок сводит всё это к одному чтению из плоского
 *     массива, который целиком помещается в кэш второго уровня.
 *
 * Кайма толщиной в один блок обязательна: чтобы решить, рисовать ли грань
 * на краю секции, нужно знать соседний воксель по ту сторону границы.
 *
 * Объекты снимка берутся из пула и переиспользуются — на каждую перестройку
 * иначе выделялось бы по 12 КБ.
 */
class SectionSnapshot {

    /** Идентификаторы блоков в области 18×18×18. */
    @JvmField
    val blocks = ByteArray(VOLUME)

    /** Упакованный свет: старший полубайт — небо, младший — источники. */
    @JvmField
    val light = ByteArray(VOLUME)

    /** Биом каждой колонки секции 16×16 — для тонирования травы и листвы. */
    @JvmField
    val biomes = ByteArray(256)

    /** Мировые координаты угла секции. */
    @JvmField var originX = 0
    @JvmField var originY = 0
    @JvmField var originZ = 0

    /** Есть ли в секции хоть что-то, кроме воздуха. */
    @JvmField var hasContent = false

    /**
     * Заполняет снимок из мира.
     *
     * @return false, если секция пуста и строить нечего — вызывающий код
     *         сразу освободит буферы, не запуская мешер
     */
    fun capture(world: World, chunk: Chunk, sectionY: Int): Boolean {
        originX = chunk.pos.originX
        originY = sectionY shl SECTION_SHIFT
        originZ = chunk.pos.originZ

        val section = chunk.sections[sectionY]
        if (section == null || section.isEmpty) {
            hasContent = false
            return false
        }
        hasContent = true

        // Биомы берутся из своего чанка: снимок покрывает ровно одну колонку
        // чанка по горизонтали, поэтому кайма для биомов не нужна.
        System.arraycopy(chunk.biomes, 0, biomes, 0, 256)

        // Внутренность секции копируется напрямую из её массивов — это
        // последовательное чтение без единой проверки границ.
        val src = section.blocks
        val srcLight = section.light
        for (y in 0 until 16) {
            for (z in 0 until 16) {
                val srcRow = (y shl 8) or (z shl 4)
                val dstRow = index(0, y, z)
                if (src != null) {
                    System.arraycopy(src, srcRow, blocks, dstRow, 16)
                }
                if (srcLight != null) {
                    System.arraycopy(srcLight, srcRow, light, dstRow, 16)
                } else {
                    // Свет ещё не рассчитан — считаем открытым небом, иначе
                    // только что сгенерированный чанк мигнёт чёрным.
                    java.util.Arrays.fill(light, dstRow, dstRow + 16, (MAX_LIGHT shl 4).toByte())
                }
            }
        }

        captureBorders(world)
        return true
    }

    /**
     * Копирует кайму — шесть граничных плоскостей вокруг секции.
     *
     * Рёбра и углы каймы намеренно не заполняются: мешеру они не нужны.
     * Расчёт Ambient Occlusion смотрит на диагональных соседей только
     * **внутри** плоскости грани, а такие соседи всегда попадают в уже
     * скопированные плоскости. Пропуск восьми угловых вокселей и двенадцати
     * рёбер экономит около трети обращений к миру при снятии снимка.
     */
    private fun captureBorders(world: World) {
        // Грани по оси Y.
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                copyVoxel(world, x, -1, z)
                copyVoxel(world, x, 16, z)
            }
        }
        // Грани по оси Z.
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                copyVoxel(world, x, y, -1)
                copyVoxel(world, x, y, 16)
            }
        }
        // Грани по оси X.
        for (y in 0 until 16) {
            for (z in 0 until 16) {
                copyVoxel(world, -1, y, z)
                copyVoxel(world, 16, y, z)
            }
        }
        // Диагонали в плоскостях граней нужны для Ambient Occlusion
        // на рёбрах секции — их 12 линий по 16 вокселей.
        for (i in 0 until 16) {
            copyVoxel(world, -1, -1, i); copyVoxel(world, -1, 16, i)
            copyVoxel(world, 16, -1, i); copyVoxel(world, 16, 16, i)
            copyVoxel(world, -1, i, -1); copyVoxel(world, -1, i, 16)
            copyVoxel(world, 16, i, -1); copyVoxel(world, 16, i, 16)
            copyVoxel(world, i, -1, -1); copyVoxel(world, i, -1, 16)
            copyVoxel(world, i, 16, -1); copyVoxel(world, i, 16, 16)
        }
    }

    private fun copyVoxel(world: World, lx: Int, ly: Int, lz: Int) {
        val wx = originX + lx
        val wy = originY + ly
        val wz = originZ + lz
        val dst = index(lx, ly, lz)

        if (wy < 0 || wy > MAX_Y) {
            // Под миром — сплошная порода (грани вниз не строятся),
            // над миром — открытое небо.
            blocks[dst] = if (wy < 0) Blocks.BEDROCK.toByte() else Blocks.AIR.toByte()
            light[dst] = if (wy < 0) 0 else (MAX_LIGHT shl 4).toByte()
            return
        }

        val neighbourChunk = world.getChunkAtBlock(wx, wz)
        if (neighbourChunk == null) {
            // Чанк ещё не загружен. Помечаем как непрозрачный: тогда грани
            // на границе загруженной области не строятся, и игрок не видит
            // «стену мира», которая всё равно исчезнет через кадр.
            blocks[dst] = Blocks.BEDROCK.toByte()
            light[dst] = (MAX_LIGHT shl 4).toByte()
            return
        }

        val clx = wx and SECTION_MASK
        val clz = wz and SECTION_MASK
        blocks[dst] = neighbourChunk.getBlock(clx, wy, clz).toByte()
        light[dst] = (((neighbourChunk.getSkyLight(clx, wy, clz) and 0xF) shl 4) or
            (neighbourChunk.getBlockLight(clx, wy, clz) and 0xF)).toByte()
    }

    // --- Чтение ---

    /** Идентификатор блока по локальным координатам −1..16. */
    fun blockAt(x: Int, y: Int, z: Int): Int = blocks[index(x, y, z)].toInt() and 0xFF

    fun skyLightAt(x: Int, y: Int, z: Int): Int = (light[index(x, y, z)].toInt() shr 4) and 0xF

    fun blockLightAt(x: Int, y: Int, z: Int): Int = light[index(x, y, z)].toInt() and 0xF

    /** Биом колонки; координаты приводятся к диапазону секции. */
    fun biomeAt(x: Int, z: Int): Int {
        val cx = x.coerceIn(0, 15)
        val cz = z.coerceIn(0, 15)
        return biomes[(cz shl 4) or cx].toInt() and 0xFF
    }

    fun reset() {
        hasContent = false
    }

    companion object {
        /** Сторона области: 16 вокселей секции плюс по одному на кайму. */
        const val SIDE = 18
        const val VOLUME = SIDE * SIDE * SIDE

        /**
         * Индекс в снимке. Смещение на 1 переводит локальные координаты
         * −1..16 в неотрицательные 0..17.
         */
        inline fun index(x: Int, y: Int, z: Int): Int =
            ((y + 1) * SIDE + (z + 1)) * SIDE + (x + 1)

        /** Биом по умолчанию, если снимок не заполнен. */
        const val DEFAULT_BIOME = Biomes.PLAINS
    }
}
