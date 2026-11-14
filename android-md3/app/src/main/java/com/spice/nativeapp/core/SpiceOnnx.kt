package com.spice.nativeapp.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * SPICE 预训练蛋白折叠 ONNX 推理（对齐 main src-tauri/src/onnx.rs 一一移植）。
 *
 * 导出图 `spice_infer_fixed.onnx` 三输入：
 *   tokens int64 [B,L]（1..20=AA，21=未知）、env f32 [B,3]（归一化）、mask f32 [B,L]；
 * 六输出：coords [B,L,3]、dist_logits [B,L,L,48]、mutation [B,L,20]、
 *   coords_mut [B,L,3]、env_offset [B,2]、conf [B,2]。
 *
 * 后处理与 main 相同：dist_logits 末维 48 类 softmax → 期望距离图 + 接触概率
 * （bin 中心几何 linspace(3,48,47) 作边、接触 bin=边<8Å 共 6 个）、mutation softmax。
 *
 * 模型本体不在仓库（原版要求本地 export_onnx.py 导出）：
 * 路径解析顺序与 main default_model_candidates 一致 —— 显式路径 > 默认位置。
 */
object SpiceOnnx {

    private const val AA = "ACDEFGHIKLMNPQRSTVWY"
    private const val MAX_LEN = 1024
    private const val DEFAULT_FILE = "spice_infer_fixed.onnx"

    data class FoldResult(
        val length: Int,
        val coords: FloatArray,          // [L,3] Cα trace（Head A）
        val coordsMut: FloatArray,       // [L,3] 突变参考轨迹（Head B'）
        val distExp: FloatArray,         // [L,L] 期望 Cα-Cα 距离
        val pContact: FloatArray,       // [L,L] 接触概率
        val mutationSoftmax: FloatArray, // [L,20]
        val envOffset: FloatArray,       // [2] ΔpH, ΔT
        val conf: FloatArray,            // [2] 双路置信度
        val modelPath: String,
        val runMs: Double
    )

    class ModelMissing(msg: String) : Exception(msg)

    @Volatile private var ortEnvRef: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionPath: String? = null

    /** 归一化环境向量（main normalize_env）。 */
    fun normalizeEnv(ph: Double, tempK: Double, ionicM: Double): FloatArray {
        val p = ((ph - 0.0) / 14.0).coerceIn(0.0, 1.0)
        val t = ((tempK - 150.0) / (400.0 - 150.0)).coerceIn(0.0, 1.0)
        val ionic = ionicM.coerceIn(1e-3, 1.0)
        val i = (Math.log10(ionic) / Math.log10(1e-3)).coerceIn(0.0, 1.0)
        return floatArrayOf(p.toFloat(), t.toFloat(), i.toFloat())
    }

    /** 1 字母序列 → token（main seq_to_tokens：0=padding 1..20=AA 21=unknown）。 */
    fun seqToTokens(seq: String): LongArray =
        LongArray(seq.length) { i -> AA.indexOf(seq[i]).let { if (it >= 0) (it + 1).toLong() else 21L } }

    fun findModel(ctx: Context, explicit: String?): File {
        explicit?.takeIf { it.isNotBlank() }?.let { f -> File(f).takeIf { it.exists() }?.let { return it } }
        // 安卓侧默认候选（对齐 main default_model_candidates 语义，映射到应用私有目录）
        File(ctx.filesDir, "models/$DEFAULT_FILE").takeIf { it.exists() }?.let { return it }
        File(ctx.getExternalFilesDir(null), DEFAULT_FILE).takeIf { it.exists() }?.let { return it }
        throw ModelMissing(
            if (explicit.isNullOrBlank()) com.spice.nativeapp.core.I18n.tf("未找到 spice_infer_fixed.onnx（默认位置 models/{v1}）", DEFAULT_FILE)
            else com.spice.nativeapp.core.I18n.tf("显式路径无模型：{v1}", explicit)
        )
    }

    fun status(ctx: Context, explicit: String?): Pair<Boolean, String> = try {
        val f = findModel(ctx, explicit)
        true to f.absolutePath
    } catch (e: ModelMissing) {
        false to (e.message ?: com.spice.nativeapp.core.I18n.t("模型缺失"))
    }

    private fun ensureSession(path: String): OrtSession {
        session?.takeIf { sessionPath == path }?.let { return it }
        synchronized(this) {
            session?.takeIf { sessionPath == path }?.let { return it }
            val e = ortEnvRef ?: OrtEnvironment.getEnvironment().also { ortEnvRef = it }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val s = e.createSession(path, opts)
            session?.close()
            session = s
            sessionPath = path
            return s
        }
    }

