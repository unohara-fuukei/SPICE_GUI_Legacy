package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * SPICE 蛋白状态包 (.spicep) —— 对齐 main 分支 protein 页的 buildSpicepPayload / loadSpicep：
 * JSON 结构 {format: SPICE_PROTEIN, version, meta, environment, coordinates,
 * simulation_history, biophysical_metrics}。
 */
object Spicep {

    const val FORMAT = "SPICE_PROTEIN"
    const val VERSION = "1.0"

    data class EnvState(val ph: Double, val tempK: Double, val pressureBar: Double, val ionicStrengthM: Double)

    data class Payload(
        val sequence: String,
        val model: String,
        val createdAt: String,
        val env: EnvState,
        val referenceCoords: List<Double>,        // 扁平 3N
        val timeAveragedCoords: List<Double>,    // 扁平 3N
        val stepCount: Int,
        val timePs: Double,
        val uHist: List<Double>,
        val trajectory: List<JSONObject>          // 自由 JSON（rmsd/rg/potential_energy/step）
    )

    fun build(
        sequence: String,
        env: EnvState,
        foldCoords: FloatArray?,
        mdCoords: FloatArray?,
        stepCount: Int,
        timePs: Double,
        uHist: List<Double>,
        model: String = "SPICE-native"
    ): String {
        fun flat(c: FloatArray?): JSONArray {
            val a = JSONArray()
            c?.forEach { a.put(Math.round(it * 1000.0) / 1000.0) }
            return a
        }
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        val meta = JSONObject()
            .put("sequence", sequence)
            .put("createdAt", java.time.Instant.now().toString())
            .put("model", model)
        root.put("meta", meta)
        root.put("environment", JSONObject()
            .put("ph", env.ph)
            .put("tempK", env.tempK)
            .put("pressureBar", env.pressureBar)
            .put("ionicStrengthM", env.ionicStrengthM))
        root.put("coordinates", JSONObject()
            .put("referenceCoords", flat(foldCoords))
            .put("currentTimeaveragedCoords", flat(mdCoords ?: foldCoords)))
        root.put("simulation_history", JSONObject()
            .put("stepCount", stepCount)
            .put("timePs", timePs)
            .put("potentialEnergyHistory", JSONArray().apply { uHist.forEach { put(it) } })
            .put("conformationTrajectory2D", JSONArray().apply { /* native 版不导出 2D 轨迹 */ }))
        root.put("biophysical_metrics", JSONObject()
            .put("m1_potential_fluctuation", JSONObject.NULL)
            .put("m2_rg_drift", JSONObject.NULL)
            .put("m3_ss_loss", JSONObject.NULL)
            .put("m4_clash_fraction", JSONObject.NULL)
            .put("m5_surface_charge_mismatch", JSONObject.NULL))
        return root.toString(2)
    }

    /** 解析 .spicep；非本格式或损坏返回 null */
    fun parse(text: String): Payload? {
        return try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) return null
            val meta = root.optJSONObject("meta") ?: return null
            val env = root.optJSONObject("environment") ?: return null
            val coords = root.optJSONObject("coordinates")
            val sim = root.optJSONObject("simulation_history")
            fun doubles(a: JSONArray?): List<Double> {
                val out = mutableListOf<Double>()
                a?.let { for (i in 0 until it.length()) out += it.optDouble(i, 0.0) }
                return out
            }
            Payload(
                sequence = meta.optString("sequence"),
                model = meta.optString("model", "unknown"),
                createdAt = meta.optString("createdAt", ""),
                env = EnvState(
                    ph = env.optDouble("ph", 7.0),
                    tempK = env.optDouble("tempK", 310.0),
                    pressureBar = env.optDouble("pressureBar", 1.0),
                    ionicStrengthM = env.optDouble("ionicStrengthM", 0.15)
                ),
                referenceCoords = doubles(coords?.optJSONArray("referenceCoords")),
                timeAveragedCoords = doubles(coords?.optJSONArray("currentTimeaveragedCoords")),
                stepCount = sim?.optInt("stepCount", 0) ?: 0,
                timePs = sim?.optDouble("timePs", 0.0) ?: 0.0,
                uHist = doubles(sim?.optJSONArray("potentialEnergyHistory")),
                trajectory = mutableListOf<JSONObject>().apply {
                    sim?.optJSONArray("conformationTrajectory2D")?.let { arr ->
                        for (i in 0 until arr.length()) (arr.optJSONObject(i))?.let { add(it) }
                    }
                }
            )
        } catch (e: Exception) {
            null
        }
    }
}
