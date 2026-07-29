package com.voxelforge.core.gen

import com.voxelforge.core.math.MathUtils
import com.voxelforge.core.noise.FractalNoise
import com.voxelforge.core.world.WorldConstants.MAX_Y
import com.voxelforge.core.world.WorldConstants.SEA_LEVEL
import kotlin.math.abs

/**
 * Результат выборки одной колонки мира. Изменяемый объект переиспользуется
 * генератором для всех 256 колонок чанка, чтобы не плодить мусор.
 */
class ColumnSample {
    /** Высота поверхности в блоках (индекс верхнего блока рельефа). */
    @JvmField var height: Int = SEA_LEVEL

    /** Идентификатор биома, см. [Biomes]. */
    @JvmField var biome: Int = Biomes.PLAINS

    /** Насколько колонка — суша: 0 в открытом океане, 1 вглубь материка. */
    @JvmField var land: Float = 1f

    /** Насколько колонка — горы: управляет и рельефом, и выбором биома. */
    @JvmField var mountain: Float = 0f

    /** Близость к оси реки: 1 в русле, 0 вне долины. */
    @JvmField var river: Float = 0f

    /** Климатические параметры — нужны декоратору для снега и сухости. */
    @JvmField var temperature: Float = 0f
    @JvmField var humidity: Float = 0f
}

/**
 * Источник формы рельефа и биомов.
 *
 * Назначение: по мировым координатам (x, z) выдать высоту поверхности,
 * биом и климатические параметры. Это сердце процедурной генерации.
 *
 * **Главное архитектурное решение здесь — высота не зависит от дискретного
 * биома.** Наивная схема «выбрали биом → взяли его baseHeight» неизбежно даёт
 * вертикальный обрыв на границе биомов: соседние колонки получают разные
 * дискретные значения, и рельеф прыгает на десятки блоков. Обычное лекарство —
 * усреднение параметров по окрестности — стоит пятикратной выборки шума
 * на каждую колонку.
 *
 * Здесь и высота, и биом выводятся из одних и тех же **непрерывных** полей
 * шума. Высота получается гладкой по построению, смешивание не нужно вовсе,
 * а биом остаётся лишь «раскраской» поверхности и флоры. Это и быстрее
 * впятеро, и принципиально не может дать шва.
 *
 * Класс потокобезопасен на чтение: все поля шума неизменяемы после
 * конструктора, а [ColumnSample] передаётся вызывающим кодом (у каждого
 * воркера свой).
 */
class TerrainSampler(seed: Long) {

    // Каждое поле получает собственный сид: одинаковые сиды дали бы
    // коррелированные карты, и, например, пустыни всегда оказывались бы
    // ровно на гребнях гор.
    private val continentNoise = FractalNoise(seed + 1, octaves = 4, persistence = 0.5f)
    private val peakNoise = FractalNoise(seed + 2, octaves = 4, persistence = 0.55f)
    private val detailNoise = FractalNoise(seed + 3, octaves = 4, persistence = 0.5f)
    private val ridgeNoise = FractalNoise(seed + 4, octaves = 5, persistence = 0.55f)
    private val temperatureNoise = FractalNoise(seed + 5, octaves = 3, persistence = 0.5f)
    private val humidityNoise = FractalNoise(seed + 6, octaves = 3, persistence = 0.5f)
    private val riverNoise = FractalNoise(seed + 7, octaves = 2, persistence = 0.5f)
    private val duneNoise = FractalNoise(seed + 8, octaves = 3, persistence = 0.5f)

