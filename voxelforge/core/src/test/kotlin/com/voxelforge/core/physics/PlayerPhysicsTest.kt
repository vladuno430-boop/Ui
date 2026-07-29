package com.voxelforge.core.physics

import com.voxelforge.core.block.Blocks
import com.voxelforge.core.world.BlockAccess
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки физики игрока.
 *
 * ## Почему эти тесты важнее большинства остальных
 *
 * Ошибка в разрешении столкновений не даёт ни исключения, ни сообщения:
 * игрок просто проваливается сквозь пол или застревает в стене. Отличить
 * это от «мир не догрузился» на глаз крайне тяжело, а воспроизвести
 * стабильно — ещё тяжелее.
 *
 * Именно такой дефект и был найден этими тестами: в [com.voxelforge.core.math.AABB]
 * условия для противоположных направлений движения оказались переставлены
 * местами, и падающий игрок не встречал пола вовсе.
 *
 * Мир для тестов подставляется заглушкой — физика зависит только от
 * интерфейса [BlockAccess], а не от настоящего мира с чанками и потоками.
 */
class PlayerPhysicsTest {

    /**
     * Простейший тестовый мир: сплошной пол на заданной высоте
     * плюс произвольные дополнительные блоки.
     */
    private class FlatWorld(
        private val floorTopY: Int,
        private val extraBlocks: Set<Triple<Int, Int, Int>> = emptySet(),
        private val floorBlock: Int = Blocks.STONE
    ) : BlockAccess {

        override fun getBlock(x: Int, y: Int, z: Int): Int {
            if (Triple(x, y, z) in extraBlocks) return Blocks.STONE
            return if (y <= floorTopY) floorBlock else Blocks.AIR
        }

        override fun getSkyLight(x: Int, y: Int, z: Int) = 15
        override fun getBlockLight(x: Int, y: Int, z: Int) = 0
        override fun getBiome(x: Int, z: Int) = 0
        override fun getHeight(x: Int, z: Int) = floorTopY + 1
    }

    /** Мир, целиком залитый водой ниже указанной отметки. */
    private class WaterWorld(
        private val waterTopY: Int,
        private val floorTopY: Int = -1
    ) : BlockAccess {
        override fun getBlock(x: Int, y: Int, z: Int): Int = when {
            y <= floorTopY -> Blocks.STONE
            y <= waterTopY -> Blocks.WATER
            else -> Blocks.AIR
        }
        override fun getSkyLight(x: Int, y: Int, z: Int) = 15
        override fun getBlockLight(x: Int, y: Int, z: Int) = 0
        override fun getBiome(x: Int, z: Int) = 0
        override fun getHeight(x: Int, z: Int) = waterTopY + 1
    }

    /** Площадка с обрывом: пол существует только при x <= edgeX. */
    private class EdgeWorld(
        private val floorTopY: Int,
        private val edgeX: Int
    ) : BlockAccess {
        override fun getBlock(x: Int, y: Int, z: Int): Int =
            if (y <= floorTopY && x <= edgeX) Blocks.STONE else Blocks.AIR
        override fun getSkyLight(x: Int, y: Int, z: Int) = 15
        override fun getBlockLight(x: Int, y: Int, z: Int) = 0
        override fun getBiome(x: Int, z: Int) = 0
        override fun getHeight(x: Int, z: Int) = floorTopY + 1
    }

    private fun newPlayer(x: Double, y: Double, z: Double): PlayerState =
        PlayerState().apply {
            teleport(x, y, z)
            mode = GameMode.SURVIVAL
            flying = false
        }

    /** Прогоняет физику указанное число шагов по 1/60 секунды. */
    private fun simulate(
        player: PlayerState,
        world: BlockAccess,
        steps: Int,
        physics: PlayerPhysics = PlayerPhysics()
    ) {
        repeat(steps) { physics.step(player, world, 1f / 60f) }
    }

    // ------------------------------------------------------------------

