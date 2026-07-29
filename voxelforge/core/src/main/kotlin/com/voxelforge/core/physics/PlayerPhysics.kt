package com.voxelforge.core.physics

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.math.AABB
import com.voxelforge.core.math.MathUtils
import com.voxelforge.core.world.BlockAccess
import com.voxelforge.core.world.WorldConstants.MAX_Y

/**
 * Физика перемещения игрока: гравитация, столкновения, плавание, полёт.
 *
 * ## Почему столкновения решаются «обрезанием шага», а не выталкиванием
 *
 * Наивная схема — сдвинуть тело, обнаружить проникновение, вытолкнуть
 * обратно — в воксельном мире работает плохо по двум причинам. Во-первых,
 * при большой скорости (падение с высоты) тело за кадр проходит несколько
 * блоков и «протыкает» пол насквозь: проникновения в конце кадра просто
 * нет, потому что тело уже под полом. Во-вторых, выталкивание из угла
 * между двумя блоками неоднозначно — направление зависит от порядка
 * проверок, и игрока произвольно «выстреливает» вбок.
 *
 * Здесь применяется другая схема: **перемещение укорачивается до контакта**.
 * Из мира берутся все блоки в объёме, который тело заметает за кадр, и
 * шаг по каждой оси сокращается так, чтобы проникновения не возникло вовсе.
 * Туннелирование становится невозможным по построению, а поведение
 * в углах — детерминированным.
 *
 * ## Порядок осей
 *
 * Оси обрабатываются раздельно и в порядке Y → X → Z. Порядок не случаен:
 * вертикаль разрешается первой, чтобы к моменту горизонтального движения
 * уже было известно, стоит ли игрок на земле. Иначе шаг вперёд у самого
 * края блока то регистрировался бы как «на земле», то нет, и прыжок
 * срабатывал бы через раз.
 *
 * Класс не хранит состояния мира и потокобезопасен при условии, что
 * каждый вызывающий поток передаёт своё [PlayerState].
 */
class PlayerPhysics {

    // Переиспользуемые коробки: физика вызывается 60 раз в секунду,
    // и аллокации здесь дали бы постоянный поток мусора.
    private val bodyBox = AABB()
    private val blockBox = AABB()
    private val sweptBox = AABB()

    /**
     * Продвигает игрока на один шаг симуляции.
     *
     * @param state состояние игрока; изменяется на месте
     * @param world источник блоков
     * @param dt    шаг времени в секундах
     */
    fun step(state: PlayerState, world: BlockAccess, dt: Float) {
        // Ограничение шага. При просадке кадра (загрузка чанков, сворачивание
        // приложения) dt может оказаться в сотни миллисекунд, и за один шаг
        // игрок пролетел бы десятки блоков. Ограничение превращает такую
        // ситуацию в замедление, а не в проваливание сквозь мир.
        val step = MathUtils.clamp(dt, 0f, MAX_STEP_SECONDS)
        if (step <= 0f) return

        updateEnvironment(state, world)

        if (state.flying) {
            applyFlightMovement(state, step)
        } else {
            applyWalkMovement(state, world, step)
        }

        moveWithCollisions(state, world, step)
    }

    /**
     * Определяет, в какой среде находится игрок: в воздухе, в воде, в лаве.
     * Проверяется точка на уровне глаз и на уровне ног — этого достаточно,
     * чтобы различить «стоит по пояс в воде» и «плывёт полностью погружённым».
     */
    private fun updateEnvironment(state: PlayerState, world: BlockAccess) {
        val feetBlock = world.getBlock(
            MathUtils.floorInt(state.x),
            MathUtils.floorInt(state.y + 0.1),
            MathUtils.floorInt(state.z)
        )
        val eyeBlock = world.getBlock(
            MathUtils.floorInt(state.x),
            MathUtils.floorInt(state.y + state.eyeHeight),
            MathUtils.floorInt(state.z)
        )
        state.inLiquid = Blocks.LIQUID[feetBlock]
        state.submerged = Blocks.LIQUID[eyeBlock]
        state.inLava = feetBlock == Blocks.LAVA
    }

