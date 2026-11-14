package com.spice.nativeapp.tools.plate

// Local persistence for the plate designer. 1:1 port of
// src/lib/tools/plate/storage.ts. The browser snapshot is a convenience copy;
// the project JSON file is the portable backup. Platform divergence: Android
// autosave goes to SharedPreferences("spice_tools") instead of localStorage
// (disclosed in README Known gaps §11).

import android.content.Context
import org.json.JSONObject

private const val SNAPSHOT_KEY = "spice_plate_project_v1"

object PlateStorage {
    fun loadSnapshot(ctx: Context): NormalizedProject? {
        return try {
            val raw = ctx.getSharedPreferences("spice_tools", Context.MODE_PRIVATE)
                .getString(SNAPSHOT_KEY, null) ?: return null
            val normalized = normalizeProject(JSONObject(raw))
            if (normalized.project != null) normalized else null
        } catch (e: Exception) {
            null
        }
    }

    fun saveSnapshot(ctx: Context, project: PlateProjectFile): Boolean {
        return try {
            ctx.getSharedPreferences("spice_tools", Context.MODE_PRIVATE)
                .edit().putString(SNAPSHOT_KEY, project.toJson().toString()).commit()
        } catch (e: Exception) {
            false
        }
    }

    fun clearSnapshot(ctx: Context) {
        try {
            ctx.getSharedPreferences("spice_tools", Context.MODE_PRIVATE)
                .edit().remove(SNAPSHOT_KEY).commit()
        } catch (e: Exception) {
            /* ignore unavailable storage */
        }
    }
}

fun parsePlateProject(text: String): NormalizedProject? {
    return try {
        val normalized = normalizeProject(JSONObject(text))
        normalized.project?.let { normalized }
    } catch (e: Exception) {
        null
    }
}

/** JS JSON.stringify(project, null, 2) equivalent — pretty-printed, key order preserved. */
fun serializePlateProject(project: PlateProjectFile): String =
    project.toJson().toString(2)
