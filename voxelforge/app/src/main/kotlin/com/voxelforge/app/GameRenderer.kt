package com.voxelforge.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.voxelforge.app.input.TouchControls
import com.voxelforge.app.render.DayNightCycle
import com.voxelforge.app.render.SkyRenderer
import com.voxelforge.app.render.UiRenderer
import com.voxelforge.app.render.WorldRenderer
import com.voxelforge.core.block.Blocks
import com.voxelforge.core.math.Mat4
import com.voxelforge.core.math.MathUtils
import com.voxelforge.core.physics.BlockRaycaster
import com.voxelforge.core.physics.GameMode
import com.voxelforge.core.physics.PlayerPhysics
import com.voxelforge.core.physics.PlayerState
import com.voxelforge.core.physics.RaycastHit
import com.voxelforge.core.world.ChunkManager
import com.voxelforge.core.world.World
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Главный цикл игры и отрисовки.
 *
 * ## Почему всё в одном потоке отрисовки
 *
 * `GLSurfaceView` вызывает [onDrawFrame] в собственном потоке, где действует
 * контекст OpenGL. Логика игры выполняется там же, а не в отдельном потоке,
 * и это осознанное решение: разделение логики и отрисовки потребовало бы
 * копировать состояние мира для рендера каждый кадр либо синхронизировать
 * доступ к нему. Для воксельного мира с сотнями тысяч изменяемых вокселей
 * и то и другое дороже, чем вся выгода от параллелизма.
 *
 * Параллелизм при этом никуда не делся — он вынесен туда, где даёт эффект
 * без разделяемого состояния: генерация и мешинг чанков идут в пуле потоков
 * (см. [ChunkManager]).
 *
 * ## Фиксированный шаг физики
 *
 * Физика считается шагами постоянной длительности, независимо от частоты
 * кадров. При переменном шаге высота прыжка и дальность падения зависели бы
 * от производительности устройства: на слабом телефоне игрок прыгал бы ниже.
 * Накопленное время расходуется целыми шагами, остаток переносится
 * на следующий кадр.
 */