    /** 全头推理（main onnx.rs infer，batch 1）。 */
    fun fold(ctx: Context, seq: String, env: ProteinBiophysics.Env, explicitPath: String? = null): FoldResult {
        val l = seq.length
        if (l == 0) error("empty sequence")
        if (l > MAX_LEN) error(com.spice.nativeapp.core.I18n.tf("序列过长（{v1} > {v2} 位置编码上限）", l, MAX_LEN))
        val modelFile = findModel(ctx, explicitPath)

        val tokens = seqToTokens(seq)
        val envn = normalizeEnv(env.ph, env.tempK, env.ionicStrengthM)
        val mask = FloatArray(l) { 1f }

        val ortEnv = ortEnvRef ?: OrtEnvironment.getEnvironment().also { ortEnvRef = it }
        val sess = ensureSession(modelFile.absolutePath)
        val start = System.currentTimeMillis()

        val tokensT = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(tokens), longArrayOf(1, l.toLong()))
        val envT = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(envn), longArrayOf(1, 3))
        val maskT = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(mask), longArrayOf(1, l.toLong()))
        val out = try {
            sess.run(mapOf("tokens" to tokensT, "env" to envT, "mask" to maskT))
        } finally {
            tokensT.close(); envT.close(); maskT.close()
        }
        val coords = flat(out, "coords")                    // [L,3]
        val distLogits = flat(out, "dist_logits")            // [L,L,48]
        val mutation = flat(out, "mutation")                 // [L,20]
        val coordsMut = flat(out, "coords_mut")              // [L,3]
        val envOffset = flat(out, "env_offset")              // [2]
        val conf = flat(out, "conf")                         // [2]
        out.close()
        val runMs = (System.currentTimeMillis() - start).toDouble()

        // ── distogram 后处理（main distogram_geometry + softmax）──
        val nEdge = 47
        val edges = FloatArray(nEdge) { i -> (3.0f + (48.0f - 3.0f) * i / (nEdge - 1).toFloat()) }
        val bounds = FloatArray(nEdge + 2)
        bounds[0] = 0f; bounds[nEdge + 1] = 72f; System.arraycopy(edges, 0, bounds, 1, nEdge)
        val centers = FloatArray(48) { k -> (bounds[k] + bounds[k + 1]) / 2f }
        val contactBins = edges.count { it < 8.0f }
        val contactMask = FloatArray(48) { k -> if (k < contactBins) 1f else 0f }

        val distExp = FloatArray(l * l)
        val pContact = FloatArray(l * l)
        for (i in 0 until l) for (j in 0 until l) {
            val base = (i * l + j) * 48
            var mx = Float.NEGATIVE_INFINITY
            for (k in 0 until 48) mx = maxOf(mx, distLogits[base + k])
            var sum = 0f
            val p = FloatArray(48)
            for (k in 0 until 48) { val ex = kotlin.math.exp(distLogits[base + k] - mx); p[k] = ex; sum += ex }
            var dexp = 0f; var pc = 0f
            for (k in 0 until 48) { val pr = p[k] / sum; dexp += pr * centers[k]; pc += pr * contactMask[k] }
            distExp[i * l + j] = dexp
            pContact[i * l + j] = pc
        }

        // ── mutation softmax [L,20] ──
        val mutSoft = FloatArray(l * 20)
        for (i in 0 until l) {
            val base = i * 20
            var mx = Float.NEGATIVE_INFINITY
            for (k in 0 until 20) mx = maxOf(mx, mutation[base + k])
            var sum = 0f
            val p = FloatArray(20)
            for (k in 0 until 20) { val ex = kotlin.math.exp(mutation[base + k] - mx); p[k] = ex; sum += ex }
            for (k in 0 until 20) mutSoft[base + k] = p[k] / sum
        }

        return FoldResult(
            length = l, coords = coords, coordsMut = coordsMut,
            distExp = distExp, pContact = pContact, mutationSoftmax = mutSoft,
            envOffset = envOffset.copyOf(), conf = conf.copyOf(),
            modelPath = modelFile.absolutePath, runMs = runMs
        )
    }

    private fun flat(out: OrtSession.Result, name: String): FloatArray {
        val t = out.get(name) as OnnxTensor
        val fb = t.floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        return arr
    }

    /** 释放会话（设置面板删除/换路径时调用）。 */
    fun unload() {
        synchronized(this) {
            session?.close()
            session = null
            sessionPath = null
        }
    }
}
