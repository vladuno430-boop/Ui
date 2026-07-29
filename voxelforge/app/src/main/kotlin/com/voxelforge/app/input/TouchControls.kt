package com.voxelforge.app.input

import android.view.MotionEvent
import com.voxelforge.app.render.UiRenderer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Прямоугольная область экрана. Используется для кнопок и зон касания.
 */
class Rect(
    @JvmField var x: Float = 0f,
    @JvmField var y: Float = 0f,
    @JvmField var width: Float = 0f,
    @JvmField var height: Float = 0f
) {
    fun contains(px: Float, py: Float): Boolean =
        px >= x && px <= x + width && py >= y && py <= y + height

    val centerX: Float get() = x + width * 0.5f
    val centerY: Float get() = y + height * 0.5f
}

/**
 * Экранная кнопка с состоянием нажатия.
 *
 * Кнопка помнит **идентификатор пальца**, которым нажата. Это принципиально
 * для сенсорного управления: без привязки к пальцу отпускание любого другого
 * касания сбрасывало бы нажатие, и, например, прыжок срывался бы каждый раз,
 * когда игрок отпускает джойстик.
 */
class TouchButton(val label: String) {
    @JvmField val bounds = Rect()

    /** Идентификатор удерживающего пальца; −1, если кнопка не нажата. */
    @JvmField var pointerId: Int = -1

    /** Сработала ли кнопка в этом кадре (для одиночных действий). */
    @JvmField var justPressed: Boolean = false

    /** Как долго кнопка удерживается, в секундах. */
    @JvmField var heldSeconds: Float = 0f

    val isPressed: Boolean get() = pointerId != -1

    fun press(id: Int) {
        if (pointerId == -1) {
            pointerId = id
            justPressed = true
            heldSeconds = 0f
        }
    }

    fun release() {
        pointerId = -1
        heldSeconds = 0f
    }

    fun update(dt: Float) {
        justPressed = false
        if (isPressed) heldSeconds += dt
    }
}

/**
 * Виртуальный джойстик перемещения.
 *
 * ## «Плавающий» центр
 *
 * Центр джойстика устанавливается **в точку первого касания** внутри его
 * зоны, а не в геометрический центр нарисованного круга. Причина — большой
 * палец не попадает точно в нарисованную область, и при жёстком центре
 * первое же касание сразу выдаёт отклонение в полсилы: персонаж дёргается
 * в сторону в момент, когда игрок только положил палец.
 *
 * ## Мёртвая зона
 *
 * Малые отклонения игнорируются: палец на экране никогда не стоит идеально
 * неподвижно, и без мёртвой зоны персонаж непрерывно подрагивает, стоя
 * на месте.
 */
class VirtualJoystick {

    @JvmField val zone = Rect()

    private var pointerId = -1
    private var centerX = 0f
    private var centerY = 0f
    private var currentX = 0f
    private var currentY = 0f

    /** Отклонение по осям в диапазоне −1..1. */
    @JvmField var outputX: Float = 0f
    @JvmField var outputY: Float = 0f

    val isActive: Boolean get() = pointerId != -1

    /** Радиус, при котором отклонение достигает максимума, в пикселях. */
    var radius: Float = 120f

    fun onDown(id: Int, x: Float, y: Float): Boolean {
        if (pointerId != -1 || !zone.contains(x, y)) return false
        pointerId = id
        centerX = x
        centerY = y
        currentX = x
        currentY = y
        updateOutput()
        return true
    }

    fun onMove(id: Int, x: Float, y: Float): Boolean {
        if (id != pointerId) return false
        currentX = x
        currentY = y
        updateOutput()
        return true
    }

    fun onUp(id: Int): Boolean {
        if (pointerId == -1 || id != pointerId) return false
        release()
        return true
    }

    /**
     * Безусловный сброс. Нужен при ACTION_CANCEL: система отбирает касания
     * при входящем звонке или разворачивании шторки уведомлений, событие
     * ACTION_UP при этом не приходит. Без сброса игрок вернулся бы в игру
     * с намертво отклонённым джойстиком и продолжал идти сам по себе.
     */
    fun release() {
        pointerId = -1
        outputX = 0f
        outputY = 0f
    }

