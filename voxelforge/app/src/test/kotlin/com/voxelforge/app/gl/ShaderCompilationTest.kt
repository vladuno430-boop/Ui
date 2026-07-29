package com.voxelforge.app.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Проверка синтаксиса шейдеров без запуска на устройстве.
 *
 * ## Зачем
 *
 * Ошибка в шейдере не приводит ни к падению, ни к сообщению: OpenGL молча
 * подставляет нулевую программу, и игра показывает чёрный экран. На
 * устройстве пользователя это неотличимо от «приложение не запускается»,
 * а диагностика требует подключения к компьютеру и чтения logcat.
 *
 * Этот тест ловит такие ошибки на этапе сборки. Он вызывает
 * `glslangValidator` — эталонный компилятор GLSL от Khronos, — который
 * разбирает исходники по той же спецификации, что и драйвер устройства.
 *
 * Тест работает потому, что [Shaders] не зависит от Android: это обычный
 * объект Kotlin, собирающий строки. Разделение «исходник шейдера» и
 * «загрузка шейдера в GL» сделано в том числе ради этой проверки.
 *
 * Если валидатор в системе не установлен, тест не падает, а сообщает
 * об этом: обязательная внешняя зависимость сломала бы сборку у всех,
 * кто её не поставил.
 */
class ShaderCompilationTest {

    private val validator: String? by lazy { findValidator() }

    private fun findValidator(): String? {
        for (candidate in listOf("glslangValidator", "glslang")) {
            val ok = runCatching {
                ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }

    /**
     * Компилирует исходник шейдера и возвращает вывод компилятора.
     * Расширение файла сообщает валидатору тип шейдера.
     */
    private fun validate(name: String, source: String, stage: String) {
        val tool = validator
        if (tool == null) {
            println("glslangValidator не найден — проверка шейдера $name пропущена")
            return
        }

        val file = File.createTempFile("voxelforge_$name", ".$stage")
        file.writeText(source)
        try {
            // Без флагов клиента: они включают генерацию SPIR-V, которая
            // требует GLSL ES 310, тогда как OpenGL ES 3.0 работает
            // с версией 300. Нам нужен разбор исходника по спецификации
            // GLSL ES, а не трансляция в промежуточное представление.
            val process = ProcessBuilder(tool, file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                val numbered = source.lineSequence()
                    .mapIndexed { i, line -> "%3d| %s".format(i + 1, line) }
                    .joinToString("\n")
                fail("Шейдер $name не компилируется:\n$output\n\n$numbered")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `вершинный шейдер геометрии компилируется`() {
        validate("chunk", Shaders.CHUNK_VERTEX, "vert")
    }

    @Test
    fun `фрагментный шейдер геометрии компилируется в обоих вариантах`() {
        validate("chunk_opaque", Shaders.chunkFragment(alphaTest = false), "frag")
        validate("chunk_cutout", Shaders.chunkFragment(alphaTest = true), "frag")
    }

    @Test
    fun `шейдеры неба компилируются`() {
        validate("sky", Shaders.SKY_VERTEX, "vert")
        validate("sky", Shaders.SKY_FRAGMENT, "frag")
    }

    @Test
    fun `шейдеры интерфейса компилируются`() {
        validate("ui", Shaders.UI_VERTEX, "vert")
        validate("ui", Shaders.UI_FRAGMENT, "frag")
    }

    @Test
    fun `шейдеры линий компилируются`() {
        validate("line", Shaders.LINE_VERTEX, "vert")
        validate("line", Shaders.LINE_FRAGMENT, "frag")
    }

    @Test
    fun `отбрасывание пикселей включено только в вырезаемом варианте`() {
        // Непрозрачный проход не должен содержать discard: эта инструкция
        // отключает раннюю проверку глубины для всего шейдера, а на
        // мобильных GPU с тайловой архитектурой — ещё и отсечение тайлов.
        assertTrue(
            !Shaders.chunkFragment(alphaTest = false).contains("discard"),
            "В непрозрачном шейдере появился discard — потеряна ранняя проверка глубины"
        )
        assertTrue(
            Shaders.chunkFragment(alphaTest = true).contains("discard"),
            "В вырезаемом шейдере нет discard — листва станет сплошной"
        )
    }

    @Test
    fun `разбор упаковки вершины согласован с форматом`() {
        // Шейдер обязан использовать те же смещения битов, что и упаковщик.
        // Проверка от противного: если кто-то заменит подстановку константы
        // на число, рассинхронизация останется незамеченной до появления
        // геометрии, разъехавшейся на случайные величины.
        val source = Shaders.CHUNK_VERTEX
        for (shift in listOf(
            com.voxelforge.core.mesh.VertexFormat.POS_Y_SHIFT,
            com.voxelforge.core.mesh.VertexFormat.POS_Z_SHIFT,
            com.voxelforge.core.mesh.VertexFormat.FACE_SHIFT,
            com.voxelforge.core.mesh.VertexFormat.AO_SHIFT,
            com.voxelforge.core.mesh.VertexFormat.LAYER_SHIFT,
            com.voxelforge.core.mesh.VertexFormat.SKY_SHIFT,
            com.voxelforge.core.mesh.VertexFormat.BLOCK_LIGHT_SHIFT
        )) {
            assertTrue(
                source.contains(">> ${shift}u"),
                "В шейдере нет сдвига на $shift бит — упаковка и распаковка разошлись"
            )
        }
    }
}
