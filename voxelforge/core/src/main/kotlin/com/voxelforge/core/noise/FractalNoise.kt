package com.voxelforge.core.noise

import kotlin.math.abs

/**
 * Фрактальное суммирование октав симплекс-шума (fBm и его варианты).
 *
 * Назначение: один вызов [SimplexNoise] даёт «мыльный» однородный рельеф.
 * Природный ландшафт самоподобен: крупные горные массивы, на них холмы,
 * на холмах — мелкие неровности. Фрактальный шум воспроизводит это,
 * складывая несколько октав с удвоением частоты и уменьшением амплитуды.
 *
 * Класс держит **свой** экземпляр [SimplexNoise] на каждую октаву со сдвинутым
 * сидом. Альтернатива — брать один шум и просто масштабировать координаты —
 * приводит к тому, что октавы совпадают в нулях решётки, и по миру идут
 * заметные «складки» правильной формы. Отдельные перестановки это исключают.
 *
 * Потокобезопасен на чтение — состояние неизменно после конструктора.
 *
 * @param seed        сид мира; каждая октава получает производный сид
 * @param octaves     число октав; каждая следующая вдвое дороже по кэш-промахам,
 *                    поэтому для рельефа берём 4–6, для второстепенных карт 2–3
 * @param persistence во сколько раз падает амплитуда каждой октавы (обычно 0.5)
 * @param lacunarity  во сколько раз растёт частота каждой октавы (обычно 2.0)
 */
class FractalNoise(
    seed: Long,
    private val octaves: Int,
    private val persistence: Float = 0.5f,
    private val lacunarity: Float = 2.0f
) {

    init {
        require(octaves in 1..12) { "octaves must be in 1..12, got $octaves" }
    }

    private val layers: Array<SimplexNoise> =
        Array(octaves) { SimplexNoise(seed + it * 0x9E3779B97F4A7C15uL.toLong()) }

    /**
     * Нормировочный делитель — сумма амплитуд всех октав. Без него результат
     * зависел бы от числа октав, и смена настройки детализации меняла бы
     * высоту всего рельефа.
     */
    private val normalization: Float = run {
        var sum = 0f
        var amp = 1f
        repeat(octaves) { sum += amp; amp *= persistence }
        if (sum == 0f) 1f else 1f / sum
    }

    /** Классический fBm в диапазоне примерно [-1, 1]. Основа высоты рельефа. */
    fun fbm2D(x: Float, y: Float, frequency: Float): Float {
        var sum = 0f
        var amp = 1f
        var freq = frequency
        for (i in 0 until octaves) {
            sum += layers[i].noise2D(x * freq, y * freq) * amp
            amp *= persistence
            freq *= lacunarity
        }
        return sum * normalization
    }

    fun fbm3D(x: Float, y: Float, z: Float, frequency: Float): Float {
        var sum = 0f
        var amp = 1f
        var freq = frequency
        for (i in 0 until octaves) {
            sum += layers[i].noise3D(x * freq, y * freq, z * freq) * amp
            amp *= persistence
            freq *= lacunarity
        }
        return sum * normalization
    }

    /** fBm, приведённый к [0, 1] — для карт температуры, влажности, плотности. */
    fun fbm2D01(x: Float, y: Float, frequency: Float): Float =
        fbm2D(x, y, frequency) * 0.5f + 0.5f

    fun fbm3D01(x: Float, y: Float, z: Float, frequency: Float): Float =
        fbm3D(x, y, z, frequency) * 0.5f + 0.5f

    /**
     * Ridged-шум: берём модуль шума и инвертируем.
     *
     * Даёт резкие гребни вместо плавных холмов — именно так выглядят
     * горные хребты. Используется как добавка в горном биоме: чистый fBm
     * там даёт округлые «булки», а не скалы.
     *
     * Каждая октава дополнительно взвешивается предыдущей: это подавляет
     * мелкие гребни в низинах и оставляет их только на склонах, что заметно
     * ближе к настоящей эрозии.
     */
    fun ridged2D(x: Float, y: Float, frequency: Float): Float {
        var sum = 0f
        var amp = 1f
        var freq = frequency
        var weight = 1f
        for (i in 0 until octaves) {
            var signal = 1f - abs(layers[i].noise2D(x * freq, y * freq))
            signal *= signal          // заострение гребня
            signal *= weight          // подавление деталей в низинах
            weight = (signal * 2f).coerceIn(0f, 1f)
            sum += signal * amp
            amp *= persistence
            freq *= lacunarity
        }
        return sum * normalization
    }

    /**
     * Billow-шум: модуль fBm, растянутый обратно в [-1, 1].
     * Даёт «клубящуюся» форму — применяется для дюн в пустыне и для облаков.
     */
    fun billow2D(x: Float, y: Float, frequency: Float): Float {
        var sum = 0f
        var amp = 1f
        var freq = frequency
        for (i in 0 until octaves) {
            sum += (abs(layers[i].noise2D(x * freq, y * freq)) * 2f - 1f) * amp
            amp *= persistence
            freq *= lacunarity
        }
        return sum * normalization
    }

    /**
     * Доменное искажение: перед выборкой шума сдвигаем координаты другим шумом.
     *
     * Это приём, который сильнее всего влияет на «естественность» карты:
     * прямой fBm даёт изотропные пятна, а искажённый — вытянутые, изогнутые
     * границы биомов и извилистые берега, похожие на настоящую географию.
     *
     * @param strength амплитуда сдвига в единицах мировых координат
     */
    fun warped2D(x: Float, y: Float, frequency: Float, strength: Float): Float {
        val wx = layers[0].noise2D(x * frequency * 0.5f + 137.2f, y * frequency * 0.5f + 311.7f)
        val wy = layers[octaves - 1].noise2D(x * frequency * 0.5f - 271.9f, y * frequency * 0.5f + 83.4f)
        return fbm2D(x + wx * strength, y + wy * strength, frequency)
    }
}
