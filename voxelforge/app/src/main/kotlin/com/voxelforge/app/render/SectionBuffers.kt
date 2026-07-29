package com.voxelforge.app.render

import android.opengl.GLES30
import com.voxelforge.core.mesh.MeshBuffer
import com.voxelforge.core.mesh.VertexFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.ShortBuffer

/**
 * Буферы GPU для одного прохода отрисовки одной секции.
 *
 * Хранит вершинный массив (VAO), буфер вершин и буфер индексов.
 *
 * ## Почему VAO, а не установка атрибутов перед каждой отрисовкой
 *
 * Формат вершины одинаков у всех секций, и, казалось бы, можно настроить
 * атрибуты один раз. Но привязка нового буфера вершин сбрасывает указатели
 * атрибутов, поэтому без VAO перед каждой из сотен секций пришлось бы
 * заново вызывать `glVertexAttribIPointer` дважды. VAO запоминает всю
 * конфигурацию, и переключение секции сводится к одному вызову
 * `glBindVertexArray` — примерно в пять раз меньше обращений к драйверу.
 */
class LayerBuffers {

    private var vao = 0
    private var vbo = 0
    private var ibo = 0

    /** Число индексов для отрисовки. Ноль означает «нечего рисовать». */
    var indexCount = 0
        private set

    /** Размер выделенного буфера вершин в байтах — для дозаписи без перевыделения. */
    private var vertexCapacityBytes = 0
    private var indexCapacityBytes = 0

    val isEmpty: Boolean get() = indexCount == 0

    /**
     * Загружает содержимое буфера мешера в GPU.
     *
     * Повторная загрузка в уже выделенный буфер достаточного размера идёт
     * через `glBufferSubData` без перевыделения. Это важно при активном
     * строительстве: каждая поставленная плита перестраивает секцию, и
     * выделение нового буфера каждый раз приводило бы к фрагментации
     * видеопамяти и лишней синхронизации с драйвером.
     */
    fun upload(source: MeshBuffer) {
        if (source.isEmpty) {
            indexCount = 0
            return
        }
        ensureCreated()

        val vertexBytes = source.vertexWordCount * 4
        val indexBytes = source.indexCount * 2

        GLES30.glBindVertexArray(vao)

        // --- Вершины ---
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val vertexData = wrapInts(source.vertices, source.vertexWordCount)
        if (vertexBytes > vertexCapacityBytes) {
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, vertexBytes, vertexData, GLES30.GL_DYNAMIC_DRAW
            )
            vertexCapacityBytes = vertexBytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertexBytes, vertexData)
        }

        /*
         * Атрибуты объявлены как целочисленные (glVertexAttribIPointer,
         * не glVertexAttribPointer). Разница принципиальна: обычная версия
         * преобразовала бы биты в число с плавающей точкой, и распаковка
         * сдвигами в шейдере потеряла бы смысл — часть битов исказилась бы
         * при переводе в float и обратно.
         */
        val stride = VertexFormat.BYTES_PER_VERTEX
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, 1, GLES30.GL_UNSIGNED_INT, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribIPointer(1, 1, GLES30.GL_UNSIGNED_INT, stride, 4)

        // --- Индексы ---
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val indexData = wrapShorts(source.indices, source.indexCount)
        if (indexBytes > indexCapacityBytes) {
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, indexData, GLES30.GL_DYNAMIC_DRAW
            )
            indexCapacityBytes = indexBytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, indexBytes, indexData)
        }

        GLES30.glBindVertexArray(0)
        indexCount = source.indexCount
    }

    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0
        )
    }

    private fun ensureCreated() {
        if (vao != 0) return
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vao = arrays[0]

        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        vbo = buffers[0]
        ibo = buffers[1]
    }

    fun dispose() {
        if (vao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
            vao = 0
            vbo = 0
            ibo = 0
        }
        indexCount = 0
        vertexCapacityBytes = 0
        indexCapacityBytes = 0
    }

    /** Приблизительный расход видеопамяти в байтах — для отладочной панели. */
    fun memoryUsage(): Int = vertexCapacityBytes + indexCapacityBytes

    companion object {
        /*
         * Промежуточные прямые буферы. GL требует прямой (direct) буфер либо
         * массив; передача обычного IntArray заставляет привязку копировать
         * его во временный буфер на каждый вызов. Переиспользуемый прямой
         * буфер, растущий по мере надобности, убирает это копирование.
         *
         * Буферы общие на весь поток отрисовки — он единственный, кто
         * загружает геометрию, поэтому синхронизация не нужна.
         */
        private var scratchBytes: ByteBuffer = allocate(1 shl 16)
        private var scratchInts: IntBuffer = scratchBytes.asIntBuffer()
        private var scratchShorts: ShortBuffer = scratchBytes.asShortBuffer()

        private fun allocate(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        private fun ensureScratch(bytes: Int) {
            if (scratchBytes.capacity() >= bytes) return
            var size = scratchBytes.capacity()
            while (size < bytes) size *= 2
            scratchBytes = allocate(size)
            scratchInts = scratchBytes.asIntBuffer()
            scratchShorts = scratchBytes.asShortBuffer()
        }

        private fun wrapInts(data: IntArray, count: Int): IntBuffer {
            ensureScratch(count * 4)
            scratchInts.clear()
            scratchInts.put(data, 0, count)
            scratchInts.position(0)
            scratchInts.limit(count)
            return scratchInts
        }

        private fun wrapShorts(data: ShortArray, count: Int): ShortBuffer {
            ensureScratch(count * 2)
            scratchShorts.clear()
            scratchShorts.put(data, 0, count)
            scratchShorts.position(0)
            scratchShorts.limit(count)
            return scratchShorts
        }
    }
}

/**
 * Полный набор буферов одной секции: три прохода отрисовки.
 *
 * Хранит также мировые координаты секции — они нужны каждый кадр для
 * отсечения по пирамиде видимости и для вычисления смещения относительно
 * камеры.
 */
class SectionBuffers(
    @JvmField val chunkX: Int,
    @JvmField val sectionY: Int,
    @JvmField val chunkZ: Int
) {
    @JvmField val opaque = LayerBuffers()
    @JvmField val cutout = LayerBuffers()
    @JvmField val translucent = LayerBuffers()

    /** Мировые координаты угла секции. */
    @JvmField val originX = chunkX * 16
    @JvmField val originY = sectionY * 16
    @JvmField val originZ = chunkZ * 16

    val isEmpty: Boolean
        get() = opaque.isEmpty && cutout.isEmpty && translucent.isEmpty

    fun dispose() {
        opaque.dispose()
        cutout.dispose()
        translucent.dispose()
    }

    fun memoryUsage(): Int =
        opaque.memoryUsage() + cutout.memoryUsage() + translucent.memoryUsage()
}
