package com.voxelforge.app.render

import android.opengl.GLES30
import com.voxelforge.app.gl.ShaderProgram
import com.voxelforge.app.gl.Shaders
import com.voxelforge.core.math.Mat4
import com.voxelforge.core.math.MathUtils

/**
 * Цикл дня и ночи и погода.
 *
 * ## Почему это отдельный класс, а не поля рендера
 *
 * Время суток влияет далеко не только на небо: от него зависят яркость
 * солнечного канала освещения, цвет тумана, видимость, поведение частиц
 * дождя. Собранное в одном месте, оно даёт единый источник этих величин
 * и позволяет проверить переходы, прокрутив сутки без запуска графики.
 *
 * ## О солнечном факторе
 *
 * Переход от дня к ночи сделан плавным через сглаживающую кривую, а не
 * линейным. Линейное затухание воспринимается неправильно: восход
 * «размазан» на десятки секунд, а самый заметный момент — пересечение
 * солнцем горизонта — проходит незамеченным. Кривая сжимает сумерки
 * в короткий выразительный отрезок.
 *
 * Ночью фактор не падает до нуля: полная темнота означала бы чёрный экран,
 * в котором невозможно ни идти, ни строить. Нижняя граница соответствует
 * лунному свету.
 */
class DayNightCycle {

    /** Длительность полных суток в секундах реального времени. */
    var dayLengthSeconds: Float = 600f

    /** Текущее время суток в долях: 0 — рассвет, 0.25 — полдень, 0.5 — закат. */
    var timeOfDay: Float = 0.28f
        private set

    /** Накопленное время игры — используется анимациями воды и облаков. */
    var elapsedSeconds: Float = 0f
        private set

    // --- Погода ---

    /** Плотность облаков 0..1. */
    var cloudCoverage: Float = 0.42f
        private set

    /** Сила дождя 0..1; при нуле осадков нет. */
    var rainIntensity: Float = 0f
        private set

    /** Целевые значения погоды — к ним идёт плавный переход. */
    private var targetCloudCoverage: Float = 0.42f
    private var targetRainIntensity: Float = 0f
    private var weatherTimer: Float = 0f

    private val random = java.util.Random()

    fun advance(dt: Float) {
        elapsedSeconds += dt
        timeOfDay = (timeOfDay + dt / dayLengthSeconds) % 1f
        updateWeather(dt)
    }

    /** Устанавливает время суток напрямую — при загрузке сохранения. */
    fun setTimeOfDay(value: Float) {
        timeOfDay = ((value % 1f) + 1f) % 1f
    }

    /**
     * Смена погоды.
     *
     * Переход к новым значениям плавный и занимает секунды: мгновенное
     * появление дождя и облачности выглядит как сбой, а не как погода.
     */
    private fun updateWeather(dt: Float) {
        weatherTimer -= dt
        if (weatherTimer <= 0f) {
            weatherTimer = WEATHER_MIN_DURATION +
                random.nextFloat() * (WEATHER_MAX_DURATION - WEATHER_MIN_DURATION)
            // Дождь — событие нечастое: постоянный ливень утомляет и
            // ухудшает видимость, ради которой и строится дальность прорисовки.
            if (random.nextFloat() < RAIN_PROBABILITY) {
                targetRainIntensity = 0.4f + random.nextFloat() * 0.6f
                targetCloudCoverage = 0.72f + random.nextFloat() * 0.24f
            } else {
                targetRainIntensity = 0f
                targetCloudCoverage = 0.18f + random.nextFloat() * 0.42f
            }
        }
        val rate = MathUtils.clamp(dt * WEATHER_TRANSITION_SPEED, 0f, 1f)
        cloudCoverage += (targetCloudCoverage - cloudCoverage) * rate
        rainIntensity += (targetRainIntensity - rainIntensity) * rate
    }

