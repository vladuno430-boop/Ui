package com.voxelforge.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Временная точка входа первого этапа.
 *
 * На этом шаге проверяется только то, что модуль :app корректно собирается
 * и видит :core. Полноценная реализация с GLSurfaceView появится на этапе,
 * посвящённом рендеру.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "VoxelForge" })
    }
}
