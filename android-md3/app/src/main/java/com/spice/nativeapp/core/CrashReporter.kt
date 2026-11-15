package com.spice.nativeapp.core

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * 上次运行的原生崩溃报告（1:1 对齐 main `src-tauri/gen/android/.../MainActivity.kt`
 * 的 `installCrashReporter` + `src-tauri/src/diagnostics.rs` 的 `get_last_crash`，
 * 以及 `src/routes/+layout.svelte` 的 `lastCrash` 浮层状态）。
 *
 * main Android 原生层：安装默认 `UncaughtExceptionHandler`，把 `time=<毫秒>\n
 * thread=<名>\n<堆栈>` 写入 external files dir（回退 internal filesDir），随后
 * `killProcess` + `exitProcess(10)`；重启后 web 层经 Tauri 命令读取，Rust 端
 * **读即删**落盘文件，浮层关闭仅清内存态。Android 对等实现沿用同一契约：
 * 落盘目录候选、读即清除、堆栈格式一致；不另设原生崩溃对话框替代链路。
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    private fun candidates(ctx: Context): List<File> =
        listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir)

    /**
     * 在 Activity `onCreate` 最早期安装：未捕获异常时先落盘堆栈，再结束进程
     * （对齐 main Android handler 的 killProcess + exitProcess(10)）。
     */
    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildString {
                    append("time=").append(System.currentTimeMillis()).append('\n')
                    append("thread=").append(thread.name).append('\n')
                    append(Log.getStackTraceString(throwable))
                }
                for (dir in candidates(appCtx)) {
                    try {
                        File(dir, FILE_NAME).writeText(report)
                        break
                    } catch (_: Exception) {
                        // 尝试下一个目录候选
                    }
                }
            } catch (_: Exception) {
                // 崩溃处理器自身不得再次抛出
            }
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    /**
     * 读取上次运行的崩溃报告；对齐 Rust `get_last_crash`：命中后**立即删除**落盘
     * 文件（下次启动不再重复弹出）。文件缺失、读取失败或内容空白返回 null。
     */
    fun read(ctx: Context): String? {
        for (dir in candidates(ctx)) {
            val f = File(dir, FILE_NAME)
            if (f.exists()) {
                val report = runCatching { f.readText().trim().ifEmpty { null } }.getOrNull()
                runCatching { f.delete() }
                if (report != null) return report
            }
        }
        return null
    }

    /** 清除已读崩溃报告（对齐 main 关闭浮层后置 `lastCrash = null` 的内存态，同步删盘） */
    fun clear(ctx: Context) {
        candidates(ctx).forEach { runCatching { File(it, FILE_NAME).delete() } }
    }
}