    /**
     * Яркость солнечного канала освещения, 0.08 (ночь) .. 1 (полдень).
     *
     * Именно это значение уходит в шейдер как `uDayFactor` и умножается
     * на солнечную составляющую света каждой грани. Свет от факелов
     * умножению не подвергается — потому каналы и хранятся раздельно.
     */
    fun sunLightFactor(): Float {
        // Высота солнца над горизонтом: синусоида с максимумом в полдень.
        val elevation = Math.sin(timeOfDay * 2.0 * Math.PI).toFloat()
        // Сжимаем сумерки: переход происходит в узкой полосе вокруг горизонта.
        val t = MathUtils.smoothStep(-0.12f, 0.22f, elevation)
        return NIGHT_LIGHT + (1f - NIGHT_LIGHT) * t
    }

    /** Направление на солнце в мировых координатах. */
    fun sunDirection(out: FloatArray) {
        val angle = timeOfDay * 2.0 * Math.PI
        out[0] = Math.cos(angle).toFloat() * 0.35f
        out[1] = Math.sin(angle).toFloat()
        out[2] = Math.cos(angle).toFloat() * 0.94f
        val len = Math.sqrt((out[0] * out[0] + out[1] * out[1] + out[2] * out[2]).toDouble()).toFloat()
        if (len > 1e-5f) {
            out[0] /= len; out[1] /= len; out[2] /= len
        }
    }

    /**
     * Цвет неба у горизонта. Он же используется как цвет тумана —
     * иначе на границе дальности прорисовки видна цветная кайма,
     * выдающая, где кончается загруженный мир.
     */
    fun horizonColor(out: FloatArray) {
        val elevation = Math.sin(timeOfDay * 2.0 * Math.PI).toFloat()
        // Три опорных состояния: ночь, заря, день.
        val dawn = MathUtils.smoothStep(-0.30f, 0.06f, elevation)
        val day = MathUtils.smoothStep(0.02f, 0.32f, elevation)

        // Ночь → заря → день.
        val nightR = 0.055f; val nightG = 0.070f; val nightB = 0.125f
        val dawnR = 0.92f; val dawnG = 0.48f; val dawnB = 0.28f
        val dayR = 0.62f; val dayG = 0.75f; val dayB = 0.92f

        out[0] = MathUtils.lerp(MathUtils.lerp(nightR, dawnR, dawn), dayR, day)
        out[1] = MathUtils.lerp(MathUtils.lerp(nightG, dawnG, dawn), dayG, day)
        out[2] = MathUtils.lerp(MathUtils.lerp(nightB, dawnB, dawn), dayB, day)

        // Дождь обесцвечивает горизонт и приглушает его.
        if (rainIntensity > 0.01f) {
            val grey = 0.38f * sunLightFactor()
            out[0] = MathUtils.lerp(out[0], grey, rainIntensity * 0.7f)
            out[1] = MathUtils.lerp(out[1], grey, rainIntensity * 0.7f)
            out[2] = MathUtils.lerp(out[2], grey * 1.06f, rainIntensity * 0.7f)
        }
    }

    /** Цвет неба в зените. */
    fun zenithColor(out: FloatArray) {
        val elevation = Math.sin(timeOfDay * 2.0 * Math.PI).toFloat()
        val day = MathUtils.smoothStep(-0.10f, 0.28f, elevation)
        out[0] = MathUtils.lerp(0.020f, 0.24f, day)
        out[1] = MathUtils.lerp(0.030f, 0.46f, day)
        out[2] = MathUtils.lerp(0.075f, 0.82f, day)

        if (rainIntensity > 0.01f) {
            val grey = 0.28f * sunLightFactor()
            out[0] = MathUtils.lerp(out[0], grey, rainIntensity * 0.65f)
            out[1] = MathUtils.lerp(out[1], grey, rainIntensity * 0.65f)
            out[2] = MathUtils.lerp(out[2], grey * 1.08f, rainIntensity * 0.65f)
        }
    }

