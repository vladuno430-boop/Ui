package com.voxelforge.app.render

import android.opengl.GLES30
import com.voxelforge.app.gl.ShaderProgram
import com.voxelforge.app.gl.Shaders
import com.voxelforge.app.gl.TextureArray
import com.voxelforge.core.math.Mat4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Отрисовка двумерного интерфейса: сенсорное управление, прицел, инвентарь.
 *
 * ## Пакетная отрисовка
 *
 * Интерфейс состоит из десятков элементов: кнопки, джойстик, ячейки
 * быстрого доступа, иконки блоков, полоски. Рисовать каждый отдельным
 * вызовом — значит потратить полсотни обращений к драйверу на то, что
 * занимает доли процента экрана.
 *
 * Здесь все элементы кадра собираются в один буфер вершин и выводятся
 * **одним вызовом отрисовки**. Это возможно потому, что и сплошные
 * прямоугольники, и иконки блоков используют один шейдер: признаком
 * «без текстуры» служит отрицательный номер слоя в самой вершине.
 *
 * ## Система координат
 *
 * Координаты задаются в пикселях экрана, начало — левый верхний угол.
 * Ортографическая проекция переводит их в нормализованные координаты
 * устройства. Пиксели, а не доли экрана, выбраны сознательно: размер
 * кнопки под палец — величина физическая (около 9 мм), и задавать её
 * в долях экрана значит получить неработающее управление на планшете.
 */
class UiRenderer {

    private lateinit var program: ShaderProgram
    private var vao = 0
    private var vbo = 0

    private var vertexData = FloatArray(INITIAL_QUADS * FLOATS_PER_QUAD)
    private var vertexCount = 0
    private var buffer: FloatBuffer = allocate(vertexData.size)

    private val projection = Mat4()
    private var screenWidth = 1
    private var screenHeight = 1

    fun initialize() {
        program = ShaderProgram("ui", Shaders.UI_VERTEX, Shaders.UI_FRAGMENT)

        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        vao = arrays[0]

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)