    /** Движение в творческом полёте: без гравитации, с сильным затуханием. */
    private fun applyFlightMovement(state: PlayerState, dt: Float) {
        state.velocityY = state.inputUp * state.flySpeed

        val accel = state.flySpeed * FLIGHT_ACCELERATION
        state.velocityX += state.inputForwardX * accel * dt
        state.velocityZ += state.inputForwardZ * accel * dt

        // Затухание, независимое от частоты кадров: коэффициент возводится
        // в степень, пропорциональную времени. Простое умножение на константу
        // давало бы разную инерцию при 30 и 60 кадрах в секунду.
        val damping = Math.pow(FLIGHT_DAMPING.toDouble(), dt.toDouble()).toFloat()
        state.velocityX *= damping
        state.velocityZ *= damping

        clampHorizontalSpeed(state, state.flySpeed)
    }

    /** Ходьба, бег, приседание, прыжок и плавание. */
    private fun applyWalkMovement(state: PlayerState, world: BlockAccess, dt: Float) {
        val targetSpeed = when {
            state.inLiquid -> state.walkSpeed * SWIM_SPEED_FACTOR
            state.sneaking -> state.walkSpeed * SNEAK_SPEED_FACTOR
            state.sprinting -> state.walkSpeed * SPRINT_SPEED_FACTOR
            else -> state.walkSpeed
        }

        // Сцепление с поверхностью. В воздухе управление ослаблено, но не
        // отключено: полностью баллистический прыжок в игре про строительство
        // ощущается неуправляемым, а точное приземление на блок — базовое
        // действие.
        val groundFriction = if (state.onGround) surfaceFriction(state, world) else AIR_CONTROL
        val accel = targetSpeed * ACCELERATION * groundFriction

        state.velocityX += state.inputForwardX * accel * dt
        state.velocityZ += state.inputForwardZ * accel * dt

        val damping = Math.pow(
            (if (state.onGround) GROUND_DAMPING else AIR_DAMPING).toDouble(),
            dt.toDouble()
        ).toFloat()
        state.velocityX *= damping
        state.velocityZ *= damping
        clampHorizontalSpeed(state, targetSpeed)

        if (state.inLiquid) {
            // В жидкости гравитация ослаблена, а всплытие происходит само:
            // так плавание не требует непрерывного нажатия прыжка.
            state.velocityY += GRAVITY * BUOYANCY_FACTOR * dt
            if (state.inputUp > 0f || state.jumpRequested) {
                state.velocityY = SWIM_UP_SPEED
            }
            state.velocityY = MathUtils.clamp(state.velocityY, -SWIM_SINK_SPEED, SWIM_UP_SPEED)
            state.jumpRequested = false
        } else {
            state.velocityY += GRAVITY * dt
            // Предельная скорость падения: без неё падение в глубокую шахту
            // разгоняет тело до скорости, на которой даже свип-проверка
            // начинает пропускать тонкие перекрытия.
            if (state.velocityY < TERMINAL_VELOCITY) state.velocityY = TERMINAL_VELOCITY

            if (state.jumpRequested && state.onGround) {
                state.velocityY = JUMP_VELOCITY
                state.onGround = false
            }
            state.jumpRequested = false
        }
    }

    /** Коэффициент сцепления с блоком под ногами (лёд скользкий). */
    private fun surfaceFriction(state: PlayerState, world: BlockAccess): Float {
        val below = world.getBlock(
            MathUtils.floorInt(state.x),
            MathUtils.floorInt(state.y - 0.1),
            MathUtils.floorInt(state.z)
        )
        val slipperiness = Blocks.get(below).slipperiness
        return if (slipperiness <= 0f) 1f else 1f / slipperiness
    }

