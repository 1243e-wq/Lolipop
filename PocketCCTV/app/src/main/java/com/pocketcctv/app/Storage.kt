package com.pocketcctv.app

import android.content.Context
import java.io.File

/**
 * recording/  = the clip being written right now
 * ready/      = finished clips waiting for Google Drive backup
 */
object Storage {
    private fun base(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    fun recordingDir(ctx: Context): File = File(base(ctx), "recording").apply { mkdirs() }
    fun readyDir(ctx: Context): File = File(base(ctx), "ready").apply { mkdirs() }

    fun pending(ctx: Context): List<File> =
        readyDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedBy { it.lastModified() } ?: emptyList()

    /** Removes local clips older than the retention window (safety net if uploads never work). */
    fun cleanLocal(ctx: Context, days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        readyDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
