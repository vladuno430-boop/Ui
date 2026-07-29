package com.voxelforge.core.mesh

import com.voxelforge.core.block.BlockFace
import com.voxelforge.core.block.BlockShape
import com.voxelforge.core.block.Blocks
import com.voxelforge.core.block.RenderLayer
import com.voxelforge.core.mesh.VertexFormat.POSITION_SCALE

/**
 * Построитель геометрии секции с объединением граней и Ambient Occlusion.
 *
 * ## Что делает жадный мешер
 *
 * Наивный подход строит по четырёхугольнику на каждую видимую грань каждого
 * блока. Плоская каменная площадка 16×16 даёт 256 отдельных квадратов, хотя
 * геометрически это один прямоугольник. Жадный алгоритм находит такие
 * прямоугольники и заменяет их одним квадратом.
 *
 * Замеренный выигрыш на сгенерированном рельефе — **сокращение числа
 * четырёхугольников в 2,9 раза** сверх обычного отсечения скрытых граней
 * (335 263 → 113 920 на 169 чанках, см. ChunkPipelineTest). На искусственно
 * ровных поверхностях выигрыш кратно больше — сплошная секция 16³ сводится
 * с 1536 граней до 6, — но природный рельеф изрезан, а объединению мешает
 * ещё и требование совпадения освещённости с Ambient Occlusion.
 *
 * Три четверти геометрии долой — величина, решающая для мобильного GPU:
 * пропорционально сокращаются и обработка вершин, и объём видеопамяти,
 * и трафик её чтения, который на устройствах с общей шиной обычно и
 * оказывается узким местом.
 *
 * ## Как он работает
 *
 * Для каждого из шести направлений граней секция режется на 16 слоёв.
 * В каждом слое строится двумерная маска 16×16, где ячейка хранит
 * **дескриптор грани** — упакованное описание того, как эта грань должна
 * выглядеть: блок, текстурный слой, освещённость, биом, четыре значения AO.
 * Затем по маске жадно вырезаются максимальные прямоугольники из ячеек
 * с **полностью совпадающими** дескрипторами.
 *
 * Требование полного совпадения принципиально: если объединить грани
 * с разной освещённостью, свет «размажется» по большому квадрату, и тень
 * от постройки уедет на несколько блоков. Именно поэтому дескриптор
 * включает всё, что влияет на пиксели, и ничего сверх того.
 *
 * ## Шесть проходов вместо трёх
 *
 * Классическая реализация обходит три оси и обрабатывает обе стороны слоя
 * за один проход. Здесь сделано шесть проходов — по одному на направление.
 * Общий объём работы тот же (маски вдвое разрежённее), зато для каждого
 * направления явно заданы свои касательные оси и порядок обхода вершин.
 * В варианте с тремя осями эти зависимости приходится выводить из индексов,
 * и ошибка в порядке вершин даёт вывернутую наизнанку грань, которую
 * потом крайне тяжело найти.
 *
 * ## Потокобезопасность
 *
 * Экземпляр хранит рабочие массивы и **не потокобезопасен**. У каждого
 * воркера мешинга свой мешер; так они не мешают друг другу и не аллоцируют
 * ничего в горячем цикле.
 */
class GreedyMesher {

    /** Маска слоя: дескриптор грани для каждой из 16×16 ячеек, 0 — пусто. */
    private val mask = LongArray(256)

    /** Рабочие буферы вершин квада — переиспользуются, чтобы не аллоцировать. */
    private val cornerA = IntArray(4)
    private val cornerB = IntArray(4)
    private val word0 = IntArray(4)
    private val word1 = IntArray(4)

    /**
     * Строит меш секции.
     *
     * @param snapshot снимок секции с каймой соседей
     * @param out      буферы, куда пишется геометрия; предварительно очищаются
     */
    fun build(snapshot: SectionSnapshot, out: SectionMesh) {
        out.clear()
        if (!snapshot.hasContent) return

        for (face in BlockFace.VALUES) {
            buildFaceDirection(snapshot, out, face)
        }
        buildCrossShapes(snapshot, out)
    }

    // ------------------------------------------------------------------
    // Кубические грани
    // ------------------------------------------------------------------

