package com.voxelforge.app.gl

import android.opengl.GLES30
import android.util.Log

/**
 * Обёртка над шейдерной программой OpenGL ES.
 *
 * Назначение: скомпилировать пару шейдеров, слинковать программу и дать
 * доступ к uniform-переменным по имени с кэшированием их расположения.
 *
 * ## Почему кэширование расположений обязательно
 *
 * `glGetUniformLocation` — это поиск строки в таблице программы на стороне
 * драйвера. Вызванный для десяти переменных в каждом из сотен вызовов
 * отрисовки за кадр, он превращается в тысячи обращений к драйверу и
 * становится заметной статьёй расхода процессорного времени. Расположения
 * не меняются в течение жизни программы, поэтому запрашиваются один раз.
 *
 * ## Об ошибках компиляции
 *
 * Ошибка в шейдере проявляется как чёрный экран без единого сообщения:
 * GL молча использует нулевую программу. Поэтому лог компилятора выводится
 * всегда, а неудача приводит к исключению с текстом ошибки — иначе поиск
 * опечатки в шейдере занимает часы вслепую.
 */
class ShaderProgram(
    private val name: String,
    vertexSource: String,
    fragmentSource: String
) {

    /** Идентификатор программы GL. 0 означает неудачную сборку. */
    val handle: Int

    private val uniformCache = HashMap<String, Int>()

    init {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource, "$name.vert")
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "$name.frag")

        val program = GLES30.glCreateProgram()
        check(program != 0) { "glCreateProgram вернул 0 для программы $name" }

        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Не удалось слинковать программу $name:\n$log")
        }

        // Шейдеры больше не нужны: их содержимое уже в программе.
        GLES30.glDetachShader(program, vertex)
        GLES30.glDetachShader(program, fragment)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)

        handle = program
    }

    private fun compile(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "glCreateShader вернул 0 для $label" }

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            // Нумерованный листинг: сообщения драйвера ссылаются на строки,
            // и без нумерации найти нужную в сотне строк шейдера мучительно.
            val numbered = source.lineSequence()
                .mapIndexed { i, line -> "%3d| %s".format(i + 1, line) }
                .joinToString("\n")
            Log.e(TAG, "Ошибка компиляции $label:\n$log\n$numbered")
            error("Ошибка компиляции $label: $log")
        }
        return shader
    }

    fun use() {
        GLES30.glUseProgram(handle)
    }

    /** Расположение uniform-переменной; −1, если она отсутствует или вырезана. */
    fun uniform(uniformName: String): Int = uniformCache.getOrPut(uniformName) {
        val location = GLES30.glGetUniformLocation(handle, uniformName)
        if (location < 0) {
            // Не ошибка: компилятор GLSL вырезает переменные, не влияющие
            // на результат. Сообщение помогает не искать несуществующую
            // «неработающую» переменную.
            Log.d(TAG, "Uniform '$uniformName' отсутствует в программе $name (вероятно, оптимизирован)")
        }
        location
    }

    fun setFloat(uniformName: String, value: Float) {
        val loc = uniform(uniformName)
        if (loc >= 0) GLES30.glUniform1f(loc, value)
    }

    fun setInt(uniformName: String, value: Int) {
        val loc = uniform(uniformName)
        if (loc >= 0) GLES30.glUniform1i(loc, value)
    }

    fun setVec2(uniformName: String, x: Float, y: Float) {
        val loc = uniform(uniformName)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }

    fun setVec3(uniformName: String, x: Float, y: Float, z: Float) {
        val loc = uniform(uniformName)
        if (loc >= 0) GLES30.glUniform3f(loc, x, y, z)
    }

    fun setVec4(uniformName: String, x: Float, y: Float, z: Float, w: Float) {
        val loc = uniform(uniformName)
        if (loc >= 0) GLES30.glUniform4f(loc, x, y, z, w)
    }

    /** Матрица 4×4. Передаётся без транспонирования: раскладка уже column-major. */
    fun setMatrix(uniformName: String, values: FloatArray) {
        val loc = uniform(uniformName)
        if (loc >= 0) GLES30.glUniformMatrix4fv(loc, 1, false, values, 0)
    }

    fun setVec3Array(uniformName: String, values: FloatArray, count: Int) {
        val loc = uniform(uniformName)
        if (loc >= 0) GLES30.glUniform3fv(loc, count, values, 0)
    }

    fun dispose() {
        if (handle != 0) GLES30.glDeleteProgram(handle)
        uniformCache.clear()
    }

    companion object {
        private const val TAG = "VoxelForge/Shader"
    }
}