        val stride = FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 2 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 5 * 4)

        GLES30.glBindVertexArray(0)
    }

    fun resize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        // Ось Y направлена вниз: так координаты совпадают с системой
        // событий касания, и не нужно переворачивать каждую координату.
        projection.setOrtho(0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
    }

    /** Начинает новый кадр интерфейса. */
    fun begin() {
        vertexCount = 0
    }

    /**
     * Добавляет сплошной прямоугольник.
     * Цвет задаётся в формате 0xAARRGGBB.
     */
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        addQuad(x, y, w, h, -1f, 0f, 0f, 1f, 1f, color)
    }

    /** Добавляет иконку блока из текстурного массива. */
    fun icon(x: Float, y: Float, w: Float, h: Float, layer: Int, color: Int = 0xFFFFFFFF.toInt()) {
        addQuad(x, y, w, h, layer.toFloat(), 0f, 0f, 1f, 1f, color)
    }

    /**
     * Добавляет рамку заданной толщины — четыре тонких прямоугольника.
     * Отдельный метод, потому что рамка используется постоянно: выделение
     * активной ячейки, обводка кнопок, контур панели.
     */
    fun frame(x: Float, y: Float, w: Float, h: Float, thickness: Float, color: Int) {
        rect(x, y, w, thickness, color)
        rect(x, y + h - thickness, w, thickness, color)
        rect(x, y + thickness, thickness, h - thickness * 2, color)
        rect(x + w - thickness, y + thickness, thickness, h - thickness * 2, color)
    }

    /**
     * Приближённый круг из прямоугольных полос.
     *
     * Для джойстика и круглых кнопок. Полосы вместо треугольного веера —
     * чтобы не заводить второй формат вершин ради нескольких элементов;
     * при 24 полосах ступенчатость на экране телефона неразличима.
     */
    fun circle(cx: Float, cy: Float, radius: Float, color: Int, segments: Int = 24) {
        val step = radius * 2f / segments
        for (i in 0 until segments) {
            val y = cy - radius + i * step
            // Полухорда круга на данной высоте.
            val dy = (y + step * 0.5f) - cy
            val halfWidth = Math.sqrt((radius * radius - dy * dy).toDouble().coerceAtLeast(0.0))
                .toFloat()
            if (halfWidth <= 0f) continue
            rect(cx - halfWidth, y, halfWidth * 2f, step + 0.5f, color)
        }
    }

    /** Кольцо — контур круга. */
    fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, color: Int, segments: Int = 24) {
        val step = radius * 2f / segments
        for (i in 0 until segments) {
            val y = cy - radius + i * step
            val dy = (y + step * 0.5f) - cy
            val outer = Math.sqrt((radius * radius - dy * dy).toDouble().coerceAtLeast(0.0)).toFloat()
            val innerR = radius - thickness
            val inner = if (Math.abs(dy) < innerR) {
                Math.sqrt((innerR * innerR - dy * dy).toDouble()).toFloat()
            } else {
                0f
            }
            if (outer <= 0f) continue
            if (inner <= 0f) {
                rect(cx - outer, y, outer * 2f, step + 0.5f, color)
            } else {
                rect(cx - outer, y, outer - inner, step + 0.5f, color)
                rect(cx + inner, y, outer - inner, step + 0.5f, color)
            }
        }
    }

    private fun addQuad(
        x: Float, y: Float, w: Float, h: Float,
        layer: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        color: Int
    ) {
        ensureCapacity()

        val a = ((color ushr 24) and 0xFF) / 255f
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        // Два треугольника: (0,1,2) и (0,2,3).
        putVertex(x, y, u0, v0, layer, r, g, b, a)
        putVertex(x, y + h, u0, v1, layer, r, g, b, a)
        putVertex(x + w, y + h, u1, v1, layer, r, g, b, a)

        putVertex(x, y, u0, v0, layer, r, g, b, a)
        putVertex(x + w, y + h, u1, v1, layer, r, g, b, a)
        putVertex(x + w, y, u1, v0, layer, r, g, b, a)
    }

    private fun putVertex(
        x: Float, y: Float,
        u: Float, v: Float, layer: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        var i = vertexCount * FLOATS_PER_VERTEX
        vertexData[i++] = x
        vertexData[i++] = y
        vertexData[i++] = u
        vertexData[i++] = v
        vertexData[i++] = layer
        vertexData[i++] = r
        vertexData[i++] = g
        vertexData[i++] = b
        vertexData[i] = a
        vertexCount++
    }

    private fun ensureCapacity() {
        val needed = (vertexCount + 6) * FLOATS_PER_VERTEX
        if (needed <= vertexData.size) return
        var size = vertexData.size * 2
        while (size < needed) size *= 2
        vertexData = vertexData.copyOf(size)
        buffer = allocate(size)
    }

    /** Выводит накопленный кадр интерфейса одним вызовом отрисовки. */
    fun flush(atlas: TextureArray) {
        if (vertexCount == 0) return

        buffer.clear()
        buffer.put(vertexData, 0, vertexCount * FLOATS_PER_VERTEX)
        buffer.position(0)

        program.use()
        program.setMatrix("uProjection", projection.m)
        program.setInt("uAtlas", 0)
        atlas.bind(0)

        // Интерфейс поверх всего: без проверки глубины и с прозрачностью.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexCount * FLOATS_PER_VERTEX * 4,
            buffer,
            GLES30.GL_STREAM_DRAW
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    fun dispose() {
        if (::program.isInitialized) program.dispose()
        if (vao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
            vao = 0
            vbo = 0
        }
    }

    companion object {
        private const val FLOATS_PER_VERTEX = 9   // x, y, u, v, layer, r, g, b, a
        private const val FLOATS_PER_QUAD = FLOATS_PER_VERTEX * 6
        private const val INITIAL_QUADS = 256

        private fun allocate(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
    }
}
