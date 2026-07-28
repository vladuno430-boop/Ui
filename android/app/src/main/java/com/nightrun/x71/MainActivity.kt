package com.nightrun.x71

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

/**
 * Thin native shell around the WebGL game.
 *
 * The game is served through [WebViewAssetLoader] rather than a `file://` URL:
 * ES modules, the service worker and `localStorage` all need a real HTTPS
 * origin, and `https://appassets.androidplatform.net/` gives us one that maps
 * straight onto `assets/game/`.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        goImmersive()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/game/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                // The page handles its own layout; never let the WebView
                // second-guess the viewport on a phone.
                useWideViewPort = true
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                textZoom = 100
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
            webChromeClient = WebChromeClient()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }

        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/game/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back is "pause / go up a menu" inside the game, not "quit".
                webView.evaluateJavascript(
                    """
                    (function () {
                      if (!window.game) return 'exit';
                      if (window.game.mode === 'run') { window.game.pause(true); return 'handled'; }
                      if (window.game.mode === 'paused') { window.game.pauseAction('quit'); return 'handled'; }
                      if (window.game.ui && window.game.ui.current !== 'title') {
                        window.game.navigate('title');
                        return 'handled';
                      }
                      return 'exit';
                    })();
                    """.trimIndent(),
                ) { result ->
                    if (result.contains("exit")) finish()
                }
            }
        })
    }

    private fun goImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.evaluateJavascript(
            "window.game && window.game.mode === 'run' && window.game.pause(true);", null,
        )
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