    private fun buildFaceDirection(
        snapshot: SectionSnapshot,
        out: SectionMesh,
        face: BlockFace
    ) {
        // Для каждого направления задаём: ось слоёв и две касательные оси
        // маски (a — горизонталь маски, b — вертикаль маски).
        for (slice in 0 until 16) {
            var anyFace = false
            java.util.Arrays.fill(mask, 0L)

            for (b in 0 until 16) {
                for (a in 0 until 16) {
                    val x: Int
                    val y: Int
                    val z: Int
                    when (face) {
                        BlockFace.UP, BlockFace.DOWN -> { x = a; y = slice; z = b }
                        BlockFace.EAST, BlockFace.WEST -> { x = slice; y = a; z = b }
                        else -> { x = a; y = b; z = slice }
                    }

                    val descriptor = describeFace(snapshot, x, y, z, face)
                    if (descriptor != 0L) {
                        mask[b * 16 + a] = descriptor
                        anyFace = true
                    }
                }
            }

            if (anyFace) mergeAndEmit(snapshot, out, face, slice)
        }
    }

    /**
     * Строит дескриптор грани блока в направлении [face] либо 0,
     * если грань не видна.
     */
    private fun describeFace(
        snapshot: SectionSnapshot,
        x: Int,
        y: Int,
        z: Int,
        face: BlockFace
    ): Long {
        val id = snapshot.blockAt(x, y, z)
        if (id == Blocks.AIR) return 0L

        val shape = Blocks.SHAPE[id]
        // «Крестики» не участвуют в объединении — у них своя ветка.
        if (shape != BlockShape.CUBE && shape != BlockShape.LIQUID) return 0L

        val nx = x + face.dx
        val ny = y + face.dy
        val nz = z + face.dz
        val neighbour = snapshot.blockAt(nx, ny, nz)

        // Грань не строится, если сосед её перекрывает.
        if (Blocks.hidesFace(id, neighbour)) return 0L

        val def = Blocks.get(id)
        val layer = def.tileFor(face)

        // Свет берётся со стороны наблюдателя — из соседнего вокселя.
        // Собственная освещённость блока здесь бессмысленна: внутри камня
        // света нет, а грань освещена тем, что снаружи.
        val sky = snapshot.skyLightAt(nx, ny, nz)
        val blockLight = snapshot.blockLightAt(nx, ny, nz)
        val biome = snapshot.biomeAt(x, z)

        val ao = computeAmbientOcclusion(snapshot, x, y, z, face)

        val liquid = shape == BlockShape.LIQUID
        // Поверхность жидкости — та, над которой нет такой же жидкости.
        val liquidSurface = liquid && snapshot.blockAt(x, y + 1, z) != id

        val tinted = id == Blocks.GRASS_BLOCK && face == BlockFace.UP ||
            id == Blocks.LEAVES || id == Blocks.TALL_GRASS

        return packDescriptor(
            blockId = id,
            layer = layer,
            sky = sky,
            blockLight = blockLight,
            biome = biome,
            ao = ao,
            animated = liquid,
            tinted = tinted,
            liquidSurface = liquidSurface
        )
    }