    private fun updateOutput() {
        var dx = currentX - centerX
        var dy = currentY - centerY
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < DEAD_ZONE) {
            outputX = 0f
            outputY = 0f
            return
        }

        // Нормируем к радиусу и ограничиваем единицей.
        val scale = (distance.coerceAtMost(radius) / radius) / distance
        dx *= scale
        dy *= scale

        outputX = dx
        // Экранная ось Y направлена вниз, а «вперёд» — это вверх по экрану.
        outputY = -dy
    }

    /** Рисует джойстик: внешнее кольцо и подвижная головка. */
    fun draw(ui: UiRenderer) {
        val cx: Float
        val cy: Float
        if (isActive) {
            cx = centerX
            cy = centerY
        } else {
            cx = zone.centerX
            cy = zone.centerY
        }

        ui.ring(cx, cy, radius, 5f, if (isActive) 0x80FFFFFF.toInt() else 0x50FFFFFF)
        val knobX = cx + outputX * radius
        val knobY = cy - outputY * radius
        ui.circle(knobX, knobY, radius * 0.36f, if (isActive) 0xB0FFFFFF.toInt() else 0x60FFFFFF)
    }

    companion object {
        /** Мёртвая зона в пикселях. */
        private const val DEAD_ZONE = 12f
    }
}

/**
 * Обработчик всех касаний экрана.
 *
 * ## Разделение экрана
 *
 * Левая половина — перемещение (джойстик), правая — обзор (свайп) и кнопки
 * действий. Такое разделение стало стандартом мобильных игр от первого лица
 * и не требует объяснения игроку.
 *
 * ## Многопальцевость
 *
 * Ключевое требование: игрок обязан одновременно идти, вертеть камерой и
 * нажимать кнопку. Поэтому обработка идёт по **идентификаторам пальцев**,
 * а не по «текущему касанию»: каждый элемент управления захватывает свой
 * палец и следит только за ним. Именно поэтому обрабатываются события
 * ACTION_POINTER_DOWN и ACTION_POINTER_UP, а не только ACTION_DOWN/UP —
 * последние приходят лишь для первого и последнего пальца.
 */
class TouchControls {

    @JvmField val joystick = VirtualJoystick()

    @JvmField val jumpButton = TouchButton("Прыжок")
    @JvmField val breakButton = TouchButton("Ломать")
    @JvmField val placeButton = TouchButton("Ставить")
    @JvmField val flyButton = TouchButton("Полёт")
    @JvmField val sneakButton = TouchButton("Присесть")
    @JvmField val sprintButton = TouchButton("Бег")
    @JvmField val inventoryButton = TouchButton("Инвентарь")

    private val buttons = arrayOf(
        jumpButton, breakButton, placeButton, flyButton,
        sneakButton, sprintButton, inventoryButton
    )

    /** Зона свайпа для поворота камеры. */
    private val lookZone = Rect()

    private var lookPointerId = -1
    private var lastLookX = 0f
    private var lastLookY = 0f

    /** Накопленный за кадр поворот в пикселях. */
    @JvmField var lookDeltaX: Float = 0f
    @JvmField var lookDeltaY: Float = 0f

    /** Ячейки быстрого доступа: индекс выбранной или −1, если не менялась. */
    @JvmField var hotbarSelection: Int = -1

    private val hotbarSlots = Array(HOTBAR_SIZE) { Rect() }

    private var screenWidth = 1f
    private var screenHeight = 1f

    /** Плотность экрана — все размеры задаются в миллиметрах, а не пикселях. */
    private var density = 1f