    @Test
    fun `игрок приземляется на пол и не проваливается`() {
        // Пол занимает всё до y=63 включительно, значит его поверхность
        // находится на отметке 64.0.
        val world = FlatWorld(floorTopY = 63)
        val player = newPlayer(0.5, 80.0, 0.5)

        simulate(player, world, steps = 300)

        assertTrue(player.onGround, "Игрок не считается стоящим на земле")
        assertEquals(
            64.0, player.y, 0.02,
            "Игрок остановился не на поверхности пола, а на высоте ${player.y}"
        )
    }

    @Test
    fun `быстрое падение с большой высоты не пробивает пол`() {
        // Проверка на туннелирование: с высоты 120 блоков скорость падения
        // достигает предельной, и за один шаг тело проходит около метра.
        // Схема «обрезание шага по заметённому объёму» обязана это выдержать.
        val world = FlatWorld(floorTopY = 5)
        val player = newPlayer(0.5, 120.0, 0.5)

        simulate(player, world, steps = 600)

        assertTrue(player.y >= 5.9, "Игрок пробил пол: оказался на высоте ${player.y}")
        assertEquals(6.0, player.y, 0.02)
    }

    @Test
    fun `игрок не проходит сквозь стену`() {
        val world = FlatWorld(
            floorTopY = 63,
            // Стена на x=2, во всю высоту тела игрока.
            extraBlocks = buildSet {
                for (y in 64..67) for (z in -2..2) add(Triple(2, y, z))
            }
        )
        val player = newPlayer(0.5, 64.0, 0.5)
        val physics = PlayerPhysics()

        // Бежим на стену две секунды.
        repeat(120) {
            player.setMovementInput(1f, 0f)
            // yaw = 0 означает движение вдоль −Z, поэтому направим вручную.
            player.inputForwardX = 1f
            player.inputForwardZ = 0f
            physics.step(player, world, 1f / 60f)
        }

        // Полуширина тела 0.3, стена начинается на x=2.0 → предел 1.7.
        assertTrue(
            player.x <= 1.72,
            "Игрок прошёл сквозь стену: x=${player.x}"
        )
        assertTrue(player.x > 0.5, "Игрок вообще не сдвинулся: x=${player.x}")
    }

    @Test
    fun `прыжок поднимает ровно на один блок`() {
        // Высота прыжка — базовая величина: на неё завязано всё
        // перемещение по рельефу. Слишком низкий прыжок делает игру
        // непроходимой, слишком высокий — ломает ощущение масштаба.
        val world = FlatWorld(floorTopY = 63)
        val player = newPlayer(0.5, 64.0, 0.5)
        val physics = PlayerPhysics()

        simulate(player, world, steps = 10, physics = physics)
        assertTrue(player.onGround, "Игрок должен стоять на земле перед прыжком")

        player.jumpRequested = true
        var maxHeight = player.y
        repeat(120) {
            physics.step(player, world, 1f / 60f)
            if (player.y > maxHeight) maxHeight = player.y
        }

        val jumpHeight = maxHeight - 64.0
        assertTrue(
            jumpHeight >= 1.0,
            "Прыжок ниже одного блока (%.2f) — на блок не забраться".format(jumpHeight)
        )
        assertTrue(
            jumpHeight < 2.0,
            "Прыжок выше двух блоков (%.2f) — ломает масштаб мира".format(jumpHeight)
        )
        // И обязательно возвращается на землю.
        assertTrue(player.onGround, "После прыжка игрок не приземлился")
    }

    @Test
    fun `удар головой о потолок гасит подъём`() {
        val world = FlatWorld(
            floorTopY = 63,
            extraBlocks = buildSet {
                for (x in -2..2) for (z in -2..2) add(Triple(x, 66, z))
            }
        )
        val player = newPlayer(0.5, 64.0, 0.5)
        val physics = PlayerPhysics()

        simulate(player, world, steps = 5, physics = physics)
        player.jumpRequested = true
        repeat(60) { physics.step(player, world, 1f / 60f) }

        // Потолок на y=66, рост 1.8 → максимум подъёма 64.2.
        assertTrue(
            player.y <= 64.25,
            "Игрок прошёл сквозь потолок: y=${player.y}"
        )
    }

