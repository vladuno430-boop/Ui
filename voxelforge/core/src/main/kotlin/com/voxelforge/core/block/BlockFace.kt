package com.voxelforge.core.block

/**
 * Шесть граней кубического блока.
 *
 * Назначение: единая нумерация направлений для мешера, освещения и выбора
 * текстуры. Порядок констант зафиксирован и используется как индекс в массивах
 * (текстуры граней, смещения соседей, нормали в шейдере), поэтому менять его
 * нельзя без правки мешера и формата сохранений.
 *
 * Система координат правая: +X на восток, +Y вверх, +Z на юг.
 */
enum class BlockFace(
    /** Индекс 0..5 — дублирует ordinal, но делает намерение явным в горячем коде. */
    @JvmField val index: Int,
    @JvmField val dx: Int,
    @JvmField val dy: Int,
    @JvmField val dz: Int
) {
    UP(0, 0, 1, 0),
    DOWN(1, 0, -1, 0),
    NORTH(2, 0, 0, -1),
    SOUTH(3, 0, 0, 1),
    WEST(4, -1, 0, 0),
    EAST(5, 1, 0, 0);

    /** Противоположная грань — нужна при распространении света между чанками. */
    val opposite: BlockFace
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            NORTH -> SOUTH
            SOUTH -> NORTH
            WEST -> EAST
            EAST -> WEST
        }

    /** Горизонтальные грани не участвуют в вертикальном спуске солнечного света. */
    val isHorizontal: Boolean get() = dy == 0

    companion object {
        /** Кэш значений: `values()` создаёт копию массива при каждом вызове. */
        @JvmField
        val VALUES: Array<BlockFace> = entries.toTypedArray()

        /** Только горизонтальные грани — обход соседних чанков по кольцу. */
        @JvmField
        val HORIZONTAL: Array<BlockFace> = arrayOf(NORTH, SOUTH, WEST, EAST)

        fun byIndex(i: Int): BlockFace = VALUES[i]
    }
}
