package com.spice.nativeapp.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/**
 * GelGenie U-Net 模型管理。下载策略与原版一致：模型不打包进 APK，
 * 首次使用时按需从 HuggingFace 下载到应用私有目录，校验 SHA-256 后本地缓存。
 */
object ModelStore {

    const val MODEL_URL =
        "https://huggingface.co/mattaq/GelGenie-Universal-FineTune-May-2024/resolve/main/onnx_checkpoints/unet_dec_21_finetune_epoch_590.onnx"
    const val MODEL_SHA256 = "df9e39157eda6e1d1f034f5ca75fb2b8d0965189c026f00504a80c2743bb6336"

    fun modelFile(ctx: Context): File = File(ctx.filesDir, "models/gelgenie_unet.onnx")

    data class ModelStatus(val ok: Boolean, val sizeBytes: Long, val shaMatch: Boolean, val error: String?)

    fun status(ctx: Context): ModelStatus {
        val f = modelFile(ctx)
        if (!f.exists()) return ModelStatus(false, 0L, false, "模型未下载")
        val sha = runCatching { sha256(f) }.getOrNull()
        return ModelStatus(true, f.length(), sha == MODEL_SHA256, if (sha == null) "校验异常" else null)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 下载模型（含重定向跟随与进度回调）。返回 Result<File>。 */
    fun download(ctx: Context, onProgress: (done: Long, total: Long) -> Unit, onStage: (String) -> Unit): Result<File> {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val target = modelFile(ctx)
        val tmp = File(dir, "gelgenie_unet.onnx.part")
        return runCatching {
            onStage("连接 HuggingFace…")
            var url = URL(MODEL_URL)
            var conn: HttpURLConnection? = null
            repeat(5) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "SPICE-Native")
                }
                val code = conn!!.responseCode
                if (code in 300..399) {
                    url = URL(conn!!.getHeaderField("Location") ?: error("缺少重定向地址"))
                    conn!!.disconnect()
                } else {
                    conn!!.connect()
                    return@repeat
                }
            }
            conn!!.connect()
            val total = conn!!.contentLengthLong.coerceAtLeast(0)
            onStage("下载模型权重…")
            tmp.outputStream().use { out ->
                val input = conn!!.inputStream
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            conn!!.disconnect()
            onStage("校验 SHA-256…")
            val actual = sha256(tmp)
            if (actual != MODEL_SHA256) {
                tmp.delete()
                error("SHA-256 校验失败：$actual")
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            target
        }.onFailure { tmp.delete() }
    }
}