    private fun clampHorizontalSpeed(state: PlayerState, maxSpeed: Float) {
        val speedSq = state.velocityX * state.velocityX + state.velocityZ * state.velocityZ
        val limit = maxSpeed * SPEED_LIMIT_SLACK
        if (speedSq > limit * limit) {
            val scale = limit / Math.sqrt(speedSq.toDouble()).toFloat()
            state.velocityX *= scale
            state.velocityZ *= scale
        }
    }

    /**
     * Перемещает тело с разрешением столкновений по трём осям.
     */
    private fun moveWithCollisions(state: PlayerState, world: BlockAccess, dt: Float) {
        val requestedDx = (state.velocityX * dt).toDouble()
        val requestedDy = (state.velocityY * dt).toDouble()
        val requestedDz = (state.velocityZ * dt).toDouble()

        if (state.noClip) {
            state.x += requestedDx
            state.y += requestedDy
            state.z += requestedDz
            state.onGround = false
            return
        }

        var dx = requestedDx
        var dy = requestedDy
        var dz = requestedDz

        bodyBox.setFromFeet(state.x, state.y, state.z, state.width.toDouble(), state.height.toDouble())
        val wasOnGround = state.onGround

        // Объём, заметаемый телом за кадр. Собрав блоки внутри него,
        // мы гарантированно не пропустим ни одного столкновения —
        // именно это исключает туннелирование при быстром падении.
        bodyBox.expandTowards(dx, dy, dz, sweptBox)
        sweptBox.grow(COLLISION_EPSILON)

        val minX = MathUtils.floorInt(sweptBox.minX)
        val maxX = MathUtils.floorInt(sweptBox.maxX)
        val minY = MathUtils.clamp(MathUtils.floorInt(sweptBox.minY), 0, MAX_Y)
        val maxY = MathUtils.clamp(MathUtils.floorInt(sweptBox.maxY), 0, MAX_Y)
        val minZ = MathUtils.floorInt(sweptBox.minZ)
        val maxZ = MathUtils.floorInt(sweptBox.maxZ)

        /*
         * Оси разрешаются по очереди, вертикаль первой.
         *
         * Порядок принципиален: признак «стоит на земле» вычисляется
         * в вертикальном проходе, и горизонтальное движение должно
         * опираться на уже уточнённое значение. При обратном порядке
         * шаг у края блока то регистрировался бы как «на земле», то нет,
         * и прыжок срабатывал бы через раз.
         */

        // --- Вертикаль ---
        for (by in minY..maxY) {
            for (bz in minZ..maxZ) {
                for (bx in minX..maxX) {
                    if (!isCollidable(world, bx, by, bz)) continue
                    fillBlockBox(world, bx, by, bz)
                    dy = bodyBox.clipYCollide(blockBox, dy)
                }
            }
        }
        bodyBox.translate(0.0, dy, 0.0)

        val verticalBlocked = Math.abs(dy - requestedDy) > COLLISION_EPSILON
        state.onGround = requestedDy <= 0.0 && verticalBlocked
        if (verticalBlocked) {
            // Гасим вертикальную скорость и при ударе о пол, и при ударе
            // головой: иначе игрок «прилипает» к перекрытию до конца прыжка.
            state.velocityY = 0f
        }

        // --- Ось X ---
        for (by in minY..maxY) {
            for (bz in minZ..maxZ) {
                for (bx in minX..maxX) {
                    if (!isCollidable(world, bx, by, bz)) continue
                    fillBlockBox(world, bx, by, bz)
                    dx = bodyBox.clipXCollide(blockBox, dx)
                }
            }
        }
        bodyBox.translate(dx, 0.0, 0.0)

        // --- Ось Z ---
        for (by in minY..maxY) {
            for (bz in minZ..maxZ) {
                for (bx in minX..maxX) {
                    if (!isCollidable(world, bx, by, bz)) continue
                    fillBlockBox(world, bx, by, bz)
                    dz = bodyBox.clipZCollide(blockBox, dz)
                }
            }
        }

        /*
         * Приседание у края: если игрок сидит, стоял на земле и шаг увёл бы
         * его в пустоту, шаг отменяется. Это не украшательство — без такой
         * защиты строить мост или карниз, глядя вниз, практически невозможно.
         *
         * Проверка делается до применения смещения к позиции, поэтому
         * отменённый шаг не оставляет коробку и позицию рассогласованными.
         */
        if (state.sneaking && wasOnGround && state.onGround) {
            if (dx != 0.0 && !hasGroundBelow(world, state.x + dx, state.y, state.z)) dx = 0.0
            if (dz != 0.0 && !hasGroundBelow(world, state.x, state.y, state.z + dz)) dz = 0.0
        }

        state.x += dx
        state.y += dy
        state.z += dz

        // Гасим скорость по осям, где произошло столкновение: иначе тело
        // продолжает «давить» в стену, а при отпускании управления резко
        // отскакивает накопленной скоростью.
        if (Math.abs(dx - requestedDx) > COLLISION_EPSILON) state.velocityX = 0f
        if (Math.abs(dz - requestedDz) > COLLISION_EPSILON) state.velocityZ = 0f

        // Страховка от выпадения из мира: даже при ошибке в геометрии
        // игрок не должен падать в пустоту бесконечно.
        if (state.y < -8.0) {
            state.y = MAX_Y.toDouble()
            state.velocityY = 0f
        }
    }

