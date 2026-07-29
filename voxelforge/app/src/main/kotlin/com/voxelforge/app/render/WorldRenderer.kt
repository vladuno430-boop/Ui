package com.voxelforge.app.render

import android.opengl.GLES30
import com.voxelforge.app.gl.ShaderProgram
import com.voxelforge.app.gl.Shaders
import com.voxelforge.app.gl.TextureArray
import com.voxelforge.core.gen.Biomes
import com.voxelforge.core.math.Frustum
import com.voxelforge.core.math.Mat4
import com.voxelforge.core.mesh.SectionMesh
import com.voxelforge.core.world.MeshConsumer
import com.voxelforge.core.world.SectionPos

/**
 * Отрисовка геометрии мира.
 *
 * ## Порядок проходов
 *
 * Порядок не произволен, каждый шаг обусловлен работой GPU:
 *
 *  1. **Непрозрачный** — первым, с записью глубины и без отбрасывания
 *     пикселей. Так работает ранняя проверка глубины: закрытые фрагменты
 *     отбрасываются до фрагментного шейдера. На мобильных GPU с отложенным
 *     тайловым рендерингом это к тому же позволяет отсечь целые тайлы.
 *
 *  2. **Вырезаемый** (листва, трава) — вторым. Он использует `discard`,
 *     из-за чего ранняя проверка глубины для него отключена, но к этому
 *     моменту буфер глубины уже заполнен непрозрачной геометрией, и
 *     большинство скрытых фрагментов отсекается обычной проверкой.
 *
 *  3. **Небо** — третьим, по уже заполненному буферу глубины. Полноэкранный
 *     шейдер неба с процедурными облаками недёшев, и запускать его на
 *     пикселях, которые закроет рельеф, — чистая потеря. Сравнение
 *     глубины оставляет от него только видимую часть неба.
 *
 *  4. **Полупрозрачный** (вода, стекло) — последним, со смешиванием
 *     и **отключённой записью глубины**. Запись пришлось бы отключить
 *     в любом случае: иначе ближнее стекло скрывало бы дальнее.
 *     Секции сортируются от дальних к ближним, иначе смешивание даёт
 *     неверный результат.
 *
 * ## Отсечение
 *
 * Каждая секция проверяется на пересечение с пирамидой видимости. При
 * дальности прорисовки 8 в мире около 1200 непустых секций, а в кадр
 * попадает примерно четверть — отсечение убирает три четверти вызовов
 * отрисовки ценой шести скалярных произведений на секцию.
 */
class WorldRenderer : MeshConsumer {

    private lateinit var opaqueProgram: ShaderProgram
    private lateinit var cutoutProgram: ShaderProgram
    private lateinit var atlas: TextureArray

    /** Буферы GPU по позиции секции. */
    private val sections = HashMap<Long, SectionBuffers>()

    /** Список для сортировки полупрозрачных секций. */
    private val translucentOrder = ArrayList<SectionBuffers>()

    private val frustum = Frustum()

    /**
     * Цвета тонирования по биомам. Передаются массивом uniform, а индекс
     * биома лежит в вершине: благодаря этому трава в лесу и в пустыне имеет
     * разный оттенок, но рисуется одним вызовом.
     */
    private val biomeTints = FloatArray(Biomes.COUNT * 3)

    // Статистика кадра для отладочной панели.
    var visibleSections = 0
        private set
    var culledSections = 0
        private set
    var drawnTriangles = 0
        private set

    /** Инициализация ресурсов GL. Вызывается на потоке отрисовки. */
    fun initialize(anisotropy: Float) {
        opaqueProgram = ShaderProgram(
            "chunk-opaque", Shaders.CHUNK_VERTEX, Shaders.chunkFragment(alphaTest = false)
        )
        cutoutProgram = ShaderProgram(
            "chunk-cutout", Shaders.CHUNK_VERTEX, Shaders.chunkFragment(alphaTest = true)
        )
        atlas = TextureArray.createBlockAtlas(anisotropy)

        for (biome in Biomes.ALL) {
            val i = biome.id * 3
            // Для травы и листвы используется общий оттенок биома: раздельные
            // цвета дали бы более точную картинку, но потребовали бы второго
            // индекса в вершине, а свободных битов там нет.
            val tint = biome.grassTint
            biomeTints[i] = ((tint shr 16) and 0xFF) / 255f
            biomeTints[i + 1] = ((tint shr 8) and 0xFF) / 255f
            biomeTints[i + 2] = (tint and 0xFF) / 255f
        }
    }

