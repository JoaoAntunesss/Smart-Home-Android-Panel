package pt.antunes.smarthub

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(0xFF1A1B21.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // guarda o estado das luzes (localStorage)
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.addJavascriptInterface(TuyaBridge(this, web), "TuyaNative")
        web.addJavascriptInterface(com.antunes.remote.TvBridge(this, web), "TvNative")
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl("file:///android_asset/index.html")
        else web.restoreState(savedInstanceState)
        fs = getSharedPreferences("hub", MODE_PRIVATE).getBoolean("fs", true)
        web.addJavascriptInterface(HubBridge(), "HubNative")
        applyFs()
    }

    private var fs = true

    private fun applyFs() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (fs) (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFs()
    }

    private fun tellJs(ok: Boolean, msg: String) {
        web.post { web.evaluateJavascript("window.__snap && window.__snap(" + ok + "," + org.json.JSONObject.quote(msg) + ")", null) }
    }

    private fun saveShot(bmp: Bitmap) {
        Thread {
            try {
                val name = "SmartHub_" + System.currentTimeMillis() + ".jpg"
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartHub")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    tellJs(true, "Guardada em Fotografias › SmartHub")
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                    val f = File(dir, name)
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    tellJs(true, "Guardada em " + f.absolutePath)
                }
            } catch (e: Exception) { tellJs(false, e.message ?: "erro") }
        }.start()
    }

    inner class HubBridge {
        /** Fotografa a zona do ecrã onde está o vídeo (x, y, largura, altura em pixels CSS). */
        @JavascriptInterface fun snapshot(x: Int, y: Int, w: Int, h: Int) {
            runOnUiThread {
                try {
                    val d = resources.displayMetrics.density
                    val loc = IntArray(2); web.getLocationInWindow(loc)
                    val ww = (w * d).toInt().coerceAtLeast(1); val hh = (h * d).toInt().coerceAtLeast(1)
                    val l = loc[0] + (x * d).toInt(); val t = loc[1] + (y * d).toInt()
                    val bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
                    if (Build.VERSION.SDK_INT >= 26) {
                        PixelCopy.request(window, Rect(l, t, l + ww, t + hh), bmp, { res ->
                            if (res == PixelCopy.SUCCESS) saveShot(bmp) else tellJs(false, "Não foi possível capturar")
                        }, Handler(Looper.getMainLooper()))
                    } else {
                        val c = Canvas(bmp); c.translate(-l.toFloat(), -t.toFloat()); window.decorView.draw(c); saveShot(bmp)
                    }
                } catch (e: Exception) { tellJs(false, e.message ?: "erro") }
            }
        }

        @JavascriptInterface fun isFullscreen(): Boolean = fs
        @JavascriptInterface fun setFullscreen(on: Boolean) {
            fs = on
            getSharedPreferences("hub", MODE_PRIVATE).edit().putBoolean("fs", on).apply()
            runOnUiThread { applyFs() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); super.onPause() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