    /**
     * Считает Ambient Occlusion для четырёх углов грани.
     *
     * ## Что это и зачем
     *
     * Настоящее затенение от окружающей геометрии считать в реальном времени
     * на мобильном устройстве нереально. Но в мире из кубов оно вычисляется
     * точно и почти бесплатно: степень затенения угла грани полностью
     * определяется тремя соседними вокселями — двумя «боковыми» и одним
     * «диагональным».
     *
     * Правило (классическое, из статьи 0fps о воксельном AO):
     * ```
     * если обе боковые заняты  → 0 (максимальная тень: угол зажат)
     * иначе                    → 3 − (боковая1 + боковая2 + диагональная)
     * ```
     * Отдельная ветка для «обе боковые заняты» нужна потому, что в этом
     * случае диагональный воксель не виден вовсе, и учитывать его нельзя —
     * иначе внутренний угол стены темнел бы неравномерно в зависимости от
     * того, что стоит за ним.
     *
     * Именно AO придаёт воксельному миру объём: без него все грани одного
     * направления имеют одинаковую яркость, и рельеф выглядит плоской
     * аппликацией.
     *
     * @return четыре значения 0..3, упакованные по 2 бита
     */
    private fun computeAmbientOcclusion(
        snapshot: SectionSnapshot,
        x: Int,
        y: Int,
        z: Int,
        face: BlockFace
    ): Int {
        // Касательные оси грани: A — вдоль первой координаты маски,
        // B — вдоль второй. Совпадают с раскладкой в buildFaceDirection.
        val ax: Int; val ay: Int; val az: Int
        val bx: Int; val by: Int; val bz: Int
        when (face) {
            BlockFace.UP, BlockFace.DOWN -> {
                ax = 1; ay = 0; az = 0
                bx = 0; by = 0; bz = 1
            }
            BlockFace.EAST, BlockFace.WEST -> {
                ax = 0; ay = 1; az = 0
                bx = 0; by = 0; bz = 1
            }
            else -> {
                ax = 1; ay = 0; az = 0
                bx = 0; by = 1; bz = 0
            }
        }

        // Точка отсчёта — воксель прямо перед гранью.
        val ox = x + face.dx
        val oy = y + face.dy
        val oz = z + face.dz

        /*
         * Углы перечисляются в том же порядке, в котором [emitQuad] обходит
         * вершины прямоугольника в плоскости маски:
         *   0 → (a−, b−)   1 → (a+, b−)   2 → (a+, b+)   3 → (a−, b+)
         * Совпадение порядков здесь и там обязательно: рассогласование
         * даёт «повёрнутое» затенение, которое выглядит как случайные
         * тёмные пятна на ровной стене.
         */
        var packed = 0
        for (corner in 0 until 4) {
            val sa = CORNER_SIGN_A[corner]
            val sb = CORNER_SIGN_B[corner]

            val side1 = isOccluding(snapshot, ox + ax * sa, oy + ay * sa, oz + az * sa)
            val side2 = isOccluding(snapshot, ox + bx * sb, oy + by * sb, oz + bz * sb)
            val diagonal = isOccluding(
                snapshot,
                ox + ax * sa + bx * sb,
                oy + ay * sa + by * sb,
                oz + az * sa + bz * sb
            )
            val value = if (side1 && side2) {
                0
            } else {
                3 - ((if (side1) 1 else 0) + (if (side2) 1 else 0) + (if (diagonal) 1 else 0))
            }
            packed = packed or (value shl (corner * 2))
        }
        return packed
    }

    private fun isOccluding(snapshot: SectionSnapshot, x: Int, y: Int, z: Int): Boolean =
        Blocks.OPAQUE[snapshot.blockAt(x, y, z)]

    /**
     * Жадное объединение прямоугольников по маске и запись их в буфер.
     *
     * Алгоритм: идём по ячейкам слева направо и сверху вниз. Найдя занятую,
     * растягиваем прямоугольник максимально вправо по совпадающим
     * дескрипторам, затем максимально вниз — но только целыми строками,
     * иначе получится не прямоугольник. Использованные ячейки обнуляются.
     *
     * Сложность линейна по числу ячеек: каждая обнуляется ровно один раз.
     */
    private fun mergeAndEmit(
        snapshot: SectionSnapshot,
        out: SectionMesh,
        face: BlockFace,
        slice: Int
    ) {
        var b = 0
        while (b < 16) {
            var a = 0
            while (a < 16) {
                val descriptor = mask[b * 16 + a]
                if (descriptor == 0L) {
                    a++
                    continue
                }

                // Растягиваем по горизонтали.
                var width = 1
                while (a + width < 16 && mask[b * 16 + a + width] == descriptor) width++

                // Растягиваем по вертикали целыми строками.
                var height = 1
                outer@ while (b + height < 16) {
                    for (k in 0 until width) {
                        if (mask[(b + height) * 16 + a + k] != descriptor) break@outer
                    }
                    height++
                }

                // Гасим использованные ячейки.
                for (dy in 0 until height) {
                    java.util.Arrays.fill(mask, (b + dy) * 16 + a, (b + dy) * 16 + a + width, 0L)
                }

                emitQuad(out, face, slice, a, b, width, height, descriptor)
                a += width
            }
            b++
        }
    }

