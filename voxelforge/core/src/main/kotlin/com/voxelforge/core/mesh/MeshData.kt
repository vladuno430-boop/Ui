package com.voxelforge.core.mesh

import com.voxelforge.core.block.RenderLayer

/**
 * Растущий буфер вершин и индексов для одного прохода отрисовки.
 *
 * Назначение: накапливать геометрию во время мешинга. Обычный
 * `ArrayList<Int>` здесь неприменим: каждый элемент боксировался бы
 * в `java.lang.Integer`, то есть на секцию с 700 гранями пришлось бы
 * около 8400 объектов — это гарантированные паузы сборщика мусора
 * в фоновом потоке и всплески расхода памяти.
 *
 * Массивы примитивов растут удвоением. Начальная ёмкость подобрана под
 * типичную секцию: подземные секции почти сплошные и дают мало граней,
 * а поверхностные — порядка 500–900. При превышении массив удваивается,
 * так что перевыделений за перестройку бывает не больше одного-двух.
 */
class MeshBuffer(initialQuads: Int = 512) {

    /** Упакованные вершины: два целых слова на вершину. */
    @JvmField
    var vertices = IntArray(initialQuads * VertexFormat.VERTICES_PER_QUAD * VertexFormat.INTS_PER_VERTEX)

    /**
     * Индексы. Тип Short выбран не ради экономии, а потому что мобильные
     * GPU обрабатывают 16-битные индексы заметно быстрее 32-битных:
     * вдвое меньше трафик и вдвое эффективнее кэш вершин после трансформации.
     * Ограничение в 65 536 вершин на секцию недостижимо на практике —
     * даже полностью «шахматная» секция даёт около 25 000.
     */
    @JvmField
    var indices = ShortArray(initialQuads * VertexFormat.INDICES_PER_QUAD)

    /** Число заполненных целых слов в [vertices]. */
    @JvmField
    var vertexWordCount = 0

    /** Число заполненных элементов в [indices]. */
    @JvmField
    var indexCount = 0

    val vertexCount: Int get() = vertexWordCount / VertexFormat.INTS_PER_VERTEX

    val quadCount: Int get() = indexCount / VertexFormat.INDICES_PER_QUAD

    val isEmpty: Boolean get() = indexCount == 0

    /**
     * Добавляет четырёхугольник из четырёх упакованных вершин.
     *
     * Порядок обхода вершин — против часовой стрелки при взгляде снаружи,
     * это соответствует режиму отсечения задних граней в GL.
     *
     * Параметр [flipDiagonal] разворачивает диагональ разбиения на треугольники.
     * Без него на гранях с неравномерным затенением возникает характерный
     * артефакт: два треугольника интерполируют освещение по-разному, и на
     * квадрате виден диагональный шов. Разворот диагонали в сторону, где
     * значения AO ближе, делает шов невидимым. Это классическая деталь,
     * без которой Ambient Occlusion на кубах смотрится сломанным.
     */
    fun addQuad(
        v0w0: Int, v0w1: Int,
        v1w0: Int, v1w1: Int,
        v2w0: Int, v2w1: Int,
        v3w0: Int, v3w1: Int,
        flipDiagonal: Boolean
    ) {
        ensureVertexCapacity(8)
        ensureIndexCapacity(6)

        val base = vertexCount
        var w = vertexWordCount
        vertices[w++] = v0w0; vertices[w++] = v0w1
        vertices[w++] = v1w0; vertices[w++] = v1w1
        vertices[w++] = v2w0; vertices[w++] = v2w1
        vertices[w++] = v3w0; vertices[w++] = v3w1
        vertexWordCount = w

        var i = indexCount
        if (flipDiagonal) {
            indices[i++] = (base + 1).toShort()
            indices[i++] = (base + 2).toShort()
            indices[i++] = (base + 3).toShort()
            indices[i++] = (base + 1).toShort()
            indices[i++] = (base + 3).toShort()
            indices[i++] = (base + 0).toShort()
        } else {
            indices[i++] = (base + 0).toShort()
            indices[i++] = (base + 1).toShort()
            indices[i++] = (base + 2).toShort()
            indices[i++] = (base + 0).toShort()
            indices[i++] = (base + 2).toShort()
            indices[i++] = (base + 3).toShort()
        }
        indexCount = i
    }

