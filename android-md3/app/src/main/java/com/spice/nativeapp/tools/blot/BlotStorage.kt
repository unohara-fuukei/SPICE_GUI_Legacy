package com.spice.nativeapp.tools.blot

/*
 * Auto-save slot for the blot tool. 1:1 port of main's storage.ts
 * (localStorage -> Android SharedPreferences, same key). Like the web build
 * the snapshot keeps the full project including imageDataUrl, so a restored
 * session reloads its image (README notes the SharedPreferences backend).
 */

import android.content.Context
import org.json.JSONObject

object BlotStorage {
    private const val KEY = "spice_blot_project_v1"

    /** Load the last session snapshot. */
    fun loadSnapshot(ctx: Context): BlotProject? {
        val raw = ctx.getSharedPreferences(KEY, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        if (raw.isEmpty()) return null
        return try {
            sanitizeProject(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun saveSnapshot(ctx: Context, project: BlotProject): Boolean {
        return try {
            ctx.getSharedPreferences(KEY, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, projectToJson(project))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun clearSnapshot(ctx: Context) {
        ctx.getSharedPreferences(KEY, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }
}
