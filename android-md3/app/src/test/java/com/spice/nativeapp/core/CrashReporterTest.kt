package com.spice.nativeapp.core

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CrashReporter（对齐 main Android `installCrashReporter` + Rust `get_last_crash`）
 * 落盘/读即删/清除语义测试。真实 handler 会 killProcess + exitProcess，
 * 测试内不触发未捕获异常路径，只验证处理器安装与文件契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CrashReporterTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        CrashReporter.clear(ctx)
    }

    @Test
    fun `无落盘报告时 read 返回 null`() {
        CrashReporter.clear(ctx)
        assertNull(CrashReporter.read(ctx))
    }

    @Test
    fun `read 读回非空且读即删，第二次 read 为 null`() {
        File(ctx.filesDir, "last_crash.txt")
            .writeText("time=1700000000000\nthread=main\njava.lang.IllegalStateException: boom\n\tat A.b(Crash.kt:1)")
        val report = CrashReporter.read(ctx)
        assertTrue(report != null && report.contains("IllegalStateException"))
        // 对齐 Rust get_last_crash：命中后立即删除落盘文件
        assertNull(CrashReporter.read(ctx))
    }

    @Test
    fun `空白报告回落 null`() {
        File(ctx.filesDir, "last_crash.txt").writeText("   \n ")
        assertNull(CrashReporter.read(ctx))
    }

    @Test
    fun `install 安装默认未捕获异常处理器且不抛异常`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashReporter.install(ctx)
            assertTrue(Thread.getDefaultUncaughtExceptionHandler() != null)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            CrashReporter.clear(ctx)
        }
    }
}