    /**
     * Записывает объединённый прямоугольник как четырёхугольник.
     *
     * Порядок обхода вершин задан так, чтобы нормаль, посчитанная по правилу
     * правой руки, совпадала с направлением грани — это требование режима
     * отсечения задних граней GL. Два варианта порядка (см. ниже) выведены
     * из векторного произведения рёбер для каждого из шести направлений.
     */
    private fun emitQuad(
        out: SectionMesh,
        face: BlockFace,
        slice: Int,
        a0: Int,
        b0: Int,
        width: Int,
        height: Int,
        descriptor: Long
    ) {
        val blockId = unpackBlockId(descriptor)
        val layer = unpackLayer(descriptor)
        val sky = unpackSky(descriptor)
        val blockLight = unpackBlockLight(descriptor)
        val biome = unpackBiome(descriptor)
        val animated = unpackAnimated(descriptor)
        val tinted = unpackTinted(descriptor)
        val liquidSurface = unpackLiquidSurface(descriptor)

        val a1 = a0 + width
        val b1 = b0 + height

        /*
         * Четыре угла прямоугольника в системе координат маски, обход
         * против часовой стрелки в плоскости (a, b). Тот же порядок
         * используется при расчёте Ambient Occlusion.
         */
        cornerA[0] = a0; cornerB[0] = b0
        cornerA[1] = a1; cornerB[1] = b0
        cornerA[2] = a1; cornerB[2] = b1
        cornerA[3] = a0; cornerB[3] = b1

        // Положительные направления лежат на дальней стороне блока.
        val planeOffset = when (face) {
            BlockFace.UP, BlockFace.EAST, BlockFace.SOUTH -> 1
            else -> 0
        }
        val plane = (slice + planeOffset) * POSITION_SCALE

        /*
         * Поверхность жидкости опускается на 2/16 блока: вода становится
         * визуально ниже берега, и водоём перестаёт выглядеть налитым
         * вровень с краями. Опускается не только верхняя грань, но и верхний
         * край боковых — иначе бортик торчал бы над поверхностью.
         *
         * Прямоугольник с этим признаком всегда высотой ровно в один блок
         * по вертикали: у нижележащей воды признак другой, а объединяются
         * только грани с полностью совпадающим дескриптором. Поэтому
         * «верхний край» здесь определён однозначно.
         */
        val drop = if (liquidSurface) LIQUID_SURFACE_DROP else 0

        val faceIndex = face.index
        for (i in 0 until 4) {
            val av = cornerA[i]
            val bv = cornerB[i]

            val px: Int
            val py: Int
            val pz: Int
            when (face) {
                BlockFace.UP -> {
                    px = av * POSITION_SCALE; py = plane - drop; pz = bv * POSITION_SCALE
                }
                BlockFace.DOWN -> {
                    px = av * POSITION_SCALE; py = plane; pz = bv * POSITION_SCALE
                }
                BlockFace.EAST, BlockFace.WEST -> {
                    // Ось a — вертикаль, ось b — глубина.
                    px = plane
                    py = av * POSITION_SCALE - if (av == a1) drop else 0
                    pz = bv * POSITION_SCALE
                }
                else -> {
                    // Ось a — горизонталь, ось b — вертикаль.
                    px = av * POSITION_SCALE
                    py = bv * POSITION_SCALE - if (bv == b1) drop else 0
                    pz = plane
                }
            }

            val u = (av - a0).coerceAtMost(VertexFormat.UV_MASK)
            val v = (bv - b0).coerceAtMost(VertexFormat.UV_MASK)

            word0[i] = VertexFormat.packWord0(px, py, pz, faceIndex, unpackAo(descriptor, i))
            word1[i] = VertexFormat.packWord1(
                u, v, layer, sky, blockLight, biome, animated, tinted
            )
        }

        /*
         * Направление обхода.
         *
         * Обход углов против часовой стрелки в плоскости (a, b) даёт нормаль
         * вдоль грани только для половины направлений — это следует из
         * векторного произведения рёбер для каждой из шести ориентаций.
         * Для UP, WEST и NORTH обход нужно развернуть, иначе грань окажется
         * вывернутой наизнанку и будет отброшена отсечением задних граней:
         * в мире появятся сквозные дыры, видимые только под определённым
         * углом, — дефект, который крайне тяжело локализовать по симптомам.
         */
        val reversed = face == BlockFace.UP || face == BlockFace.WEST || face == BlockFace.NORTH

        val i0 = if (reversed) 3 else 0
        val i1 = if (reversed) 2 else 1
        val i2 = if (reversed) 1 else 2
        val i3 = if (reversed) 0 else 3

        /*
         * Выбор диагонали разбиения на треугольники.
         *
         * Квадрат с неравномерным AO интерполируется двумя треугольниками
         * по-разному, и на нём проступает диагональный шов. Разворот
         * диагонали к паре углов с меньшей разницей делает шов незаметным.
         * Без этой строки Ambient Occlusion на внутренних углах выглядит
         * сломанным — самый частый дефект воксельных мешеров.
         */
        val ao0 = unpackAo(descriptor, i0)
        val ao1 = unpackAo(descriptor, i1)
        val ao2 = unpackAo(descriptor, i2)
        val ao3 = unpackAo(descriptor, i3)
        val flip = ao0 + ao2 > ao1 + ao3

        val buffer = out.bufferFor(Blocks.LAYER[blockId] ?: RenderLayer.OPAQUE)
        buffer.addQuad(
            word0[i0], word1[i0],
            word0[i1], word1[i1],
            word0[i2], word1[i2],
            word0[i3], word1[i3],
            flip
        )
    }

