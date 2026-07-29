package com.voxelforge.core.physics

import com.voxelforge.core.math.MathUtils

/**
 * Режим игры.
 *
 * От него зависят не только полёт и неуязвимость, но и правила инвентаря:
 * в творческом режиме блоки берутся из бесконечной палитры, в выживании —
 * только из рюкзака.
 */
enum class GameMode {
    /** Выживание: ограниченный инвентарь, гравитация, разрушение за время. */
    SURVIVAL,

    /** Творческий: полёт, мгновенное разрушение, бесконечные блоки. */
    CREATIVE
}

/**
 * Полное состояние игрока: положение, скорость, ориентация, ввод и режим.
 *
 * ## Почему это отдельный изменяемый класс, а не поля в игровом объекте
 *
 * Состояние игрока — единственная структура, к которой обращаются
 * одновременно физика, рендер (позиция камеры), сохранение и интерфейс.
 * Вынесенное в отдельный класс, оно сериализуется одним методом,
 * подставляется в тесты без создания всей игры и позволяет физике быть
 * классом **без состояния**, то есть тривиально проверяемым.
 *
 * Координаты — с двойной точностью: в бесконечном мире float32 на удалении
 * в миллионы блоков даёт шаг сетки около метра, и персонаж начинает
 * дёргаться между позициями.
 *
 * Углы поворота хранятся в радианах: все тригонометрические функции
 * принимают именно их, и перевод из градусов на каждом кадре — лишняя работа
 * в самом горячем месте (матрица вида пересчитывается каждый кадр).
 */
class PlayerState {

    // --- Положение (точка в ногах, а не в центре тела) ---

    @JvmField var x: Double = 0.0
    @JvmField var y: Double = 80.0
    @JvmField var z: Double = 0.0

    // --- Скорость, блоков в секунду ---

    @JvmField var velocityX: Float = 0f
    @JvmField var velocityY: Float = 0f
    @JvmField var velocityZ: Float = 0f

    // --- Ориентация ---

    /** Поворот вокруг вертикали в радианах. 0 — взгляд вдоль −Z. */
    @JvmField var yaw: Float = 0f

    /** Наклон в радианах, ограничен ±(π/2 − ε). */
    @JvmField var pitch: Float = 0f

    // --- Габариты ---

    @JvmField var width: Float = 0.6f
    @JvmField var height: Float = 1.8f

    /**
     * Высота глаз над точкой опоры. Меняется при приседании — это единственный
     * зримый признак того, что приседание сработало, поэтому камера должна
     * опускаться плавно (см. [updateEyeHeight]).
     */
    @JvmField var eyeHeight: Float = STANDING_EYE_HEIGHT

    // --- Состояние среды, заполняется физикой ---

    @JvmField var onGround: Boolean = false
    @JvmField var inLiquid: Boolean = false
    @JvmField var submerged: Boolean = false
    @JvmField var inLava: Boolean = false

    // --- Режимы ---

    @JvmField var mode: GameMode = GameMode.CREATIVE
    @JvmField var flying: Boolean = false
    @JvmField var noClip: Boolean = false
    @JvmField var sprinting: Boolean = false
    @JvmField var sneaking: Boolean = false

    // --- Скорости ---

    @JvmField var walkSpeed: Float = 4.4f
    @JvmField var flySpeed: Float = 11f

    // --- Ввод текущего кадра ---

    /**
     * Направление движения в **мировых** координатах, уже развёрнутое
     * по углу взгляда. Физика намеренно не знает про джойстик и клавиши:
     * перевод «экранного» ввода в мировое направление — задача слоя ввода.
     * Благодаря этому физику можно проверять тестами, подавая направления
     * напрямую, и она не меняется при добавлении геймпада или клавиатуры.
     */
    @JvmField var inputForwardX: Float = 0f
    @JvmField var inputForwardZ: Float = 0f

    /** Вертикальный ввод для полёта и всплытия: −1..1. */
    @JvmField var inputUp: Float = 0f

    /** Запрос прыжка; сбрасывается физикой после обработки. */
    @JvmField var jumpRequested: Boolean = false

    /** Координаты глаза — точка, из которой строится камера и луч выбора блока. */
    val eyeX: Double get() = x
    val eyeY: Double get() = y + eyeHeight
    val eyeZ: Double get() = z

