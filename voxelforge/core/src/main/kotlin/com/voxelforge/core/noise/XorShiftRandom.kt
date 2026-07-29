package com.voxelforge.core.noise

/**
 * Быстрый детерминированный генератор псевдослучайных чисел (xorshift128+).
 *
 * Назначение: любая «случайность» в генерации мира — разброс деревьев, руды,
 * травы, формы пещер. Ключевое требование здесь не криптостойкость, а
 * **воспроизводимость**: один и тот же сид обязан давать один и тот же мир
 * на любом устройстве и в любой версии Android.
 *
 * Почему не `java.util.Random`:
 *  1. Он синхронизирован через AtomicLong — а генерация чанков идёт в пуле
 *     потоков, и на каждом вызове получалась бы конкуренция за CAS.
 *  2. Он вдвое медленнее: xorshift128+ — это три сдвига и два XOR.
 *  3. Его младшие биты имеют известные корреляции, из-за чего решётка деревьев
 *     на больших площадях выглядит регулярной.
 *
 * Экземпляр **не потокобезопасен** — это осознанно. Каждый воркер создаёт
 * собственный генератор, засеянный от координат чанка (см. [forChunk]),
 * поэтому разделяемого состояния нет вовсе.
 */
class XorShiftRandom(seed: Long) {

    private var s0: Long
    private var s1: Long

    init {
        // Прогоняем сид через SplitMix64: при последовательных сидах (0, 1, 2…)
        // xorshift без «размешивания» выдаёт коррелированные потоки, и соседние
        // чанки получались бы подозрительно похожими.
        var z = seed
        z = splitMix64(z.let { it + GOLDEN_GAMMA })
        s0 = if (z == 0L) 0x9E3779B97F4A7C15uL.toLong() else z
        z = splitMix64(z + GOLDEN_GAMMA)
        s1 = if (z == 0L) 0xBF58476D1CE4E5B9uL.toLong() else z
    }

    private fun splitMix64(input: Long): Long {
        var z = input
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Следующее 64-битное значение. Период 2^128−1. */
    fun nextLong(): Long {
        var x = s0
        val y = s1
        s0 = y
        x = x xor (x shl 23)
        s1 = x xor y xor (x ushr 17) xor (y ushr 26)
        return s1 + y
    }

    fun nextInt(): Int = (nextLong() ushr 32).toInt()

    /**
     * Целое в диапазоне [0, [bound]). Используется отбраковка вместо остатка:
     * простой `% bound` даёт смещение в пользу малых значений, и, например,
     * деревья заметно чаще прижимались бы к северо-западному углу чанка.
     */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, got $bound" }
        // Степень двойки — можно взять старшие биты напрямую, они самые качественные.
        if (bound and (bound - 1) == 0) {
            return ((bound.toLong() * (nextLong() ushr 33)) ushr 31).toInt()
        }
        val limit = Int.MAX_VALUE - (Int.MAX_VALUE % bound) - 1
        var r: Int
        do {
            r = (nextLong() ushr 33).toInt()
        } while (r > limit)
        return r % bound
    }

    /** Целое в диапазоне [min, max] включительно. */
    fun nextIntRange(min: Int, max: Int): Int =
        if (max <= min) min else min + nextInt(max - min + 1)

    /** Число с плавающей точкой в [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() * FLOAT_UNIT

    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * DOUBLE_UNIT

    /** Число в диапазоне [min, max). */
    fun nextFloatRange(min: Float, max: Float): Float = min + nextFloat() * (max - min)

    /** Событие с вероятностью [probability] (0..1). */
    fun chance(probability: Float): Boolean = nextFloat() < probability

    /** Случайный элемент массива — для выбора вариации блока или типа дерева. */
    fun <T> pick(items: Array<T>): T = items[nextInt(items.size)]

    companion object {
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        private const val FLOAT_UNIT = 1f / (1 shl 24)
        private const val DOUBLE_UNIT = 1.0 / (1L shl 53)

        /**
         * Создаёт генератор, детерминированно привязанный к координатам чанка.
         *
         * Это принципиальный момент архитектуры генерации: декоратор чанка
         * (деревья, кактусы, руда) обязан выдавать одинаковый результат независимо
         * от того, в каком порядке потоки добрались до чанков. Привязка к
         * координатам вместо общего счётчика гарантирует это без блокировок.
         *
         * Множители — крупные простые числа, чтобы (x, z) и (z, x) давали
         * разные потоки и мир не получал зеркальную симметрию по диагонали.
         */
        fun forChunk(worldSeed: Long, chunkX: Int, chunkZ: Int, salt: Long = 0L): XorShiftRandom {
            val h = worldSeed +
                chunkX.toLong() * 341_873_128_712L +
                chunkZ.toLong() * 132_897_987_541L +
                salt * 2_147_483_647L
            return XorShiftRandom(h)
        }
    }
}