    /** Цвет светила: белый днём, тёплый на заре, холодный ночью (луна). */
    fun sunColor(out: FloatArray) {
        val elevation = Math.sin(timeOfDay * 2.0 * Math.PI).toFloat()
        if (elevation < -0.05f) {
            // Луна.
            out[0] = 0.68f; out[1] = 0.74f; out[2] = 0.92f
            return
        }
        val high = MathUtils.smoothStep(0.02f, 0.42f, elevation)
        out[0] = MathUtils.lerp(1.0f, 1.0f, high)
        out[1] = MathUtils.lerp(0.62f, 0.97f, high)
        out[2] = MathUtils.lerp(0.34f, 0.90f, high)
    }

    val isNight: Boolean get() = sunLightFactor() < 0.35f

    companion object {
        /** Минимальная освещённость ночью — лунный свет. */
        private const val NIGHT_LIGHT = 0.11f

        private const val WEATHER_MIN_DURATION = 90f
        private const val WEATHER_MAX_DURATION = 260f
        private const val RAIN_PROBABILITY = 0.3f
        private const val WEATHER_TRANSITION_SPEED = 0.09f
    }
}

/**
 * Отрисовка неба, солнца и облаков.
 *
 * ## Небо без геометрии
 *
 * Классический подход — купол из сотен треугольников с текстурой. Здесь
 * вместо него один полноэкранный треугольник: направление луча для каждого
 * пикселя восстанавливается из обратной матрицы «проекция × вид», а цвет
 * считается аналитически. Преимущества существенные:
 *
 *  - ноль вершинных данных и ни одной текстуры неба;
 *  - нет швов и искажений полюсов, неизбежных при натягивании текстуры
 *    на сферу;
 *  - облака получают правильную перспективу автоматически, потому что
 *    считаются как пересечение луча с плоскостью, а не рисуются на куполе.
 *
 * Стоимость — полноэкранный фрагментный шейдер, но она снижается тем,
 * что небо рисуется **после** рельефа с проверкой глубины и выполняется
 * лишь на действительно видимых пикселях неба.
 */
class SkyRenderer {

    private lateinit var program: ShaderProgram
    private var emptyVao = 0

    private val invViewProj = Mat4()
    private val sunDirection = FloatArray(3)
    private val zenith = FloatArray(3)
    private val horizon = FloatArray(3)
    private val sun = FloatArray(3)

    fun initialize() {
        program = ShaderProgram("sky", Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT)
        // Пустой вершинный массив: вершины полностью вычисляются в шейдере
        // из gl_VertexID, но спецификация всё равно требует привязанный VAO.
        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        emptyVao = arrays[0]
    }

    /**
     * Рисует небо в незакрытых рельефом пикселях.
     *
     * @param viewProj матрица, построенная относительно камеры
     * @param cycle    источник времени суток и погоды
     * @param cameraY  высота камеры — от неё зависит расстояние до облаков
     */
    fun render(viewProj: Mat4, cycle: DayNightCycle, cameraY: Double) {
        if (!viewProj.invertTo(invViewProj)) return

        cycle.sunDirection(sunDirection)
        cycle.zenithColor(zenith)
        cycle.horizonColor(horizon)
        cycle.sunColor(sun)

        program.use()
        program.setMatrix("uInvViewProj", invViewProj.m)
        program.setVec3("uZenithColor", zenith[0], zenith[1], zenith[2])
        program.setVec3("uHorizonColor", horizon[0], horizon[1], horizon[2])
        program.setVec3("uSunDirection", sunDirection[0], sunDirection[1], sunDirection[2])
        program.setVec3("uSunColor", sun[0], sun[1], sun[2])
        program.setFloat("uTime", cycle.elapsedSeconds)
        program.setFloat("uCloudCoverage", cycle.cloudCoverage)
        program.setFloat("uCameraY", cameraY.toFloat())
        program.setFloat("uDayFactor", cycle.sunLightFactor())

        // Глубина неба равна дальней плоскости, поэтому сравнение LEQUAL
        // пропускает его только там, где рельефа нет. Запись глубины
        // отключена: небо не должно перекрывать полупрозрачный проход.
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        GLES30.glBindVertexArray(emptyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    fun dispose() {
        if (::program.isInitialized) program.dispose()
        if (emptyVao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(emptyVao), 0)
            emptyVao = 0
        }
    }
}