    // ------------------------------------------------------------------
    // Приём мешей из конвейера
    // ------------------------------------------------------------------

    override fun onMeshReady(mesh: SectionMesh) {
        val key = SectionPos.pack(mesh.chunkX, mesh.sectionY, mesh.chunkZ)
        val buffers = sections.getOrPut(key) {
            SectionBuffers(mesh.chunkX, mesh.sectionY, mesh.chunkZ)
        }
        buffers.opaque.upload(mesh.opaque)
        buffers.cutout.upload(mesh.cutout)
        buffers.translucent.upload(mesh.translucent)

        // Секция могла опустеть (игрок выкопал всё) — держать пустые
        // буферы в видеопамяти незачем.
        if (buffers.isEmpty) {
            buffers.dispose()
            sections.remove(key)
        }
    }

    override fun onSectionRemoved(chunkX: Int, sectionY: Int, chunkZ: Int) {
        val key = SectionPos.pack(chunkX, sectionY, chunkZ)
        sections.remove(key)?.dispose()
    }

    // ------------------------------------------------------------------
    // Отрисовка
    // ------------------------------------------------------------------

    /**
     * Рисует непрозрачную и вырезаемую геометрию.
     *
     * @param viewProj   матрица «проекция × вид», построенная относительно камеры
     * @param cameraX/Y/Z позиция камеры в мировых координатах
     * @param dayFactor  яркость солнечного света, 0 (ночь) .. 1 (полдень)
     * @param fogColor   цвет тумана; совпадает с цветом неба у горизонта,
     *                   иначе на границе прорисовки видна цветная кайма
     * @param fogStart/fogEnd границы тумана в блоках
     */
    fun renderSolid(
        viewProj: Mat4,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        dayFactor: Float,
        time: Float,
        fogColor: FloatArray,
        fogStart: Float,
        fogEnd: Float
    ) {
        frustum.setFromMatrix(viewProj)
        visibleSections = 0
        culledSections = 0
        drawnTriangles = 0
        translucentOrder.clear()

        atlas.bind(0)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glDisable(GLES30.GL_BLEND)

        // --- Проход 1: непрозрачная геометрия ---
        opaqueProgram.use()
        setCommonUniforms(opaqueProgram, viewProj, dayFactor, time, fogColor, fogStart, fogEnd)

        val originLoc = opaqueProgram.uniform("uSectionOrigin")
        for (buffers in sections.values) {
            if (!isVisible(buffers, cameraX, cameraY, cameraZ)) {
                culledSections++
                continue
            }
            visibleSections++
            if (buffers.translucent.indexCount > 0) translucentOrder.add(buffers)
            if (buffers.opaque.isEmpty) continue

            setSectionOrigin(originLoc, buffers, cameraX, cameraY, cameraZ)
            buffers.opaque.draw()
            drawnTriangles += buffers.opaque.indexCount / 3
        }

        // --- Проход 2: вырезаемая геометрия ---
        cutoutProgram.use()
        setCommonUniforms(cutoutProgram, viewProj, dayFactor, time, fogColor, fogStart, fogEnd)
        // Растительность и листва видны с обеих сторон: «крестики» состоят
        // из плоскостей, у которых нет внутренней стороны.
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        val cutoutOriginLoc = cutoutProgram.uniform("uSectionOrigin")
        for (buffers in sections.values) {
            if (buffers.cutout.isEmpty) continue
            if (!isVisible(buffers, cameraX, cameraY, cameraZ)) continue
            setSectionOrigin(cutoutOriginLoc, buffers, cameraX, cameraY, cameraZ)
            buffers.cutout.draw()
            drawnTriangles += buffers.cutout.indexCount / 3
        }
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    /**
     * Рисует полупрозрачную геометрию. Вызывается после неба.
     *
     * Сортировка по убыванию расстояния обязательна: смешивание не
     * коммутативно, и при обратном порядке дальняя вода, нарисованная
     * поверх ближней, даёт заметно неверный цвет на стыках.
     */
    fun renderTranslucent(
        viewProj: Mat4,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        dayFactor: Float,
        time: Float,
        fogColor: FloatArray,
        fogStart: Float,
        fogEnd: Float
    ) {
        if (translucentOrder.isEmpty()) return

        translucentOrder.sortByDescending { buffers ->
            val dx = buffers.originX + 8 - cameraX
            val dy = buffers.originY + 8 - cameraY
            val dz = buffers.originZ + 8 - cameraZ
            dx * dx + dy * dy + dz * dz
        }

        cutoutProgram.use()
        setCommonUniforms(cutoutProgram, viewProj, dayFactor, time, fogColor, fogStart, fogEnd)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Запись глубины отключена: иначе ближнее стекло скрыло бы дальнее,
        // и стеклянная постройка выглядела бы как одна плоскость.
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        val originLoc = cutoutProgram.uniform("uSectionOrigin")
        for (buffers in translucentOrder) {
            setSectionOrigin(originLoc, buffers, cameraX, cameraY, cameraZ)
            buffers.translucent.draw()
            drawnTriangles += buffers.translucent.indexCount / 3
        }

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(0)
    }

    private fun setCommonUniforms(
        program: ShaderProgram,
        viewProj: Mat4,
        dayFactor: Float,
        time: Float,
        fogColor: FloatArray,
        fogStart: Float,
        fogEnd: Float
    ) {
        program.setMatrix("uViewProj", viewProj.m)
        program.setInt("uAtlas", 0)
        program.setFloat("uDayFactor", dayFactor)
        program.setFloat("uTime", time)
        program.setVec3("uFogColor", fogColor[0], fogColor[1], fogColor[2])
        program.setFloat("uFogStart", fogStart)
        program.setFloat("uFogEnd", fogEnd)
        program.setVec3Array("uBiomeTint", biomeTints, Biomes.COUNT)
    }

    /**
     * Передаёт смещение секции **относительно камеры**.
     *
     * Именно здесь двойная точность мировых координат сводится к float
     * без потери качества: вычитание выполняется в Double, а в шейдер
     * попадает уже небольшая разность. Если бы позиции передавались
     * абсолютными, на удалении в миллион блоков float32 дал бы шаг сетки
     * около 0,06 блока, и геометрия видимо дрожала бы при движении камеры.
     */
    private fun setSectionOrigin(
        location: Int,
        buffers: SectionBuffers,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double
    ) {
        if (location < 0) return
        GLES30.glUniform3f(
            location,
            (buffers.originX - cameraX).toFloat(),
            (buffers.originY - cameraY).toFloat(),
            (buffers.originZ - cameraZ).toFloat()
        )
    }

    private fun isVisible(
        buffers: SectionBuffers,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double
    ): Boolean {
        val minX = (buffers.originX - cameraX).toFloat()
        val minY = (buffers.originY - cameraY).toFloat()
        val minZ = (buffers.originZ - cameraZ).toFloat()
        return frustum.isBoxVisible(
            minX, minY, minZ,
            minX + 16f, minY + 16f, minZ + 16f,
            // Запас в один блок: поверхность воды колеблется в шейдере
            // и может выйти за геометрические границы секции.
            margin = 1.5f
        )
    }

    /** Освобождает всю геометрию — при смене мира. */
    fun clearAllSections() {
        for (buffers in sections.values) buffers.dispose()
        sections.clear()
        translucentOrder.clear()
    }

    fun dispose() {
        clearAllSections()
        if (::opaqueProgram.isInitialized) opaqueProgram.dispose()
        if (::cutoutProgram.isInitialized) cutoutProgram.dispose()
        if (::atlas.isInitialized) atlas.dispose()
    }

    /** Расход видеопамяти под геометрию, в мегабайтах. */
    fun videoMemoryMb(): Float {
        var total = 0L
        for (buffers in sections.values) total += buffers.memoryUsage()
        return total / (1024f * 1024f)
    }

    val sectionCount: Int get() = sections.size

    /** Текстурный массив — нужен интерфейсу для отрисовки иконок блоков. */
    fun atlasTexture(): TextureArray = atlas
}