    private fun ensureVertexCapacity(extraWords: Int) {
        if (vertexWordCount + extraWords <= vertices.size) return
        var newSize = vertices.size * 2
        while (newSize < vertexWordCount + extraWords) newSize *= 2
        vertices = vertices.copyOf(newSize)
    }

    private fun ensureIndexCapacity(extra: Int) {
        if (indexCount + extra <= indices.size) return
        var newSize = indices.size * 2
        while (newSize < indexCount + extra) newSize *= 2
        indices = indices.copyOf(newSize)
    }

    /**
     * Сбрасывает счётчики, **сохраняя** выделенные массивы.
     * Именно ради этого буферы живут в пуле: повторная перестройка той же
     * секции не выделяет ни байта.
     */
    fun clear() {
        vertexWordCount = 0
        indexCount = 0
    }

    /**
     * Ужимает массивы, если они заметно переросли фактическую нагрузку.
     * Вызывается при возврате буфера в пул: без этого один аномально
     * сложный участок мира навсегда раздул бы все буферы пула.
     */
    fun trimIfOversized(maxWords: Int = 1 shl 16) {
        if (vertices.size > maxWords && vertexWordCount * 4 < vertices.size) {
            vertices = IntArray(maxOf(vertexWordCount, 1024))
        }
        if (indices.size > maxWords && indexCount * 4 < indices.size) {
            indices = ShortArray(maxOf(indexCount, 1024))
        }
    }
}

/**
 * Результат мешинга одной секции: три буфера по числу проходов отрисовки.
 *
 * Назначение: передать геометрию из потока-воркера в поток рендера.
 *
 * **Почему проходов именно три.** Каждый требует своего состояния GL,
 * и смешивать их в один буфер нельзя:
 *  - непрозрачный рисуется первым с записью глубины — это позволяет GPU
 *    отбрасывать закрытые фрагменты до фрагментного шейдера;
 *  - вырезаемый (листва, трава) отбрасывает пиксели по альфе, из-за чего
 *    ранняя проверка глубины отключается, и держать его отдельно выгодно;
 *  - полупрозрачный требует смешивания, отключённой записи глубины
 *    и сортировки секций от дальних к ближним.
 *
 * Разделение на этапе мешинга, а не отрисовки, означает ноль сортировки
 * и ноль переключений состояния GL внутри прохода.
 */
class SectionMesh {

    @JvmField
    val opaque = MeshBuffer(512)

    @JvmField
    val cutout = MeshBuffer(64)

    @JvmField
    val translucent = MeshBuffer(64)

    /** Координаты секции, для которой построен меш. */
    @JvmField var chunkX = 0
    @JvmField var sectionY = 0
    @JvmField var chunkZ = 0

    /**
     * Счётчик поколения. Пока меш строился в фоне, игрок мог сломать ещё
     * один блок, и секция была помечена заново. Сравнение поколений
     * на приёме результата позволяет отбросить устаревший меш, не рисуя
     * кадр с уже несуществующей геометрией.
     */
    @JvmField var generation = 0

    fun bufferFor(layer: RenderLayer): MeshBuffer = when (layer) {
        RenderLayer.OPAQUE -> opaque
        RenderLayer.CUTOUT -> cutout
        RenderLayer.TRANSLUCENT -> translucent
    }

    val isEmpty: Boolean
        get() = opaque.isEmpty && cutout.isEmpty && translucent.isEmpty

    val totalQuads: Int
        get() = opaque.quadCount + cutout.quadCount + translucent.quadCount

    fun clear() {
        opaque.clear()
        cutout.clear()
        translucent.clear()
    }

    fun trim() {
        opaque.trimIfOversized()
        cutout.trimIfOversized()
        translucent.trimIfOversized()
    }
}
