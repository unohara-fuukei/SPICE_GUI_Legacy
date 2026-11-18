package com.spice.gui

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import java.io.File
import kotlin.system.exitProcess

class MainActivity : TauriActivity() {
  companion object {
    private const val PREFS = "spice_prefs"
    private const val KEY_ORIENTATION_LOCK = "orientation_lock"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    installCrashReporter()
    applyOrientationLock(readOrientationLock())
  }

  /**
   * Edge-toEdge keeps the content under the status bar. The legacy WebView
   * cannot rely on CSS `env(safe-area-inset-top)`, so we reserve a native
   * spacer strip of the status-bar height above the WebView instead of
   * injecting scripts into the page. The spacer matches the topbar surface
   * color; the WebView fills whatever space remains.
   */
  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    webView.addJavascriptInterface(OrientationBridge(), "SpiceNative")
    applyTopSafeArea(webView)
  }

  private fun readOrientationLock(): Boolean =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getBoolean(KEY_ORIENTATION_LOCK, false)

  private fun applyOrientationLock(locked: Boolean) {
    requestedOrientation = if (locked) {
      ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
      ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
  }

  /**
   * Minimal web->native bridge. The web layer asks the activity to lock or
   * release screen rotation; the choice is persisted so it survives a restart.
   * On desktop/browser builds this object is absent and the web layer no-ops.
   */
  inner class OrientationBridge {
    @JavascriptInterface
    fun setOrientationLock(locked: Boolean) {
      getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ORIENTATION_LOCK, locked)
        .apply()
      runOnUiThread { applyOrientationLock(locked) }
    }
  }

  private fun statusBarHeightPx(): Int {
    val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resId != 0) resources.getDimensionPixelSize(resId) else 0
  }

  private fun applyTopSafeArea(webView: WebView) {
    val barH = statusBarHeightPx()
    if (barH <= 0) return
    webView.post {
      val parent = webView.parent as? ViewGroup ?: return@post
      val originalParams = webView.layoutParams ?: ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      parent.removeView(webView)

      val holder = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = originalParams
      }
      val spacer = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          barH
        )
        // Matches --pix-bg-2 (topbar surface) in the bundled dark palette.
        setBackgroundColor(0xFF121624.toInt())
      }
      holder.addView(spacer)
      holder.addView(
        webView,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
      )
      parent.addView(holder)
    }
  }

  /**
   * SPICE crash diagnostics: persist the last uncaught exception (thread +
   * stack trace) into the app's private files dirs. The web layer surfaces it
   * after the next launch via the `get_last_crash` Tauri command, so field
   * reports can include the exact native stack trace.
   */
  private fun installCrashReporter() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        val report = buildString {
          append("time=").append(System.currentTimeMillis()).append('\n')
          append("thread=").append(thread.name).append('\n')
          append(Log.getStackTraceString(throwable))
        }
        val candidates = listOfNotNull(
          getExternalFilesDir(null),
          filesDir
        )
        for (dir in candidates) {
          try {
            File(dir, "last_crash.txt").writeText(report)
            break
          } catch (_: Exception) {
            // try the next dir
          }
        }
      } catch (_: Exception) {
        // never rethrow from the crash handler itself
      }
      android.os.Process.killProcess(android.os.Process.myPid())
      exitProcess(10)
    }
  }
}