    /**
     * Раскладывает элементы управления под размер экрана.
     *
     * Размеры считаются от физической плотности, а не от разрешения:
     * кнопка под палец должна быть около 9 мм на любом устройстве.
     * Расчёт в пикселях дал бы на экране 1440p кнопки вдвое мельче,
     * чем на 720p, при одинаковом физическом размере экрана.
     */
    fun layout(width: Int, height: Int, displayDensity: Float) {
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        density = displayDensity

        val unit = 48f * density          // базовый размер кнопки, ~9 мм
        val margin = 14f * density

        // Джойстик — левый нижний угол.
        joystick.radius = unit * 1.15f
        joystick.zone.x = 0f
        joystick.zone.y = height * 0.30f
        joystick.zone.width = width * 0.42f
        joystick.zone.height = height * 0.70f

        // Зона обзора — вся правая часть экрана; кнопки поверх неё
        // обрабатываются раньше и потому перехватывают касание.
        lookZone.x = width * 0.42f
        lookZone.y = 0f
        lookZone.width = width * 0.58f
        lookZone.height = height.toFloat()

        // Кнопки действий — правый нижний угол, «веером» под большой палец.
        val rightX = width - margin - unit
        val bottomY = height - margin - unit

        breakButton.bounds.apply {
            x = rightX - unit * 1.45f; y = bottomY - unit * 0.15f
            this.width = unit * 1.25f; this.height = unit * 1.25f
        }
        placeButton.bounds.apply {
            x = rightX - unit * 0.15f; y = bottomY - unit * 1.5f
            this.width = unit * 1.25f; this.height = unit * 1.25f
        }
        jumpButton.bounds.apply {
            x = rightX + unit * 0.05f; y = bottomY + unit * 0.05f
            this.width = unit * 1.1f; this.height = unit * 1.1f
        }
        sneakButton.bounds.apply {
            x = margin; y = bottomY - unit * 0.1f
            this.width = unit; this.height = unit
        }
        sprintButton.bounds.apply {
            x = margin; y = bottomY - unit * 1.3f
            this.width = unit; this.height = unit
        }
        flyButton.bounds.apply {
            x = width - margin - unit * 0.9f; y = margin
            this.width = unit * 0.9f; this.height = unit * 0.9f
        }
        inventoryButton.bounds.apply {
            x = width - margin - unit * 1.95f; y = margin
            this.width = unit * 0.9f; this.height = unit * 0.9f
        }

        // Быстрые слоты — по центру снизу.
        val slotSize = unit * 0.85f
        val totalWidth = slotSize * HOTBAR_SIZE
        val startX = (width - totalWidth) * 0.5f
        val slotY = height - slotSize - margin * 0.4f
        for (i in 0 until HOTBAR_SIZE) {
            hotbarSlots[i].apply {
                x = startX + i * slotSize
                y = slotY
                this.width = slotSize
                this.height = slotSize
            }
        }
    }

    /**
     * Обрабатывает событие касания.
     *
     * Порядок проверок задаёт приоритет: кнопки и слоты перехватывают
     * касание раньше зоны обзора, иначе нажатие на кнопку, лежащую поверх
     * зоны свайпа, одновременно крутило бы камеру.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                handleDown(id, x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                // ACTION_MOVE приходит одним событием на все пальцы сразу,
                // поэтому обходим их все, а не только actionIndex.
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (joystick.onMove(id, x, y)) continue
                    if (id == lookPointerId) {
                        lookDeltaX += x - lastLookX
                        lookDeltaY += y - lastLookY
                        lastLookX = x
                        lastLookY = y
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                handleUp(id)
            }

            MotionEvent.ACTION_CANCEL -> {
                // Система отобрала касания (звонок, шторка уведомлений).
                // Все элементы обязаны отпуститься, иначе игрок вернётся
                // в игру с намертво зажатым прыжком.
                releaseAll()
            }
        }
        return true
    }

    private fun handleDown(id: Int, x: Float, y: Float) {
        for (i in 0 until HOTBAR_SIZE) {
            if (hotbarSlots[i].contains(x, y)) {
                hotbarSelection = i
                return
            }
        }
        for (button in buttons) {
            if (button.bounds.contains(x, y)) {
                button.press(id)
                return
            }
        }
        if (joystick.onDown(id, x, y)) return

        if (lookPointerId == -1 && lookZone.contains(x, y)) {
            lookPointerId = id
            lastLookX = x
            lastLookY = y
        }
    }

    private fun handleUp(id: Int) {
        if (joystick.onUp(id)) return
        for (button in buttons) {
            if (button.pointerId == id) {
                button.release()
                return
            }
        }
        if (id == lookPointerId) lookPointerId = -1
    }

    private fun releaseAll() {
        joystick.release()
        for (button in buttons) button.release()
        lookPointerId = -1
        lookDeltaX = 0f
        lookDeltaY = 0f
    }

    /** Сбрасывает одноразовые состояния. Вызывается в конце кадра. */
    fun endFrame(dt: Float) {
        lookDeltaX = 0f
        lookDeltaY = 0f
        hotbarSelection = -1
        for (button in buttons) button.update(dt)
    }