    @Test
    fun `игрок не застревает при ходьбе по ровному полу`() {
        // Регрессия на случай, если гашение скорости при столкновении
        // начнёт срабатывать ложно: тогда персонаж не сможет разогнаться.
        val world = FlatWorld(floorTopY = 63)
        val player = newPlayer(0.5, 64.0, 0.5)
        val physics = PlayerPhysics()

        simulate(player, world, steps = 10, physics = physics)
        val startX = player.x

        repeat(60) {
            player.inputForwardX = 1f
            player.inputForwardZ = 0f
            physics.step(player, world, 1f / 60f)
        }

        val travelled = player.x - startX
        assertTrue(
            travelled > 2.0,
            "За секунду игрок прошёл всего %.2f блока — скорость гасится ложно".format(travelled)
        )
    }

    @Test
    fun `в творческом полёте гравитация не действует`() {
        val world = FlatWorld(floorTopY = 63)
        val player = newPlayer(0.5, 90.0, 0.5)
        player.mode = GameMode.CREATIVE
        player.flying = true

        simulate(player, world, steps = 180)

        assertEquals(90.0, player.y, 0.01, "В полёте игрок снизился до ${player.y}")
    }

    @Test
    fun `в воде падение замедляется`() {
        val waterWorld = WaterWorld(waterTopY = 60, floorTopY = 20)

        val player = newPlayer(0.5, 55.0, 0.5)
        simulate(player, waterWorld, steps = 60)

        // За секунду в воде игрок опускается на считаные блоки,
        // тогда как в воздухе пролетел бы больше десяти.
        val sank = 55.0 - player.y
        assertTrue(sank < 4.0, "Погружение слишком быстрое: %.1f блока за секунду".format(sank))
        assertTrue(player.inLiquid, "Признак нахождения в жидкости не выставлен")
    }

    @Test
    fun `всплытие работает без опоры`() {
        val waterWorld = WaterWorld(waterTopY = 60)

        val player = newPlayer(0.5, 40.0, 0.5)
        val physics = PlayerPhysics()
        repeat(60) {
            player.jumpRequested = true
            physics.step(player, waterWorld, 1f / 60f)
        }

        assertTrue(player.y > 41.0, "Игрок не всплывает: y=${player.y}")
    }

    @Test
    fun `диагональное движение не быстрее прямого`() {
        // Классический дефект управления: без нормализации ввода
        // движение по диагонали оказывается быстрее в 1,41 раза.
        val player = PlayerState()
        player.yaw = 0f

        player.setMovementInput(1f, 0f)
        val straight = Math.hypot(player.inputForwardX.toDouble(), player.inputForwardZ.toDouble())

        player.setMovementInput(1f, 1f)
        val diagonal = Math.hypot(player.inputForwardX.toDouble(), player.inputForwardZ.toDouble())

        assertTrue(
            abs(diagonal - straight) < 0.02,
            "Диагональ быстрее прямого движения: %.3f против %.3f".format(diagonal, straight)
        )
    }

    @Test
    fun `приседание у края не даёт упасть`() {
        // Пол занимает только положительные x: край на x = 1.0.
        // Пол занимает только неположительные x: край проходит по x = 1.0.
        val edgeWorld = EdgeWorld(floorTopY = 63, edgeX = 0)

        val player = newPlayer(0.5, 64.0, 0.5)
        val physics = PlayerPhysics()
        simulate(player, edgeWorld, steps = 10, physics = physics)

        player.sneaking = true
        repeat(120) {
            player.inputForwardX = 1f
            player.inputForwardZ = 0f
            physics.step(player, edgeWorld, 1f / 60f)
        }

        assertTrue(player.onGround, "Присевший игрок сошёл с края: y=${player.y}")
        assertEquals(64.0, player.y, 0.05)
    }

    @Test
    fun `шаг физики ограничен при просадке кадров`() {
        // Если приложение свернули на минуту, dt окажется огромным.
        // Без ограничения игрок телепортировался бы сквозь мир.
        val world = FlatWorld(floorTopY = 63)
        val player = newPlayer(0.5, 65.0, 0.5)
        val physics = PlayerPhysics()

        physics.step(player, world, 60f)

        assertTrue(
            player.y > 60.0,
            "Игрок провалился при огромном шаге времени: y=${player.y}"
        )
    }
}
