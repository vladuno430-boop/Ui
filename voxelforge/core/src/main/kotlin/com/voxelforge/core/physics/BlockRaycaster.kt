package com.voxelforge.core.physics

import com.voxelforge.core.block.BlockFace
import com.voxelforge.core.block.Blocks
import com.voxelforge.core.math.MathUtils
import com.voxelforge.core.world.BlockAccess

/**
 * Результат трассировки луча по вокселям.
 * Изменяемый и переиспользуемый: трассировка выполняется каждый кадр
 * для подсветки блока под прицелом.
 */
class RaycastHit {
    /** Попал ли луч в блок. */
    @JvmField var hit: Boolean = false

    /** Координаты найденного блока. */
    @JvmField var blockX: Int = 0
    @JvmField var blockY: Int = 0
    @JvmField var blockZ: Int = 0

    /** Идентификатор блока. */
    @JvmField var blockId: Int = Blocks.AIR

    /** Грань, через которую луч вошёл в блок — по ней ставится новый блок. */
    @JvmField var face: BlockFace = BlockFace.UP

    /** Расстояние от начала луча до точки входа. */
    @JvmField var distance: Float = 0f

    /** Координаты соседней клетки со стороны [face] — место установки блока. */
    val placeX: Int get() = blockX + face.dx
    val placeY: Int get() = blockY + face.dy
    val placeZ: Int get() = blockZ + face.dz

    fun clear() {
        hit = false
        blockId = Blocks.AIR
    }
}

/**
 * Трассировка луча по воксельной сетке алгоритмом Амануатидеса–Ву (3D-DDA).
 *
 * ## Зачем именно этот алгоритм
 *
 * Очевидное решение — шагать по лучу мелкими шагами и проверять блок
 * в каждой точке — имеет два неустранимых недостатка. При крупном шаге луч
 * перепрыгивает тонкие блоки (стекло в стене остаётся невыделяемым), а при
 * мелком приходится делать сотни проверок на каждый кадр, причём **точность
 * всё равно остаётся приблизительной**: невозможно надёжно определить,
 * через какую именно грань луч вошёл в блок, а без этого некуда ставить
 * новый блок.
 *
 * DDA обходит ровно те клетки, которые луч действительно пересекает, —
 * ни одной лишней и ни одной пропущенной. За один шаг он продвигается
 * к ближайшей границе клетки по одной из трёх осей, и **та ось, по которой
 * был сделан шаг, сразу даёт грань входа**. Число шагов равно числу
 * пересечённых клеток, то есть примерно длине луча в блоках: для дистанции
 * взаимодействия в 6 блоков это около 10 итераций вместо сотен.
 *
 * Класс не хранит состояния и потокобезопасен при своём [RaycastHit]
 * у каждого вызывающего.
 */
object BlockRaycaster {