    /** Рисует все элементы управления. */
    fun draw(ui: UiRenderer, hotbarItems: IntArray, selectedSlot: Int, flying: Boolean, sneaking: Boolean, sprinting: Boolean) {
        joystick.draw(ui)

        drawButton(ui, breakButton, 0xC0C03030.toInt(), 0x60FFFFFF)
        drawButton(ui, placeButton, 0xC03060C0.toInt(), 0x60FFFFFF)
        drawButton(ui, jumpButton, 0xB0FFFFFF.toInt(), 0x40FFFFFF)
        drawButton(ui, sneakButton, if (sneaking) 0xC0FFC040.toInt() else 0x70FFFFFF, 0x40FFFFFF)
        drawButton(ui, sprintButton, if (sprinting) 0xC040FF80.toInt() else 0x70FFFFFF, 0x40FFFFFF)
        drawButton(ui, flyButton, if (flying) 0xC040C0FF.toInt() else 0x70FFFFFF, 0x40FFFFFF)
        drawButton(ui, inventoryButton, 0x90FFFFFF.toInt(), 0x40FFFFFF)

        drawHotbar(ui, hotbarItems, selectedSlot)
    }

    private fun drawButton(ui: UiRenderer, button: TouchButton, fill: Int, border: Int) {
        val b = button.bounds
        val radius = b.width * 0.5f
        val alphaBoost = if (button.isPressed) 0x30000000 else 0
        ui.circle(b.centerX, b.centerY, radius, fill + alphaBoost)
        ui.ring(b.centerX, b.centerY, radius, 3f, border)
    }

    private fun drawHotbar(ui: UiRenderer, items: IntArray, selected: Int) {
        for (i in 0 until HOTBAR_SIZE) {
            val slot = hotbarSlots[i]
            ui.rect(slot.x, slot.y, slot.width, slot.height, 0x70101418)
            val id = if (i < items.size) items[i] else 0
            if (id != 0) {
                val pad = slot.width * 0.16f
                val tile = com.voxelforge.core.block.Blocks.get(id).iconTile
                ui.icon(slot.x + pad, slot.y + pad, slot.width - pad * 2, slot.height - pad * 2, tile)
            }
            // Выделение активной ячейки — единственный способ понять,
            // какой блок будет поставлен.
            val borderColor = if (i == selected) 0xFFFFFFFF.toInt() else 0x50FFFFFF
            val thickness = if (i == selected) 3f else 1.5f
            ui.frame(slot.x, slot.y, slot.width, slot.height, thickness, borderColor)
        }
    }

    /** Рисует прицел в центре экрана. */
    fun drawCrosshair(ui: UiRenderer) {
        val cx = screenWidth * 0.5f
        val cy = screenHeight * 0.5f
        val size = 11f * density
        val thickness = 2f * density
        val color = 0xC0FFFFFF.toInt()
        ui.rect(cx - size, cy - thickness * 0.5f, size * 2f, thickness, color)
        ui.rect(cx - thickness * 0.5f, cy - size, thickness, size * 2f, color)
    }

    /** Чувствительность поворота: радиан на пиксель свайпа. */
    fun lookSensitivity(): Float = 0.0038f / density * 2.6f

    companion object {
        const val HOTBAR_SIZE = 9
    }
}