    /** Есть ли под точкой опора в пределах небольшого спуска. */
    private fun hasGroundBelow(world: BlockAccess, x: Double, y: Double, z: Double): Boolean {
        val bx = MathUtils.floorInt(x)
        val bz = MathUtils.floorInt(z)
        val by = MathUtils.floorInt(y - SNEAK_PROBE_DEPTH)
        return isCollidable(world, bx, by, bz)
    }

    private fun isCollidable(world: BlockAccess, x: Int, y: Int, z: Int): Boolean {
        if (y < 0 || y > MAX_Y) return false
        return Blocks.SOLID[world.getBlock(x, y, z)]
    }

    private fun fillBlockBox(world: BlockAccess, x: Int, y: Int, z: Int) {
        val id = world.getBlock(x, y, z)
        blockBox.setBlock(x, y, z, Blocks.get(id).collisionHeight)
    }

    companion object {
        /** Ускорение свободного падения, блоков в секунду за секунду. */
        const val GRAVITY = -26f

        /**
         * Начальная скорость прыжка. Подобрана так, чтобы игрок запрыгивал
         * ровно на один блок с небольшим запасом: это базовое движение,
         * и промах по высоте здесь ощущается как сломанное управление.
         */
        const val JUMP_VELOCITY = 8.4f

        const val TERMINAL_VELOCITY = -55f

        const val SPRINT_SPEED_FACTOR = 1.55f
        const val SNEAK_SPEED_FACTOR = 0.35f
        const val SWIM_SPEED_FACTOR = 0.6f

        private const val ACCELERATION = 14f
        private const val FLIGHT_ACCELERATION = 12f
        private const val AIR_CONTROL = 0.28f

        // Затухание задано как доля скорости, сохраняемая за секунду.
        private const val GROUND_DAMPING = 0.0000045f
        private const val AIR_DAMPING = 0.35f
        private const val FLIGHT_DAMPING = 0.000002f

        private const val BUOYANCY_FACTOR = 0.22f
        private const val SWIM_UP_SPEED = 3.4f
        private const val SWIM_SINK_SPEED = 2.2f

        /** Небольшой запас скорости сверх целевой — для диагонального движения. */
        private const val SPEED_LIMIT_SLACK = 1.05f

        private const val COLLISION_EPSILON = 1e-6
        private const val SNEAK_PROBE_DEPTH = 0.06
        private const val MAX_STEP_SECONDS = 0.05f
    }
}
