package com.spice.nativeapp.tools.qpcr

/*
 * Local autosave for the qPCR workspace. 1:1 port of main's
 * src/lib/tools/qpcr/storage.ts — SharedPreferences instead of localStorage
 * (same full-project JSON snapshot).
 */

import android.content.Context

object QpcrStorage {
    private const val KEY = "spice_qpcr_project_v1"

    fun loadSnapshot(ctx: Context): QpcrProject? = try {
        val raw = ctx.getSharedPreferences("spice_tools", Context.MODE_PRIVATE).getString(KEY, null)
        if (raw == null) null else normalizeProject(org.json.JSONObject(raw))
    } catch (e: Exception) {
        null
    }

    fun saveSnapshot(ctx: Context, project: QpcrProject): Boolean = try {
        ctx.getSharedPreferences("spice_tools", Context.MODE_PRIVATE)
            .edit().putString(KEY, projectToJson(project)).commit()
    } catch (e: Exception) {
        false
    }

    fun clearSnapshot(ctx: Context): Boolean = try {
        ctx.getSharedPreferences("spice_tools", Context.MODE_PRIVATE)
            .edit().remove(KEY).commit()
    } catch (e: Exception) {
        false
    }
}