    /**
     * Направление взгляда. Совпадает с соглашением [com.voxelforge.core.math.Mat4]:
     * при yaw = 0 и pitch = 0 взгляд направлен вдоль −Z.
     */
    fun lookX(): Float = -Math.sin(yaw.toDouble()).toFloat() * Math.cos(pitch.toDouble()).toFloat()
    fun lookY(): Float = Math.sin(pitch.toDouble()).toFloat()
    fun lookZ(): Float = -Math.cos(yaw.toDouble()).toFloat() * Math.cos(pitch.toDouble()).toFloat()

    /**
     * Переводит ввод из системы координат экрана (вперёд/вбок относительно
     * взгляда) в мировое направление.
     *
     * @param forward −1..1, положительное — вперёд
     * @param strafe  −1..1, положительное — вправо
     */
    fun setMovementInput(forward: Float, strafe: Float) {
        val sin = Math.sin(yaw.toDouble()).toFloat()
        val cos = Math.cos(yaw.toDouble()).toFloat()
        // Вперёд: (−sin, −cos). Вправо: (cos, −sin).
        var wx = -sin * forward + cos * strafe
        var wz = -cos * forward - sin * strafe
        // Нормализация: без неё диагональное движение быстрее прямого
        // ровно в 1,41 раза — классический дефект управления.
        val len = Math.sqrt((wx * wx + wz * wz).toDouble()).toFloat()
        if (len > 1f) {
            wx /= len
            wz /= len
        }
        inputForwardX = wx
        inputForwardZ = wz
    }

    /** Поворот камеры свайпом. Наклон ограничивается, чтобы не «кувыркнуться». */
    fun addLook(deltaYaw: Float, deltaPitch: Float) {
        yaw += deltaYaw
        // Держим угол в пределах одного оборота, иначе float постепенно
        // теряет точность при долгой игре с непрерывным вращением.
        if (yaw > MathUtils.PI) yaw -= MathUtils.TWO_PI
        if (yaw < -MathUtils.PI) yaw += MathUtils.TWO_PI

        pitch = MathUtils.clamp(pitch + deltaPitch, -PITCH_LIMIT, PITCH_LIMIT)
    }

    /**
     * Плавно подтягивает высоту глаз к целевой.
     * Мгновенное переключение при приседании выглядит как рывок камеры,
     * который на маленьком экране читается как сбой отрисовки.
     */
    fun updateEyeHeight(dt: Float) {
        val target = if (sneaking) SNEAKING_EYE_HEIGHT else STANDING_EYE_HEIGHT
        val rate = MathUtils.clamp(dt * EYE_HEIGHT_SPEED, 0f, 1f)
        eyeHeight += (target - eyeHeight) * rate
    }

    /** Мгновенно переносит игрока (появление в мире, загрузка сохранения). */
    fun teleport(nx: Double, ny: Double, nz: Double) {
        x = nx; y = ny; z = nz
        velocityX = 0f; velocityY = 0f; velocityZ = 0f
        onGround = false
    }

    fun copyFrom(other: PlayerState) {
        x = other.x; y = other.y; z = other.z
        velocityX = other.velocityX; velocityY = other.velocityY; velocityZ = other.velocityZ
        yaw = other.yaw; pitch = other.pitch
        mode = other.mode; flying = other.flying
        walkSpeed = other.walkSpeed; flySpeed = other.flySpeed
    }

    override fun toString(): String =
        "Player(%.1f, %.1f, %.1f, режим=%s%s)".format(
            x, y, z, mode,
            if (onGround) ", на земле" else ""
        )

    companion object {
        const val STANDING_EYE_HEIGHT = 1.62f
        const val SNEAKING_EYE_HEIGHT = 1.32f

        /** Скорость подтягивания камеры при приседании, доля за секунду. */
        private const val EYE_HEIGHT_SPEED = 14f

        /**
         * Предел наклона чуть меньше прямого угла. Ровно 90° приводит к тому,
         * что направление взгляда становится параллельно вектору «вверх»,
         * матрица вида вырождается, и кадр обращается в чёрный экран.
         */
        private val PITCH_LIMIT = MathUtils.PI * 0.5f - 0.001f
    }
}
