package com.spice.nativeapp.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object AttachmentStore {

    private fun dir(ctx: Context, projectId: Long): File =
        File(ctx.filesDir, "attachments/$projectId").apply { mkdirs() }

    fun add(ctx: Context, projectId: Long, uri: Uri, displayName: String): Attachment? = runCatching {
        val safe = displayName.replace(Regex("[^\\w\\u4e00-\\u9fa5._-]"), "_")
        val target = File(dir(ctx, projectId), safe)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        Attachment(displayName, "attachments/$projectId/$safe", target.length())
    }.getOrNull()

    fun file(ctx: Context, relPath: String): File = File(ctx.filesDir, relPath)

    fun delete(ctx: Context, att: Attachment) {
        runCatching { file(ctx, att.relPath).delete() }
    }

    fun shareUri(ctx: Context, att: Attachment): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file(ctx, att.relPath))
}
