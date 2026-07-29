package com.voxelforge.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Перехват и показ необработанных сбоев.
 *
 * ## Зачем это нужно именно здесь
 *
 * На устройстве пользователя необработанное исключение выглядит как молчаливо
 * закрывшееся приложение. Ни причины, ни места — «не запускается». Получить
 * стек вызовов можно только через logcat, для чего нужен компьютер, кабель
 * и режим разработчика, то есть на практике — никак.
 *
 * Этот класс делает две вещи: сохраняет стек вызовов в файл внутри каталога
 * приложения и показывает его на экране. Пользователь может переслать текст,
 * и диагностика занимает минуту вместо переписки вслепую.
 *
 * Отдельно перехватываются сбои **в потоке отрисовки**: `GLSurfaceView`
 * выполняет отрисовку в своём потоке, и исключение в нём убивает процесс
 * так же незаметно.
 */
object CrashReporter {

    private const val TAG = "VoxelForge/Crash"
    private const val CRASH_FILE = "last_crash.txt"

    /** Устанавливает глобальный обработчик. Вызывается первым делом в Application/Activity. */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildReport(thread, throwable)
                Log.e(TAG, report)
                saveReport(context, report)
            } catch (ignored: Throwable) {
                // Обработчик сбоев не имеет права сам упасть: иначе
                // потеряется и исходное исключение.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun buildReport(thread: Thread, throwable: Throwable): String {
        val stack = StringWriter()
        throwable.printStackTrace(PrintWriter(stack))
        return buildString {
            appendLine("VoxelForge — сбой")
            appendLine("Время: ${java.util.Date()}")
            appendLine("Поток: ${thread.name}")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            append(stack.toString())
        }
    }

    private fun saveReport(context: Context, report: String) {
        runCatching {
            File(context.filesDir, CRASH_FILE).writeText(report)
        }
    }

    /** Читает отчёт о прошлом сбое, если он был. */
    fun lastReport(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE)
        return if (file.exists()) file.readText() else null
    }

    fun clearLastReport(context: Context) {
        runCatching { File(context.filesDir, CRASH_FILE).delete() }
    }

    /**
     * Заменяет содержимое окна текстом ошибки.
     *
     * Намеренно строится из кода без ресурсов разметки: сбой мог произойти
     * как раз из-за ресурсов, и попытка раздуть разметку привела бы
     * ко второму исключению внутри обработчика первого.
     */
    fun showOnScreen(activity: Activity, title: String, details: String) {
        activity.runOnUiThread {
            runCatching {
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#12161C"))
                    setPadding(32, 48, 32, 32)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                root.addView(TextView(activity).apply {
                    text = title
                    setTextColor(Color.parseColor("#FF7B72"))
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.START
                })

                val scroll = ScrollView(activity)
                scroll.addView(TextView(activity).apply {
                    text = details
                    setTextColor(Color.parseColor("#C9D1D9"))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, 24, 0, 0)
                    setTextIsSelectable(true)
                })
                root.addView(scroll)

                activity.setContentView(root)
            }
        }
    }
}
