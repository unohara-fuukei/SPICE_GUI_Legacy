package com.spice.gui

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import java.io.File
import kotlin.system.exitProcess

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    installCrashReporter()
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