class GameRenderer(
    private val controls: TouchControls,
    private val onFatalError: (Throwable) -> Unit
) : GLSurfaceView.Renderer {

    // --- Мир и игрок ---

    private lateinit var world: World
    private lateinit var chunkManager: ChunkManager
    private val player = PlayerState()
    private val physics = PlayerPhysics()
    private val raycastHit = RaycastHit()

    // --- Рендер ---

    private val worldRenderer = WorldRenderer()
    private val skyRenderer = SkyRenderer()
    private val uiRenderer = UiRenderer()
    private val dayNight = DayNightCycle()

    private val projection = Mat4()
    private val view = Mat4()
    private val viewProj = Mat4()
    private val fogColor = FloatArray(3)

    // --- Время ---

    private var lastFrameNanos = 0L
    private var physicsAccumulator = 0f

    // --- Настройки ---

    /** Дальность прорисовки в чанках. */
    @Volatile var renderDistance: Int = 8

    /** Вертикальный угол обзора в градусах. */
    @Volatile var fieldOfView: Float = 72f

    @Volatile var anisotropyLevel: Float = 4f

    // --- Состояние интерфейса ---

    private var screenWidth = 1
    private var screenHeight = 1
    private var displayDensity = 2f

    /** Быстрые слоты: идентификаторы блоков. */
    private val hotbar = IntArray(TouchControls.HOTBAR_SIZE)
    private var selectedSlot = 0

    /** Накопленное время удержания кнопки разрушения — для повторных ударов. */
    private var breakCooldown = 0f
    private var placeCooldown = 0f

    // --- Статистика ---

    private var fpsAccumulator = 0f
    private var fpsFrames = 0
    var currentFps: Float = 0f
        private set

    @Volatile
    var initialized = false
        private set

    /** Сид мира. Устанавливается до создания поверхности. */
    @Volatile var worldSeed: Long = System.currentTimeMillis()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            Log.i(TAG, "GL_VERSION=${GLES30.glGetString(GLES30.GL_VERSION)}")
            Log.i(TAG, "GL_RENDERER=${GLES30.glGetString(GLES30.GL_RENDERER)}")

            worldRenderer.initialize(anisotropyLevel)
            skyRenderer.initialize()
            uiRenderer.initialize()

            createWorld()

            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glClearColor(0.45f, 0.62f, 0.85f, 1f)

            lastFrameNanos = System.nanoTime()
            initialized = true
            Log.i(TAG, "Инициализация завершена, сид мира $worldSeed")
        } catch (t: Throwable) {
            Log.e(TAG, "Сбой инициализации GL", t)
            onFatalError(t)
        }
    }

    private fun createWorld() {
        world = World(seed = worldSeed)
        chunkManager = ChunkManager(world, renderDistance)

        val spawn = world.generator.findSpawnPoint()
        // Появляемся чуть выше найденной точки: чанк ещё не загружен,
        // и приземление произойдёт уже по готовой геометрии.
        player.teleport(spawn.first + 0.5, spawn.second + 2.0, spawn.third + 0.5)
        player.mode = GameMode.CREATIVE
        player.flying = false

        // Стартовый набор блоков в быстрых слотах.
        val palette = Blocks.creativePalette
        for (i in hotbar.indices) {
            hotbar[i] = if (i < palette.size) palette[i] else Blocks.AIR
        }

        // Предзагрузка ближних чанков: без неё игрок появляется в пустоте
        // и проваливается сквозь ещё не сгенерированный мир.
        chunkManager.waitForInitialChunks(player.x, player.z, radius = 2, timeoutMs = 4000)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, screenWidth, screenHeight)
        uiRenderer.resize(screenWidth, screenHeight)
        controls.layout(screenWidth, screenHeight, displayDensity)
        updateProjection()
    }

    fun setDisplayDensity(density: Float) {
        displayDensity = density.coerceAtLeast(0.75f)
    }

    private fun updateProjection() {
        val aspect = screenWidth.toFloat() / screenHeight
        // Дальняя плоскость с запасом за дальностью прорисовки: туман
        // скрывает границу раньше, чем она обрежется плоскостью.
        val far = (renderDistance + 2) * 16f
        projection.setPerspective(fieldOfView, aspect, 0.08f, far)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!initialized) return
        try {
            val now = System.nanoTime()
            var dt = (now - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = now
            // Ограничение: после сворачивания приложения dt может быть
            // в секундах, и физика за один кадр телепортировала бы игрока.
            dt = MathUtils.clamp(dt, 0f, 0.1f)

            updateStatistics(dt)
            dayNight.advance(dt)

            processInput(dt)
            stepPhysics(dt)

            chunkManager.renderDistance = renderDistance
            chunkManager.update(player.x, player.z)
            chunkManager.drainMeshes(worldRenderer)

            drawFrame(dt)

            controls.endFrame(dt)
        } catch (t: Throwable) {
            Log.e(TAG, "Сбой в кадре", t)
            onFatalError(t)
        }
    }

    private fun updateStatistics(dt: Float) {
        fpsAccumulator += dt
        fpsFrames++
        if (fpsAccumulator >= 0.5f) {
            currentFps = fpsFrames / fpsAccumulator
            fpsAccumulator = 0f
            fpsFrames = 0
        }
    }

    // ------------------------------------------------------------------
    // Ввод
    // ------------------------------------------------------------------

    private fun processInput(dt: Float) {
        synchronized(controls) {
            // Поворот камеры свайпом.
            val sensitivity = controls.lookSensitivity()
            if (controls.lookDeltaX != 0f || controls.lookDeltaY != 0f) {
                player.addLook(
                    -controls.lookDeltaX * sensitivity,
                    -controls.lookDeltaY * sensitivity
                )
            }

            // Перемещение джойстиком.
            player.setMovementInput(controls.joystick.outputY, controls.joystick.outputX)

            player.sneaking = controls.sneakButton.isPressed
            player.sprinting = controls.sprintButton.isPressed &&
                controls.joystick.outputY > 0.55f

            // Переключение полёта. Доступно только в творческом режиме.
            if (controls.flyButton.justPressed && player.mode == GameMode.CREATIVE) {
                player.flying = !player.flying
                if (player.flying) player.velocityY = 0f
            }

            if (player.flying) {
                // В полёте прыжок поднимает, приседание опускает.
                player.inputUp = when {
                    controls.jumpButton.isPressed -> 1f
                    controls.sneakButton.isPressed -> -1f
                    else -> 0f
                }
            } else {
                player.inputUp = 0f
                if (controls.jumpButton.isPressed) player.jumpRequested = true
            }

            if (controls.hotbarSelection >= 0) {
                selectedSlot = controls.hotbarSelection
            }

            handleBlockInteraction(dt)
        }
    }

    /**
     * Разрушение и установка блоков.
     *
     * Оба действия работают с задержкой между срабатываниями. Без неё
     * удержание кнопки разрушения сносило бы по блоку за кадр — при 60 кадрах
     * это шестьдесят блоков в секунду, то есть тоннель прокапывается быстрее,
     * чем игрок успевает понять, где находится.
     */
    private fun handleBlockInteraction(dt: Float) {
        breakCooldown -= dt
        placeCooldown -= dt

        val hasTarget = BlockRaycaster.castFromPlayer(world, player, REACH_DISTANCE, raycastHit)

        if (controls.breakButton.isPressed && hasTarget && breakCooldown <= 0f) {
            val id = raycastHit.blockId
            if (Blocks.get(id).breakable) {
                val old = world.getBlock(raycastHit.blockX, raycastHit.blockY, raycastHit.blockZ)
                if (world.setBlock(raycastHit.blockX, raycastHit.blockY, raycastHit.blockZ, Blocks.AIR)) {
                    chunkManager.onBlockChanged(
                        raycastHit.blockX, raycastHit.blockY, raycastHit.blockZ,
                        old, Blocks.AIR
                    )
                }
            }
            breakCooldown = BREAK_INTERVAL
        }

        if (controls.placeButton.isPressed && hasTarget && placeCooldown <= 0f) {
            val blockId = hotbar[selectedSlot]
            if (blockId != Blocks.AIR) {
                val px = raycastHit.placeX
                val py = raycastHit.placeY
                val pz = raycastHit.placeZ
                // Не даём замуровать самого себя: проверяем пересечение
                // с коробкой игрока. Без этого блок ставится прямо в ноги,
                // и игрок оказывается внутри геометрии.
                if (!intersectsPlayer(px, py, pz)) {
                    val old = world.getBlock(px, py, pz)
                    if (world.placeBlock(px, py, pz, blockId)) {
                        chunkManager.onBlockChanged(px, py, pz, old, blockId)
                    }
                }
            }
            placeCooldown = PLACE_INTERVAL
        }
    }

    private fun intersectsPlayer(bx: Int, by: Int, bz: Int): Boolean {
        val half = player.width * 0.5
        val minX = player.x - half
        val maxX = player.x + half
        val minZ = player.z - half
        val maxZ = player.z + half
        val minY = player.y
        val maxY = player.y + player.height
        return bx + 1.0 > minX && bx.toDouble() < maxX &&
            by + 1.0 > minY && by.toDouble() < maxY &&
            bz + 1.0 > minZ && bz.toDouble() < maxZ
    }

    // ------------------------------------------------------------------
    // Физика
    // ------------------------------------------------------------------

    private fun stepPhysics(dt: Float) {
        physicsAccumulator += dt
        var steps = 0
        while (physicsAccumulator >= PHYSICS_STEP && steps < MAX_PHYSICS_STEPS) {
            physics.step(player, world, PHYSICS_STEP)
            physicsAccumulator -= PHYSICS_STEP
            steps++
        }
        // Если накопилось слишком много, остаток отбрасываем: догонять
        // просадку кадров лавиной шагов физики — верный путь к тому, чтобы
        // просадка стала постоянной.
        if (steps >= MAX_PHYSICS_STEPS) physicsAccumulator = 0f

        player.updateEyeHeight(dt)
    }

    // ------------------------------------------------------------------
    // Отрисовка
    // ------------------------------------------------------------------

    private fun drawFrame(dt: Float) {
        dayNight.horizonColor(fogColor)

        GLES30.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Матрица вида строится относительно камеры: наблюдатель всегда
        // в начале координат, а геометрия смещается на разность позиций.
        view.setFirstPersonView(0f, 0f, 0f, player.yaw, player.pitch)
        Mat4.multiply(projection, view, viewProj)

        val dayFactor = dayNight.sunLightFactor()
        val visibility = (renderDistance * 16f)
        // Дождь и подводное положение сокращают видимость — туман
        // сгущается ближе к игроку.
        val fogEnd = when {
            player.submerged -> 26f
            else -> visibility * (1f - dayNight.rainIntensity * 0.42f)
        }
        val fogStart = fogEnd * 0.55f

        worldRenderer.renderSolid(
            viewProj, player.eyeX, player.eyeY, player.eyeZ,
            dayFactor, dayNight.elapsedSeconds, fogColor, fogStart, fogEnd
        )

        skyRenderer.render(viewProj, dayNight, player.eyeY)

        worldRenderer.renderTranslucent(
            viewProj, player.eyeX, player.eyeY, player.eyeZ,
            dayFactor, dayNight.elapsedSeconds, fogColor, fogStart, fogEnd
        )

        drawInterface()
    }

    private fun drawInterface() {
        uiRenderer.begin()

        // Подводная и лавовая заливка экрана — иначе непонятно, что игрок
        // под водой, ведь модели головы у него нет.
        if (player.submerged) {
            val tint = if (player.inLava) 0x90D04010.toInt() else 0x702C6CB5
            uiRenderer.rect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), tint)
        }

        controls.drawCrosshair(uiRenderer)
        synchronized(controls) {
            controls.draw(
                uiRenderer, hotbar, selectedSlot,
                player.flying, player.sneaking, player.sprinting
            )
        }

        uiRenderer.flush(worldRenderer.atlasTexture())
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /**
     * Сбрасывает отметку времени кадра.
     *
     * Вызывается при паузе и возобновлении. Без этого первый кадр после
     * возвращения в игру получил бы dt, равный всему времени в фоне,
     * и физика за один шаг протащила бы игрока через полмира. Ограничение
     * dt сверху защищает от того же, но сброс отметки честнее: игра
     * продолжается с того места, где остановилась, а не «доигрывает»
     * пропущенное время.
     */
    fun resetFrameClock() {
        lastFrameNanos = System.nanoTime()
    }

    fun shutdown() {
        if (::chunkManager.isInitialized) chunkManager.shutdown()
        worldRenderer.dispose()
        skyRenderer.dispose()
        uiRenderer.dispose()
    }

    /** Строка отладочной статистики. */
    fun debugLine(): String {
        if (!initialized) return "инициализация…"
        return buildString {
            append("%.0f к/с | ".format(currentFps))
            append("XYZ %.1f %.1f %.1f | ".format(player.x, player.y, player.z))
            append("секций ${worldRenderer.visibleSections}/")
            append("${worldRenderer.visibleSections + worldRenderer.culledSections} | ")
            append("треуг. ${worldRenderer.drawnTriangles} | ")
            append("видеопамять %.1f МБ | ".format(worldRenderer.videoMemoryMb()))
            append(if (::chunkManager.isInitialized) chunkManager.statsLine() else "")
        }
    }

    companion object {
        private const val TAG = "VoxelForge/Renderer"

        /** Шаг физики: 60 раз в секунду. */
        private const val PHYSICS_STEP = 1f / 60f

        /** Предел шагов физики за кадр — защита от «спирали смерти». */
        private const val MAX_PHYSICS_STEPS = 5

        /** Дальность взаимодействия с блоками в блоках. */
        private const val REACH_DISTANCE = 5.5f

        private const val BREAK_INTERVAL = 0.22f
        private const val PLACE_INTERVAL = 0.20f
    }
}