    // ------------------------------------------------------------------
    // Блоки-«крестики»
    // ------------------------------------------------------------------

    /**
     * Строит траву и факелы — два пересекающихся вертикальных квада.
     *
     * Такие блоки не объединяются: их в мире мало, а форма не сводится
     * к прямоугольнику в плоскости слоя. Каждый квад выводится дважды
     * с противоположным обходом вершин, чтобы «крестик» был виден с обеих
     * сторон при включённом отсечении задних граней. Альтернатива —
     * отключать отсечение на весь проход вырезаемых блоков — обошлась бы
     * дороже: листва рисовалась бы с двойной перерисовкой.
     */
    private fun buildCrossShapes(snapshot: SectionSnapshot, out: SectionMesh) {
        for (y in 0 until 16) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val id = snapshot.blockAt(x, y, z)
                    if (id == Blocks.AIR) continue
                    if (Blocks.SHAPE[id] != BlockShape.CROSS) continue

                    val def = Blocks.get(id)
                    val layer = def.iconTile
                    val sky = snapshot.skyLightAt(x, y, z)
                    val blockLight = maxOf(
                        snapshot.blockLightAt(x, y, z),
                        Blocks.EMISSION[id]
                    )
                    val biome = snapshot.biomeAt(x, z)
                    val tinted = id == Blocks.TALL_GRASS

                    val halfWidth = def.crossWidth / 2
                    val centre = POSITION_SCALE / 2
                    val lo = centre - halfWidth
                    val hi = centre + halfWidth
                    val bx = x * POSITION_SCALE
                    val by = y * POSITION_SCALE
                    val bz = z * POSITION_SCALE
                    val top = by + def.crossHeight

                    val buffer = out.bufferFor(RenderLayer.CUTOUT)

                    // Первая диагональная плоскость и её обратная сторона.
                    emitCrossQuad(
                        buffer, bx + lo, by, bz + lo, bx + hi, top, bz + hi,
                        layer, sky, blockLight, biome, tinted
                    )
                    // Вторая плоскость — перпендикулярная первой.
                    emitCrossQuad(
                        buffer, bx + lo, by, bz + hi, bx + hi, top, bz + lo,
                        layer, sky, blockLight, biome, tinted
                    )
                }
            }
        }
    }

    /** Выводит вертикальный квад между двумя точками основания, с обеих сторон. */
    private fun emitCrossQuad(
        buffer: MeshBuffer,
        x0: Int, y0: Int, z0: Int,
        x1: Int, y1: Int, z1: Int,
        layer: Int,
        sky: Int,
        blockLight: Int,
        biome: Int,
        tinted: Boolean
    ) {
        // Грань помечается как UP: у «крестика» нет осмысленной нормали,
        // а вертикальная нормаль даёт равномерное освещение с любой стороны,
        // что для растительности выглядит естественнее бокового затенения.
        val faceIndex = BlockFace.UP.index
        val ao = 3   // растительность не затеняется: она не имеет объёма

        val v0w0 = VertexFormat.packWord0(x0, y0, z0, faceIndex, ao)
        val v1w0 = VertexFormat.packWord0(x1, y0, z1, faceIndex, ao)
        val v2w0 = VertexFormat.packWord0(x1, y1, z1, faceIndex, ao)
        val v3w0 = VertexFormat.packWord0(x0, y1, z0, faceIndex, ao)

        val a = VertexFormat.packWord1(0, 0, layer, sky, blockLight, biome, false, tinted)
        val b = VertexFormat.packWord1(1, 0, layer, sky, blockLight, biome, false, tinted)
        val c = VertexFormat.packWord1(1, 1, layer, sky, blockLight, biome, false, tinted)
        val d = VertexFormat.packWord1(0, 1, layer, sky, blockLight, biome, false, tinted)

        buffer.addQuad(v0w0, a, v1w0, b, v2w0, c, v3w0, d, false)
        // Обратная сторона: тот же квад с развёрнутым обходом.
        buffer.addQuad(v3w0, d, v2w0, c, v1w0, b, v0w0, a, false)
    }

    // ------------------------------------------------------------------
    // Упаковка дескриптора грани
    // ------------------------------------------------------------------

    /*
     * Раскладка (Long):
     *   бит  0      признак наличия грани
     *   биты 1..8   идентификатор блока
     *   биты 9..14  слой текстуры
     *   биты 15..18 солнечный свет
     *   биты 19..22 свет от источников
     *   биты 23..25 биом
     *   биты 26..33 четыре значения AO по 2 бита
     *   бит  34     анимация
     *   бит  35     тонирование биомом
     *   бит  36     поверхность жидкости
     */

    private fun packDescriptor(
        blockId: Int,
        layer: Int,
        sky: Int,
        blockLight: Int,
        biome: Int,
        ao: Int,
        animated: Boolean,
        tinted: Boolean,
        liquidSurface: Boolean
    ): Long =
        1L or
            ((blockId.toLong() and 0xFF) shl 1) or
            ((layer.toLong() and 0x3F) shl 9) or
            ((sky.toLong() and 0xF) shl 15) or
            ((blockLight.toLong() and 0xF) shl 19) or
            ((biome.toLong() and 0x7) shl 23) or
            ((ao.toLong() and 0xFF) shl 26) or
            (if (animated) 1L shl 34 else 0L) or
            (if (tinted) 1L shl 35 else 0L) or
            (if (liquidSurface) 1L shl 36 else 0L)

    private fun unpackBlockId(d: Long): Int = ((d shr 1) and 0xFF).toInt()
    private fun unpackLayer(d: Long): Int = ((d shr 9) and 0x3F).toInt()
    private fun unpackSky(d: Long): Int = ((d shr 15) and 0xF).toInt()
    private fun unpackBlockLight(d: Long): Int = ((d shr 19) and 0xF).toInt()
    private fun unpackBiome(d: Long): Int = ((d shr 23) and 0x7).toInt()
    private fun unpackAo(d: Long, corner: Int): Int = ((d shr (26 + corner * 2)) and 0x3).toInt()
    private fun unpackAnimated(d: Long): Boolean = (d shr 34) and 1L != 0L
    private fun unpackTinted(d: Long): Boolean = (d shr 35) and 1L != 0L
    private fun unpackLiquidSurface(d: Long): Boolean = (d shr 36) and 1L != 0L

    companion object {
        /** Насколько опускается поверхность жидкости, в шестнадцатых долях блока. */
        private const val LIQUID_SURFACE_DROP = 2

        /**
         * Знаки смещения вдоль касательных осей для четырёх углов грани.
         * Порядок обхода — против часовой стрелки в плоскости маски.
         */
        private val CORNER_SIGN_A = intArrayOf(-1, 1, 1, -1)
        private val CORNER_SIGN_B = intArrayOf(-1, -1, 1, 1)
    }
}