    /**
     * Заполняет [out] данными колонки.
     *
     * Порядок вычислений важен: сначала непрерывные поля, затем высота,
     * и только в самом конце — дискретный биом, который уже знает итоговую
     * высоту и потому может отличить пляж от равнины.
     */
    fun sample(worldX: Int, worldZ: Int, out: ColumnSample) {
        val x = worldX.toFloat()
        val z = worldZ.toFloat()

        // --- Непрерывные климатические и тектонические поля ---

        // Доменное искажение даёт материкам изрезанные, «географические»
        // очертания вместо круглых пятен.
        val continent = continentNoise.warped2D(x, z, FREQ_CONTINENT, WARP_STRENGTH)
        val peaks = peakNoise.fbm2D(x, z, FREQ_PEAKS)
        val detail = detailNoise.fbm2D(x, z, FREQ_DETAIL)
        val temperature = temperatureNoise.fbm2D(x, z, FREQ_TEMPERATURE)
        val humidity = humidityNoise.fbm2D(x, z, FREQ_HUMIDITY)

        // Доля суши: плавный переход от океанского дна к материку.
        val land = MathUtils.smoothStep(-0.30f, 0.12f, continent)

        // Горный множитель. Умножение на land не даёт хребтам вырастать
        // посреди океана, где им неоткуда взяться.
        val mountain = MathUtils.smoothStep(0.12f, 0.62f, peaks) * land

        // --- Высота рельефа ---

        // Дно океана и базовый уровень суши, между ними плавный переход.
        val oceanFloor = 36f + detail * 5f
        val landBase = SEA_LEVEL + 5f + continent * 9f
        var height = MathUtils.lerp(oceanFloor, landBase, land)

        // Мелкие неровности. На суше они выражены сильнее, чем под водой.
        height += detail * (3f + 6f * land)

        // Горы: ridged-шум даёт острые гребни, а не округлые холмы.
        if (mountain > 0.001f) {
            val ridge = ridgeNoise.ridged2D(x, z, FREQ_RIDGE)
            height += mountain * (16f + ridge * 40f)
        }

        // Дюны. «Сухость» — непрерывная величина (жарко И сухо), поэтому
        // дюны появляются постепенно и не создают шва на границе пустыни.
        val aridity = MathUtils.clamp((temperature - 0.10f) * 2.2f, 0f, 1f) *
            MathUtils.clamp((-humidity - 0.02f) * 2.5f, 0f, 1f) * land
        if (aridity > 0.001f) {
            height += aridity * duneNoise.billow2D(x, z, FREQ_DUNE) * 5f
        }

        // --- Реки ---
        //
        // Русло — это множество точек, где шум близок к нулю. Такой приём
        // даёт непрерывные извилистые линии произвольной длины, чего нельзя
        // добиться пороговой фильтрацией «шум > t» (она даёт пятна, а не линии).
        val riverRaw = abs(riverNoise.fbm2D(x, z, FREQ_RIVER))
        var river = 1f - MathUtils.smoothStep(0f, RIVER_WIDTH, riverRaw)
        // В океане река не нужна, а в горах она превратилась бы в неестественный
        // каньон поперёк хребта, поэтому её влияние там гасится.
        river *= land * (1f - mountain * 0.92f)

        if (river > 0.002f) {
            // Долина: рельеф притягивается к уровню чуть ниже моря,
            // но только вниз — река не может «поднять» местность.
            val bed = SEA_LEVEL - 3f - river * 3f
            height = MathUtils.lerp(height, kotlin.math.min(height, bed), river)
        }

        val finalHeight = MathUtils.clamp(height, 4f, (MAX_Y - 12).toFloat()).toInt()

        out.height = finalHeight
        out.land = land
        out.mountain = mountain
        out.river = river
        out.temperature = temperature
        out.humidity = humidity
        out.biome = selectBiome(finalHeight, land, mountain, river, temperature, humidity)
    }

    /**
     * Выбор биома по уже вычисленной высоте и непрерывным полям.
     *
     * Порядок проверок — это приоритет: вода перекрывает климат (в пустыне
     * посреди озера должен быть океан, а не песок), а климат перекрывает
     * растительность. Каждый порог сопоставлен с тем, как выглядит переход,
     * а не выведен формально.
     */
    private fun selectBiome(
        height: Int,
        land: Float,
        mountain: Float,
        river: Float,
        temperature: Float,
        humidity: Float
    ): Int {
        // Глубокая вода вне русла реки — океан.
        if (height < SEA_LEVEL - 4 && land < 0.55f) return Biomes.OCEAN

        // Русло реки: под водой и близко к оси.
        if (river > 0.30f && height <= SEA_LEVEL) return Biomes.RIVER

        // Узкая полоса у самой воды — пляж. Без него трава обрывалась бы
        // в море вертикальной стенкой.
        if (height <= SEA_LEVEL + 2 && height >= SEA_LEVEL - 3) {
            // На холоде вместо песчаного пляжа логичнее промёрзший берег.
            return if (temperature < FREEZE_TEMPERATURE) Biomes.SNOW else Biomes.BEACH
        }

        // Горы определяются рельефом, а не климатом: скалы есть и в тайге,
        // и в жарком поясе.
        if (mountain > 0.42f || height > SEA_LEVEL + 34) return Biomes.MOUNTAINS

        if (temperature < FREEZE_TEMPERATURE) return Biomes.SNOW
        if (temperature > 0.24f && humidity < -0.04f) return Biomes.DESERT
        if (humidity > 0.02f) return Biomes.FOREST
        return Biomes.PLAINS
    }

    /**
     * Быстрая выборка только высоты — без биома и климата.
     * Нужна там, где важна лишь геометрия: предварительный расчёт положения
     * спавна и проверка, не окажется ли структура в воздухе.
     */
    fun sampleHeightOnly(worldX: Int, worldZ: Int, scratch: ColumnSample): Int {
        sample(worldX, worldZ, scratch)
        return scratch.height
    }

    companion object {
        // Частоты подобраны так, чтобы материк занимал порядка 1500 блоков,
        // горный массив — 400, а мелкий рельеф менялся каждые 80 блоков.
        private const val FREQ_CONTINENT = 0.0011f
        private const val FREQ_PEAKS = 0.0026f
        private const val FREQ_DETAIL = 0.013f
        private const val FREQ_RIDGE = 0.0075f
        private const val FREQ_TEMPERATURE = 0.0016f
        private const val FREQ_HUMIDITY = 0.0021f
        private const val FREQ_RIVER = 0.0015f
        private const val FREQ_DUNE = 0.021f

        /** Амплитуда доменного искажения материков в блоках. */
        private const val WARP_STRENGTH = 190f

        /** Полуширина речной долины в единицах шума. */
        private const val RIVER_WIDTH = 0.055f

        /** Ниже этой температуры вода мёрзнет, а осадки идут снегом. */
        const val FREEZE_TEMPERATURE = -0.30f
    }
}
