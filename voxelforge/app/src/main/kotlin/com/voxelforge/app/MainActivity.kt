package com.voxelforge.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.voxelforge.app.input.TouchControls

/**
 * Точка входа игры.
 *
 * ## Об устройстве этого класса
 *
 * Activity намеренно осталась тонкой: её задача — создать поверхность
 * OpenGL, передать в неё события касания и корректно отработать жизненный
 * цикл. Вся игра живёт в [GameRenderer].
 *
 * Класс наследуется от **`android.app.Activity`, а не от `AppCompatActivity`**.
 * Причина практическая: игра не использует ни одного элемента библиотеки
 * совместимости — весь интерфейс рисуется в OpenGL. Наследование от
 * AppCompat потащило бы за собой инициализацию тем, менеджера ресурсов
 * и всей библиотеки на старте, то есть лишние сотни миллисекунд запуска
 * и целый класс возможных сбоев инициализации ради нулевой пользы.
 *
 * ## Проверка поддержки OpenGL ES 3.0
 *
 * Она выполняется до создания поверхности. Без неё устройство без
 * поддержки получило бы чёрный экран и молчаливое падение при первом же
 * вызове функции третьей версии — самый неинформативный из возможных
 * исходов. Явная проверка даёт понятное сообщение.
 */
class MainActivity : Activity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: GameRenderer
    private val controls = TouchControls()

    private var fatalErrorShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(applicationContext)

        try {
            // Экран не должен гаснуть во время игры: игрок может долго
            // рассматривать постройку, не касаясь экрана.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            enterImmersiveMode()

            if (!isOpenGlEs3Supported()) {
                CrashReporter.showOnScreen(
                    this,
                    "Устройство не поддерживается",
                    "VoxelForge требует OpenGL ES 3.0.\n\n" +
                        "Обнаруженная версия: ${detectedGlEsVersion()}\n" +
                        "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                )
                return
            }

            renderer = GameRenderer(controls) { throwable -> onFatalError(throwable) }
            renderer.setDisplayDensity(resources.displayMetrics.density)
            renderer.worldSeed = System.currentTimeMillis()

            surfaceView = GLSurfaceView(this).apply {
                setEGLContextClientVersion(3)
                // Глубина 24 бита: при 16 битах на дальности прорисовки
                // в 8 чанков начинается z-fighting на дальних поверхностях.
                setEGLConfigChooser(8, 8, 8, 0, 24, 0)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            setContentView(surfaceView)

            // Сообщаем о сбое прошлого запуска, если он был: иначе
            // повторяющаяся ошибка так и останется незамеченной.
            CrashReporter.lastReport(this)?.let {
                Log.w(TAG, "Отчёт о прошлом сбое:\n$it")
                CrashReporter.clearLastReport(this)
            }
        } catch (t: Throwable) {
            onFatalError(t)
        }
    }

    /**
     * Проверка поддержки OpenGL ES 3.0 через ActivityManager.
     *
     * Именно этот способ, а не разбор строки GL_VERSION: последняя доступна
     * только после создания контекста, то есть уже поздно — если версия
     * не поддерживается, контекст не создастся.
     */
    private fun isOpenGlEs3Supported(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.deviceConfigurationInfo.reqGlEsVersion >= 0x00030000
    }

    private fun detectedGlEsVersion(): String {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.deviceConfigurationInfo.glEsVersion ?: "неизвестно"
    }

    /**
     * Полноэкранный режим со скрытыми системными панелями.
     *
     * Панели скрываются «липко»: свайп от края показывает их временно,
     * после чего они прячутся сами. Обычное скрытие возвращало бы панели
     * навсегда при первом же случайном свайпе у нижнего края — а именно там
     * находятся кнопки управления игрой.
     */
    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /**
     * События касания приходят в потоке интерфейса, а читаются в потоке
     * отрисовки. Синхронизация по объекту управления — самый дешёвый
     * корректный вариант: захват происходит несколько раз за кадр
     * и удерживается микросекунды.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fatalErrorShown) return super.onTouchEvent(event)
        synchronized(controls) {
            return controls.onTouchEvent(event)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::surfaceView.isInitialized) {
            surfaceView.onResume()
            if (::renderer.isInitialized) renderer.resetFrameClock()
        }
        enterImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        if (::surfaceView.isInitialized) surfaceView.onPause()
        if (::renderer.isInitialized) renderer.resetFrameClock()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::renderer.isInitialized) renderer.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * Показывает сбой на экране вместо молчаливого закрытия.
     *
     * Флаг [fatalErrorShown] защищает от лавины: сбой в потоке отрисовки
     * повторяется на каждом кадре, и без него экран мигал бы сообщениями
     * шестьдесят раз в секунду.
     */
    private fun onFatalError(throwable: Throwable) {
        if (fatalErrorShown) return
        fatalErrorShown = true
        Log.e(TAG, "Неустранимая ошибка", throwable)

        runOnUiThread {
            runCatching { if (::surfaceView.isInitialized) surfaceView.onPause() }
            CrashReporter.showOnScreen(
                this,
                "Ошибка запуска VoxelForge",
                CrashReporter.buildReport(Thread.currentThread(), throwable)
            )
        }
    }

    companion object {
        private const val TAG = "VoxelForge/Main"
    }
}