    /**
     * Пускает луч и находит первый блок, удовлетворяющий условию.
     *
     * @param world       источник блоков
     * @param originX/Y/Z начало луча (обычно глаз игрока)
     * @param dirX/Y/Z    направление, не обязано быть нормированным
     * @param maxDistance предельная дальность в блоках
     * @param out         результат
     * @param includeLiquids учитывать ли жидкости; при выборе блока — нет
     *                       (иначе под водой невозможно ничего сломать),
     *                       но для определения точки касания воды — да
     */
    fun cast(
        world: BlockAccess,
        originX: Double,
        originY: Double,
        originZ: Double,
        dirX: Float,
        dirY: Float,
        dirZ: Float,
        maxDistance: Float,
        out: RaycastHit,
        includeLiquids: Boolean = false
    ): Boolean {
        out.clear()

        // Нормализация: без неё шаги DDA считались бы в единицах длины
        // вектора направления, и дальность работала бы непредсказуемо.
        val lengthSq = dirX * dirX + dirY * dirY + dirZ * dirZ
        if (lengthSq < 1e-12f) return false
        val inv = 1f / Math.sqrt(lengthSq.toDouble()).toFloat()
        val dx = dirX * inv
        val dy = dirY * inv
        val dz = dirZ * inv

        var bx = MathUtils.floorInt(originX)
        var by = MathUtils.floorInt(originY)
        var bz = MathUtils.floorInt(originZ)

        // Направление шага по каждой оси.
        val stepX = if (dx > 0f) 1 else -1
        val stepY = if (dy > 0f) 1 else -1
        val stepZ = if (dz > 0f) 1 else -1

        /*
         * tDelta — насколько нужно продвинуться вдоль луча, чтобы пересечь
         * одну клетку по данной оси. Для оси с нулевой компонентой направления
         * это бесконечность: луч параллелен ей и границ по ней не пересекает.
         * Явная подстановка бесконечности вместо деления на ноль избавляет
         * от отдельной ветки в главном цикле.
         */
        val tDeltaX = if (dx != 0f) Math.abs(1f / dx) else Float.MAX_VALUE
        val tDeltaY = if (dy != 0f) Math.abs(1f / dy) else Float.MAX_VALUE
        val tDeltaZ = if (dz != 0f) Math.abs(1f / dz) else Float.MAX_VALUE

        // tMax — расстояние до первой границы клетки по каждой оси.
        var tMaxX = firstBoundary(originX, bx, dx, tDeltaX)
        var tMaxY = firstBoundary(originY, by, dy, tDeltaY)
        var tMaxZ = firstBoundary(originZ, bz, dz, tDeltaZ)

        // Грань, через которую луч вошёл в текущую клетку.
        var enteredFace = BlockFace.UP
        var travelled = 0f

        // Ограничение числа итераций — страховка от вырожденных направлений
        // (например, NaN, просочившегося из повреждённого состояния камеры).
        val maxSteps = (maxDistance * 3f).toInt() + 8

        for (step in 0 until maxSteps) {
            val id = world.getBlock(bx, by, bz)
            val isTarget = if (includeLiquids) {
                id != Blocks.AIR
            } else {
                id != Blocks.AIR && !Blocks.LIQUID[id]
            }

            // Первая клетка (та, в которой находится глаз) пропускается:
            // иначе, стоя по пояс в воде или в траве, игрок «выбирал» бы
            // блок, в котором сам и находится.
            if (isTarget && step > 0) {
                out.hit = true
                out.blockX = bx
                out.blockY = by
                out.blockZ = bz
                out.blockId = id
                out.face = enteredFace
                out.distance = travelled
                return true
            }

            // Шаг по оси, граница которой ближе всего.
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                travelled = tMaxX
                if (travelled > maxDistance) break
                bx += stepX
                tMaxX += tDeltaX
                // Вошли через грань, противоположную направлению шага.
                enteredFace = if (stepX > 0) BlockFace.WEST else BlockFace.EAST
            } else if (tMaxY < tMaxZ) {
                travelled = tMaxY
                if (travelled > maxDistance) break
                by += stepY
                tMaxY += tDeltaY
                enteredFace = if (stepY > 0) BlockFace.DOWN else BlockFace.UP
            } else {
                travelled = tMaxZ
                if (travelled > maxDistance) break
                bz += stepZ
                tMaxZ += tDeltaZ
                enteredFace = if (stepZ > 0) BlockFace.NORTH else BlockFace.SOUTH
            }
        }
        return false
    }

    /**
     * Расстояние вдоль луча до первой границы клетки по одной оси.
     *
     * Учитывает дробную часть стартовой позиции: луч, начатый у самого края
     * блока, обязан пересечь границу почти сразу, а начатый в центре —
     * пройти полклетки.
     */
    private fun firstBoundary(origin: Double, block: Int, dir: Float, tDelta: Float): Float {
        if (dir == 0f) return Float.MAX_VALUE
        val fraction = (origin - block).toFloat()
        return if (dir > 0f) {
            (1f - fraction) * tDelta
        } else {
            fraction * tDelta
        }
    }

    /** Удобная обёртка: трассировка от глаз игрока вдоль взгляда. */
    fun castFromPlayer(
        world: BlockAccess,
        player: PlayerState,
        maxDistance: Float,
        out: RaycastHit
    ): Boolean = cast(
        world,
        player.eyeX, player.eyeY, player.eyeZ,
        player.lookX(), player.lookY(), player.lookZ(),
        maxDistance, out
    )
}
